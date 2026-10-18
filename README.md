# LinkLens

An interview-focused URL shortener:
- short links, custom aliases, expiry and URL validation;
- privacy-respecting **click analytics** with rule-based insights;
- a **Redis** cache that fails open;
- **rate-limited** link creation;
- a reproducible **load test** with measured results.

What sets it apart from typical shorteners:
- **Link-rot monitoring.** Every destination is re-checked in the background (SSRF-safe). A link that starts returning 404, loops in redirects, loses its domain, or gets replaced by a different page is flagged: *"Your link points to a dead page."*
- **A transparent preview.** Add `+` to any short link (`/docs+`) to see where it really goes before visiting: final URL after redirects, page title, HTTPS, health, expiry.
- **A live click counter.** Watch clicks arrive in real time (Server-Sent Events).

| Part | Stack |
|---|---|
| `backend/` | Java 21, Spring Boot 3.5, Maven, PostgreSQL + Flyway, Redis (optional cache) |
| `frontend/` | React 19, TypeScript, Vite 7, Tailwind 4 |
| `loadtest/` | ApacheBench script and committed baseline results |
| `docs/` | Architecture, API, database, caching, security, performance, interview notes |

## Quick start with Docker

```bash
docker compose --profile app up -d --build      # UI http://localhost:3000 · API + short links http://localhost:8080
scripts/smoke-test.sh http://localhost:8080 http://localhost:3000
```

`docker compose up -d` (no profile) starts only PostgreSQL (port 5433) and Redis, for running the app from your IDE. Deployment to free tiers (Render + Neon) is covered in [docs/deployment.md](docs/deployment.md).

## Prerequisites

- JDK 21+ (the build targets Java 21 bytecode via `--release 21`). Maven isn't needed; use `./mvnw`.
- PostgreSQL 14+.
- Redis 6+ (optional: without it, everything is served from PostgreSQL).
- Node.js 20.19+ for the frontend.
- Docker, for the Testcontainers integration tests only (skipped without it).
- ApacheBench (`ab`), for the load test only (bundled with macOS; `apt install apache2-utils` on Linux).

## Run it

```bash
# 1. Data stores
createdb linklens                       # or: CREATE DATABASE linklens;
brew services start redis               # or: docker run -d -p 6379:6379 redis:7-alpine   (optional)

# 2. Backend: http://localhost:8080
cd backend
cp -n .env.example .env                 # first time only (-n never overwrites); then edit DB_USERNAME / DB_PASSWORD
./mvnw spring-boot:run

# 3. Frontend: http://localhost:5173 (second terminal)
cd frontend
npm install
npm run dev
```

