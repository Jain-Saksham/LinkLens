# Architecture

LinkLens is one Spring Boot service (a modular monolith) plus a React single-page app. PostgreSQL is the source of truth. Redis is an optional cache: the service keeps working without it.

```
 Browser ──► React SPA (Vite) ──/api──►┐
                                       │
 Anyone ──► GET /{code} ───────────────┤   Spring Boot (one process)
                                       ▼
   ┌───────────────────────────────────────────────────────────────┐
   │ RateLimitInterceptor (POST /api/v1/urls only)                 │
   │ Controllers ─► ShortUrlService ─► ShortUrlCache ─► Redis      │
   │                       │                 (miss / down)         │
   │                       ├──────────────► ShortUrlRepository ──┐ │
   │                       └─► ClickRecorder (bounded queue)     │ │
   │                              └─ flusher every 200 ms ───────┤ │
   │ Controllers ─► AnalyticsService ─► ClickEventRepository ────┤ │
   │                       └─► InsightGenerator (pure rules)     │ │
   └─────────────────────────────────────────────────────────────┼─┘
                                                                 ▼
                                                            PostgreSQL
```

## Packages (layered: controller → service → repository)

| Package | Responsibility |
|---|---|
| `controller` | HTTP only: binding, validation, status codes |
| `service` | Business rules and orchestration: create/resolve, analytics, insights, click recording |
| `cache` | Redis cache-aside for short-code lookups (`ShortUrlCache`, `CachedLink`) |
| `ratelimit` | Token bucket, per-client limiter, MVC interceptor |
| `repository` | Spring Data JPA; native SQL for aggregations |
| `domain` | JPA entities (`ShortUrl`, `ClickEvent`) |
| `dto` | Request/response records, kept separate from entities |
| `exception` | Domain exceptions and the global RFC 7807 handler |
| `config` | Typed properties, CORS, interceptor registration, scheduling, `Clock` |

## Request flows

**Redirect `GET /{code}`** (the hot path)
1. `ShortUrlCache.get(code)`. On a hit, the database is not touched. A cached "not found" also counts as a hit.
2. On a miss: `findByShortCode` (unique index), then `cache.put` with a TTL capped at the link's expiry.
3. Not found → 404. Expired (checked on every request, cached or not) → 410.
4. Bots are skipped. Otherwise `ClickRecorder.record(...)` enqueues the click in O(1), with no I/O.
5. 302 with `Cache-Control: no-store`.

**Create `POST /api/v1/urls`**
1. The interceptor takes a token from the client's bucket, or returns 429.
2. Bean Validation runs on the DTO, giving a 400 with field errors.
3. Service rules: no self-links, no reserved aliases.
4. Optimistic `INSERT`; the unique constraint decides races. Custom alias → 409. Generated code → retry with a new code.
5. After the commit, write-through into the cache, which also replaces any cached "not found" for that code.

**Click pipeline**
- Redirect threads only `offer()` to a bounded queue (50k). When it's full, the click is dropped and counted.
- One scheduled flusher drains up to 1,000 clicks per transaction: a multi-row `INSERT` into `click_events` plus one aggregated `click_count + n` per link.
- The buffer is flushed on graceful shutdown.

**Analytics `GET /api/v1/urls/{code}/analytics?days=N`**
- Two aggregate queries (daily counts, top referrers), each an index-only scan.
- Zero-filled in Java, then passed to `InsightGenerator`, a pure function of the aggregates.

## Failure modes

| Failure | Behaviour |
|---|---|
| Redis down or slow | One request pays the 250 ms timeout. Redis is then bypassed for 30 s, everything is served from PostgreSQL, and health stays UP. |
| PostgreSQL down | Cached links keep redirecting. Uncached lookups and writes fail within the 3 s pool timeout and return 503 + `Retry-After`. Clicks buffer and then drop (counted). |
| Click writer behind | Queue absorbs bursts (50k). Beyond that, clicks are dropped and counted, and redirects never slow down. |
| Same alias requested concurrently | Exactly one `INSERT` wins; the rest get 409 (integration-tested with 16 threads). |
| Generated-code collision | Caught by the constraint name; retried with a new code (up to 5 times). |
| Unexpected exception | Logged server-side; the client gets a generic 500 ProblemDetail with no internals. |
| Process shutdown | Graceful: in-flight requests finish, and the click buffer is flushed. |

## Deliberately not built

Microservices, message brokers, Kubernetes and paid APIs are out of scope. Each "next step" in the docs names the simplest change that would remove the current limit: Redis-backed rate limits, table partitioning, a CDN in front of redirects.
