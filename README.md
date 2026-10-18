# LinkLens

An interview-focused URL shortener:
- short links, custom aliases, expiry and URL validation;
- privacy-respecting **click analytics** with rule-based insights;
- a **Redis** cache that fails open;
- **rate-limited** link creation;
- a reproducible **load test** with measured results.

| Part | Stack |
|---|---|
| `backend/` | Java 21, Spring Boot 3.5, Maven, PostgreSQL + Flyway, Redis (optional cache) |
| `frontend/` | React 19, TypeScript, Vite 7, Tailwind 4 |
| `loadtest/` | ApacheBench script and committed baseline results |
| `docs/` | Architecture, API, database, caching, security, performance, interview notes |

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
- Copy it, open it, or open its **Analytics** page (`/links/{code}`): totals, a daily chart, top sources and insights.
- "Your links" lives in your browser's `localStorage`, because there are no accounts.

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
| `MANAGEMENT_ENDPOINTS` | `health` | Add `metrics` for cache and click counters |

## API (summary)

| Method | Path | Success | Errors |
|---|---|---|---|
| `POST` | `/api/v1/urls` | `201` link | `400`, `409` alias taken, `429` rate limited, `503` |
| `GET` | `/{code}` | `302` redirect (click recorded) | `404`, `410` expired |
| `GET` | `/api/v1/urls/{code}` | `200` link + all-time clicks | `404` |
| `GET` | `/api/v1/urls/{code}/analytics?days=30` | `200` totals, daily series, referrers, insights | `400`, `404` |

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

- [Architecture](docs/architecture.md): components, request flows, failure modes
- [API contract](docs/api.md)
- [Database & indexing](docs/database.md): schema, indexes, measured query plans
- [Caching](docs/caching.md): TTL/expiry, invalidation, fail-open
- [Security & privacy](docs/security.md): rate limiting, validation, data collected
- [Performance](docs/performance.md): how to reproduce, baseline numbers
- [Interview notes](docs/interview-notes.md): decisions, trade-offs, likely questions

## Project layout

```
backend/src/main/java/com/linklens/
  controller/   HTTP only: ShortUrlController (API + analytics), RedirectController (/{code})
  service/      ShortUrlService, AnalyticsService, InsightGenerator, ClickRecorder, ClickSource
  cache/        ShortUrlCache (Redis cache-aside, fail-open), CachedLink
  ratelimit/    TokenBucket, RateLimiter, RateLimitInterceptor
  repository/   Spring Data JPA + native aggregation queries
  domain/       JPA entities: ShortUrl, ClickEvent
  dto/ exception/ validation/ config/
backend/src/main/resources/db/migration/   V1 short_urls, V2 click_events

frontend/src/
  pages/        HomePage, AnalyticsPage
  components/   ShortenForm, ResultCard, RecentLinks, analytics/ (chart, sources, insights)
  lib/          Pure, unit-tested logic: validation, expiry, formatting, chart maths, router
  api/client.ts Typed fetch wrapper; ProblemDetail → ApiError
```
