# Interview guide

A one-page walkthrough of LinkLens for interviews: what happens on each request, why it's built this way, how it fails, and how it scales. Deeper Q&A per topic is in [interview-notes.md](interview-notes.md).

## 1. Thirty-second pitch

A URL shortener that **also watches its links**:
- background checks flag destinations that die (404, dead domain, redirect loop, or a page silently replaced);
- a `+` preview shows where any link really goes;
- analytics are privacy-first, and click counts update live.

It's a Spring Boot modular monolith on PostgreSQL, with Redis as an optional cache and a React SPA, deployed on free tiers. CI gates every deploy.

## 2. Request flows

**Redirect `GET /abc`** (the hot path)
1. The browser connects to Render's edge (TLS), which forwards to the container.
2. `RedirectController` checks the path against a regex, so only valid codes reach the service.
3. `ShortUrlCache.get("abc")` → Redis.
   - **Hit:** no DB access at all.
   - **Miss:** `SELECT … WHERE short_code = ?` on the unique index, then cache the result with TTL = `min(1h, time left before expiry)`.
   - An unknown code is cached as "not found" for 60 s.
4. Expiry is checked against the clock on every request: expired → 410, unknown → 404.
5. Bots are skipped. Otherwise the click (link id, timestamp, referrer host) goes into a bounded in-memory queue: O(1), no I/O.
6. `302 Location: …` with `Cache-Control: no-store`.
7. Within 200 ms, the flusher writes the batch in one transaction (multi-row INSERT + `click_count = click_count + n`). After commit, it pushes new counts to anyone watching over SSE.

**Create `POST /api/v1/urls`**
1. Token bucket per client IP: 20 burst, 20/min; otherwise 429 + `Retry-After`.
2. Bean Validation (http(s) only, ≤ 2048 chars, alias pattern, future expiry) → 400 with per-field errors.
3. Rules: no links to LinkLens itself, no reserved aliases.
4. One transaction: `INSERT short_urls` + `INSERT link_health (PENDING)`. **There's no exists-check first; the unique constraint decides.** A taken alias → 409; a random-code collision → retry with a new code.
5. After commit, write the link into the cache (this also replaces a cached "not found").

**Background link-rot check**
1. Every poll, claim due rows with `FOR UPDATE SKIP LOCKED` and push them out with a 10-minute lease.
2. Fetch each destination outside any transaction, SSRF-safe (connect-time IP validation, every redirect hop, timeouts, title only).
3. A pure evaluator computes the next state: 2 consecutive failures → BROKEN, backoff with jitter, title drift → WARNING.

**Preview `GET /abc+`**
- Use the stored inspection if it's under 6 h old; otherwise join a single in-flight check and wait up to 2.5 s.
- Render escaped HTML under `CSP default-src 'none'`.

## 3. Key trade-offs

| Decision | Chosen | Gave up |
|---|---|---|
| Short codes | 7 random Base62 characters with optimistic insert | Shorter sequential codes (enumerable) |
| Redirect status | 302 + `no-store` | Free browser caching (301): every click reaches the server, so counts and expiry stay correct |
| Click writes | Batched write-behind, bounded, drop-and-count | Guaranteed delivery (a crash loses ≤ ~200 ms; a full queue drops) |
| Cache | Cache-aside, fail-open, TTL ≤ expiry | A few ms when Redis is down; at concurrency 1 locally the DB was even faster |
| Rate limiting | In-memory token bucket | Global limits across instances |
| Health checks | Debounced states, bot walls = "unverifiable" | Instant alerts (a failure must repeat) |
| Analytics privacy | Referrer host only; no IP/UA stored | Geo, device and unique-visitor metrics |
| Hosting | Free tiers | Always-on: cold starts after 15 idle minutes |

## 4. Failure scenarios

| What fails | What users see | Why |
|---|---|---|
| Redis down | Nothing (one request pays 250 ms) | Fail-open, then Redis is bypassed for 30 s; health stays UP |
| PostgreSQL down | Cached links still redirect; others get 503 + `Retry-After` within 3 s | Short pool timeout; DB errors are mapped to 503, not 500 |
| Click writer behind | Nothing; counts may be slightly low | Bounded queue drops and counts (`linklens.clicks.dropped`) |
| Two people take the same alias at once | One 201, the other 409 | The unique constraint arbitrates (tested with 16 threads) |
| Destination slow, hung or huge | The check records TIMEOUT and backs off | Per-hop + total deadlines, 1 MiB read cap |
| Someone shortens `http://169.254.169.254/` | Health shows "blocked", nothing is fetched | Connect-time address policy, re-checked on every hop |
| Instance crash mid health-check | The link is re-checked later | The lease expires and the row becomes due again |
| Free instance asleep | The first visitor waits for the cold start | Platform limitation, measured in deployment.md |
| Bad deploy | Never reaches production | Render deploys only commits whose CI checks passed |

## 5. How it scales (in the order you'd need it)

1. **Read path:** the cache already absorbs repeat lookups. Next, add a small in-process L1 (Caffeine) in front of Redis, then put a CDN in front of redirects for the hottest links (accepting fewer counted clicks).
2. **Multiple app instances:** stateless except for three things. Rate limits move to Redis (atomic Lua token bucket); live counts fan out via Redis pub/sub; click buffers are per-instance, which is fine. Health claims already work across instances (`SKIP LOCKED`).
3. **Database:** separate read replicas for analytics. Partition `click_events` by month (drop old partitions for retention) and roll up into daily counts. `short_urls` stays tiny and indexed.
4. **Clicks at very high volume:** replace the in-memory buffer with a durable log (Redis Streams, then Kafka) and aggregate in a consumer. That gains delivery guarantees at the cost of new infrastructure.
5. **Global latency:** run regional app instances with the cache close to users, and keep writes in one primary region (they're rare compared with redirects).

## 6. Numbers worth remembering

- **Redirect throughput (local, 32 connections):** 82k req/s with the cache, 47k without (median of 3 runs).
- **Analytics queries:** index-only scans; 10 buffers read out of 789k rows, 0.3–2.3 ms.
- **Click persistence:** batching took it from ~2.9k to ~35–45k clicks/s.
- **Cold start at 0.1 CPU (local simulation):** 145 s → 89 s with Class Data Sharing.
- **Tests:** 196 backend (including Testcontainers against real PostgreSQL + Redis), 34 frontend, and a 21-step end-to-end smoke test.