> **Homebrew PostgreSQL?** It has no `postgres` role (you'll see `FATAL: role "postgres" does not exist`). Set `DB_USERNAME` to your macOS username (`whoami`) and leave `DB_PASSWORD` empty.

Flyway creates and migrates the schema on startup. Check `http://localhost:8080/actuator/health` → `{"status":"UP"}`. The Vite dev server proxies `/api` to the backend.

**In the UI:**
- Shorten a link, optionally with an alias and an expiry.
- Copy, open or **preview** it, and watch its click count go live.
- Its **Analytics** page (`/links/{code}`) has live totals, a daily chart, top sources, insights and **destination health**, with a "Check now" button and a banner when the link is broken.
- "Your links" lives in your browser's `localStorage`, because there are no accounts. It flags broken links.

Destinations are checked in the background (first check within ~30 s, then daily, backing off when failing). The preview page is served by the backend at `http://localhost:8080/{code}+`.

### Configuration

All settings are environment variables. `backend/.env` is loaded if present, and real env vars override it. The full list with comments is in [backend/.env.example](backend/.env.example).

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | local `linklens` DB, `postgres`/`postgres` | PostgreSQL connection |
| `DB_CONNECTION_TIMEOUT_MS` | `3000` | Fail fast when the DB is down (returns 503) |
| `REDIS_URL` | `redis://localhost:6379` | Cache location |
| `CACHE_ENABLED`, `CACHE_TTL`, `CACHE_NEGATIVE_TTL` | `true`, `1h`, `60s` | Cache behaviour |
| `RATE_LIMIT_ENABLED`, `RATE_LIMIT_CAPACITY`, `RATE_LIMIT_REFILL_PERIOD` | `true`, `20`, `1m` | Creation limit per client IP |
| `FORWARD_HEADERS_STRATEGY` | `none` | Set to `native` behind a trusted proxy |
| `APP_BASE_URL` | `http://localhost:8080` | Origin used in returned short links |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | Frontend origins |
| `HEALTH_CHECKS_ENABLED`, `HEALTH_CHECK_INTERVAL`, `HEALTH_FAILURE_THRESHOLD` | `true`, `24h`, `2` | Link-rot monitoring |
| `LIVE_MAX_PER_LINK`, `LIVE_MAX_TOTAL` | `50`, `1000` | Caps on open live-count streams |
| `MANAGEMENT_ENDPOINTS` | `health` | Add `metrics` for cache and click counters |

## API (summary)

| Method | Path | Success | Errors |
|---|---|---|---|
| `POST` | `/api/v1/urls` | `201` link | `400`, `409` alias taken, `429` rate limited, `503` |
| `GET` | `/{code}` | `302` redirect (click recorded) | `404`, `410` expired |
| `GET` | `/api/v1/urls/{code}` | `200` link + all-time clicks | `404` |
| `GET` | `/api/v1/urls/{code}/analytics?days=30` | `200` totals, daily series, referrers, insights | `400`, `404` |
| `GET` | `/{code}+` | `200` HTML preview of the destination | `404`, `410` |
| `GET` | `/api/v1/urls/{code}/health` | `200` state, cause, final URL, title, redirect chain | `404` |
| `POST` | `/api/v1/urls/{code}/health/check` | `200` fresh check (60 s cooldown) | `404`, `429` |
| `GET` | `/api/v1/urls/{code}/clicks/stream` | SSE `clicks` events `{clickCount}` | `404`, `503` |

```bash
curl -X POST localhost:8080/api/v1/urls -H 'Content-Type: application/json' \
  -d '{"url":"https://spring.io","customAlias":"spring","expiresAt":"2030-01-01T00:00:00Z"}'
```

Full contract, including error format and headers: [docs/api.md](docs/api.md).

## Tests

```bash
cd backend  && ./mvnw verify   # unit (JUnit/Mockito), web slices, @DataJpaTest + integration (Testcontainers)
cd frontend && npm test        # Vitest: validation, expiry, formatting, chart maths, routing
cd frontend && npm run build   # type-check + production build
```

| Area | Covered by |
|---|---|
| Redirect + cache | First hit populates; later hits skip the DB; TTL ≤ link expiry; expired cached link → 410 the moment it expires; negative cache replaced on create |
| Redis outage | App keeps creating and redirecting with Redis unreachable, without per-request timeouts |
| Concurrency | 16 parallel requests for one alias → exactly one 201; parallel generated codes all unique; token bucket never overspends |
| Analytics | Referrer stored as host only; bots not counted; UTC day boundaries; aggregation SQL on real PostgreSQL; insight rules |
| Errors | 400 / 404 / 409 / 410 / 429 / 503 / generic 500 contracts |
| SSRF | 38 address cases (metadata, RFC 1918, IPv6 ULA, IPv4-in-IPv6, NAT64); localhost blocked via DNS; a redirect into 169.254.169.254 is never requested |
| Link health | Real HTTP against a local site: healthy → warning → broken with backoff, redirect chains, title drift, timeouts, loops, huge pages, bot walls; `SKIP LOCKED` claims are disjoint under concurrency; expired links retired |
| Preview | Attacker-controlled titles escaped; placeholder injection impossible; CSP headers; doesn't count a click |
| Live counts | SSE stream delivers the current count, then updates after clicks commit; subscriber caps |

Integration tests run against real PostgreSQL 16 and Redis 7 containers and skip themselves when Docker isn't available.

## Load test

```bash
cd backend && RATE_LIMIT_ENABLED=false MANAGEMENT_ENDPOINTS=health,metrics ./mvnw spring-boot:run
cd loadtest && ./run.sh cache-on
```

Measured baseline (Apple M4, everything local, median of 3 runs, 32 connections):

| Scenario | Cache on | Cache off |
|---|---|---|
| Redirect | 81,955 req/s | 47,030 req/s |
| Create link | 15,969 req/s | 17,670 req/s |
| Analytics (30 days) | 3,609 req/s | 3,560 req/s |

Method, latencies, caveats and findings are in [docs/performance.md](docs/performance.md).

## Docs

- [Deployment](docs/deployment.md): providers and verified free-tier limits, env vars, steps, limitations
- [Interview guide](docs/interview-guide.md): request flows, trade-offs, failure scenarios, scaling
- [Architecture](docs/architecture.md): components, request flows, failure modes
- [API contract](docs/api.md)
- [Database & indexing](docs/database.md): schema, indexes, measured query plans
- [Caching](docs/caching.md): TTL/expiry, invalidation, fail-open
- [Security & privacy](docs/security.md): SSRF protection, preview output encoding, rate limiting, validation, data collected
- [Performance](docs/performance.md): how to reproduce, baseline numbers
- [Interview notes](docs/interview-notes.md): decisions, trade-offs, likely questions

## Project layout

```
backend/src/main/java/com/linklens/
  controller/   HTTP only: ShortUrlController (API + analytics), RedirectController (/{code})
  service/      ShortUrlService, AnalyticsService, InsightGenerator, ClickRecorder, ClickSource
  cache/        ShortUrlCache (Redis cache-aside, fail-open), CachedLink
  inspection/   DestinationInspector, SafeDnsResolver + AddressPolicy (SSRF guard), HtmlTitle
  health/       Link-rot monitoring: HealthEvaluator (pure rules), LinkHealthService, scheduler
  preview/      /{code}+ server-rendered preview page
  live/         LiveClickRegistry: SSE subscribers per link
  ratelimit/    TokenBucket, RateLimiter, RateLimitInterceptor
  repository/   Spring Data JPA + native aggregation queries
  domain/       JPA entities: ShortUrl, ClickEvent
  dto/ exception/ validation/ config/
backend/src/main/resources/db/migration/   V1 short_urls, V2 click_events, V3 link_health

frontend/src/
  pages/        HomePage, AnalyticsPage
  components/   ShortenForm, ResultCard, RecentLinks, analytics/ (chart, sources, insights)
  lib/          Pure, unit-tested logic: validation, expiry, formatting, chart maths, router
  api/client.ts Typed fetch wrapper; ProblemDetail → ApiError
```
