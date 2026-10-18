# Interview notes

Each topic: **problem → alternatives → choice → trade-offs**, then two likely questions with short answers. Details live in the linked docs.

---

## 1. Short-code generation

- **Problem:** codes must be short and unique, and must not be guessable.
- **Alternatives:**
  - Base62 of the DB id: no collisions, but enumerable, and it leaks volume.
  - Hash of the URL, truncated: still collides, and the same URL always gets the same code.
  - Pre-generated key pool or Snowflake ids: built for very large, multi-writer scale.
- **Choice:** 7 random Base62 characters from `SecureRandom` (62⁷ ≈ 3.5 × 10¹²). Insert optimistically and let the unique constraint arbitrate; on a short-code clash, retry with a new code.
- **Trade-off:** collisions are possible but astronomically rare until billions of rows. There's no extra round trip for an "exists?" check.

**Q: Why not check `exists` before inserting?**
Check-then-insert is a race: two requests can both see "free". The unique constraint is the only atomic arbiter, so inserting and handling the violation is both correct and one query cheaper. The service also checks the violated *constraint name*, so a different integrity error is never mislabelled as a conflict.

**Q: When would you move to a key-generation service?**
When many writers create codes so fast that collision retries become measurable, or when codes must be shorter than random selection allows at that volume. Then you'd pre-allocate id ranges per instance and encode them.

---

## 2. Redirect semantics

- **Problem:** redirect fast, count clicks, and enforce expiry.
- **Alternatives:**
  - 301 (cacheable by browsers forever).
  - 307/308 (preserve the HTTP method; irrelevant for GET).
- **Choice:** `302` + `Cache-Control: no-store`; `410 Gone` for expired links, 404 for unknown ones.
- **Trade-off:** every visit reaches the server (more load) in exchange for correct counts and expiry. The cache absorbs that load.

**Q: Why 410 instead of 404 for expired links?**
404 means "never existed or unknown", 410 means "existed, intentionally gone". Crawlers drop 410s faster, and clients can explain them properly.

**Q: Isn't 302 an open-redirect risk?**
Only the stored target is used (no user-supplied redirect parameter), and targets are restricted to http(s). Remaining abuse (phishing links) is a content problem: URL reputation checks and reporting, noted as future work in security.md.

---

## 3. Link intelligence (click events + insights)

- **Problem:** show trends and sources without collecting personal data.
- **Alternatives:**
  - Only the counter: no trends.
  - Full request logs (IP, UA, URL): invasive.
  - A third-party analytics service: data leaves the system, and it's paid.
- **Choice:**
  - Store only `(link, timestamp, referrer host)`. Bots are skipped in memory.
  - Aggregate in SQL (two queries over a covering index) and zero-fill days in Java.
  - Insights are pure rules with minimum sample sizes, comparing *complete* days week over week.
- **Trade-offs:**
  - No unique-visitor metric, by design.
  - Daily buckets are UTC.
  - Rules are simple, but explainable and testable; an ML model would be neither, at this data volume.

**Q: How do you avoid misleading insights like "+200%"?**
- Minimum samples: a trend needs ≥ 5 clicks in the baseline week, and a dominant source needs ≥ 10 clicks.
- Today is excluded from week-over-week, because a half-finished day looks like a decline every morning.
- With nothing significant to say, the API returns an explicit "Collecting data" insight instead of manufacturing one.

**Q: How does this scale to billions of events?**
- Roll events up into a daily table and partition raw events by month, dropping old partitions.
- Serve analytics from a read replica.
- The covering index keeps per-link queries proportional to *that link's* recent clicks, not to table size: measured as 10 buffers on a 789k-row table.

---

## 4. Click pipeline (write-behind batching)

- **Problem:** recording clicks must not slow redirects or make them fail.
- **Alternatives:**
  - Synchronous write: adds latency, and the redirect fails when the DB does.
  - `@Async` per click: measured at only ~2.9k clicks/s, losing 93% in a burst.
  - Kafka: durable, but a new system to run.
- **Choice:** redirect threads `offer()` to a bounded in-memory queue. A single flusher writes every 200 ms in batches of 1,000: a multi-row INSERT plus aggregated `click_count + n` updates, in one transaction.
- **Trade-offs:**
  - Counts lag by ~200 ms.
  - When the buffer is full, clicks are dropped and counted rather than slowing redirects.
  - A hard crash loses at most the buffer; graceful shutdown flushes it.
  - Measured throughput after batching: ~35–45k clicks/s.

**Q: Why drop clicks instead of blocking or using CallerRunsPolicy?**
Blocking or caller-runs puts the database write back on the redirect thread exactly when the database is slow, turning an analytics problem into an outage. A counted drop (`linklens.clicks.dropped`) is visible and bounded.

**Q: What would you change for guaranteed delivery?**
Use a durable log (a Redis Stream, or Kafka at scale), or an outbox table written in the request's transaction, then consume and aggregate asynchronously. You pay with an extra system or an extra write per redirect.

---

## 5. Caching (Redis)

- **Problem:** redirects are read-heavy, and the DB pool limits concurrency.
- **Alternatives:**
  - `@Cacheable`: no per-entry TTL, awkward negative caching.
  - In-process Caffeine: per-instance copies.
  - CDN caching of redirects: loses click counts.
- **Choice:** explicit cache-aside on the redirect path, plus write-through on create.
  - TTL = `min(1 h, time to expiry)`.
  - Negative entries last 60 s.
  - Fail open with a 30 s bypass after any Redis error.
  - Redis is excluded from health checks.
- **Trade-offs:**
  - Measured +74% redirect throughput at 32 connections.
  - Slightly slower at concurrency 1 locally, and slower creates (an extra `SET`).
  - Edits made outside the app are visible after at most one TTL.

**Q: How do you keep the cache correct when links expire?**
Two layers. Each entry stores `expiresAt` and every redirect checks it against the clock, which is exact. The TTL is capped at the expiry, so dead entries also leave memory on time. An integration test proves a cached link turns into a 410 the moment it expires.

**Q: What happens if Redis goes down?**
The first request pays one 250 ms timeout. Then the cache is bypassed for 30 s and everything is served from PostgreSQL (measured at 2–4 ms per redirect), and health stays UP. When Redis returns, the next probe after the backoff resumes caching. Integration test: `RedisUnavailableIntegrationTest`.

---

## 6. Rate limiting

- **Problem:** stop abusive link creation without hurting normal users.
- **Alternatives:**
  - Fixed window: 2× bursts at window edges.
  - Sliding log: memory per request.
  - Redis + Lua: works across instances, but adds a network hop and a dependency.
- **Choice:** an in-memory token bucket per client IP (20 burst, 20/min) on `POST` only, with lazy refill. Responses carry `429`, `Retry-After` and `X-RateLimit-*` headers.
- **Trade-offs:**
  - The limit is per instance (N instances ⇒ N× the limit).
  - The eviction race can allow one extra request.
  - The IP key is weak against large botnets and harsh on shared NATs.

**Q: Walk through the concurrency.**
- `computeIfAbsent` atomically creates one bucket per client.
- `tryConsume` is `synchronized` on that bucket, because refill + check + decrement must be atomic.
- Contention is per client only. A test with 64 threads and 10 tokens gets exactly 10.

**Q: How would you make it global across instances?**
Store `tokens` and `lastRefill` in Redis and run refill-and-consume in one Lua script (atomic on the Redis side). Decide the failure policy explicitly: fail-open on creation is reasonable here.

---

## 7. Reliability

- **Problem:** races, expired data, database failures and bad input must produce correct, explicit responses.
- **Choice:**
  - The unique constraint arbitrates concurrent aliases (16 parallel requests → one 201 and fifteen 409s, integration-tested).
  - Expiry is checked on every path.
  - A 3 s pool timeout plus `503 Retry-After` for DB outages.
  - A generic 500 that never leaks internals.
  - Validation at the edge (Bean Validation), business rules in the service.
  - Graceful shutdown.
- **Trade-off:** the service returns 503 rather than queueing writes during DB outages. Simpler, and the client knows to retry.

**Q: What does a user see if PostgreSQL dies?**
- Cached links keep redirecting.
- Uncached redirects and creates fail fast (≤ 3 s) with 503 + `Retry-After`.
- Clicks buffer in memory and are dropped once the buffer is full.
- Nothing hangs, and no stack traces are exposed.

**Q: Why map only one constraint name to 409?**
A `DataIntegrityViolationException` can come from any constraint (a check constraint, a foreign key). Treating all of them as "alias taken" would hide real bugs behind a misleading 409.

---

## 8. Testing strategy

- **Choice:**
  - Pure unit tests: token bucket, insights, referrer parsing, chart maths.
  - Mockito service tests.
  - `@WebMvcTest` slices for HTTP contracts.
  - `@DataJpaTest` against real PostgreSQL for native SQL.
  - Full `@SpringBootTest` integration tests with **Testcontainers** (PostgreSQL + Redis) for cache, expiry, races, Redis outage and the click pipeline.
- **Trade-offs:**
  - Integration tests need Docker. They skip automatically without it, so they must also run in CI.
  - Containers are shared across test classes to keep runs fast.

**Q: Why not H2 for tests?**
The app relies on PostgreSQL behaviour: `AT TIME ZONE`, `to_char`, `INCLUDE` indexes, constraint names, `reWriteBatchedInserts`. H2 would pass tests that fail in production.

**Q: How do you test time-dependent logic?**
Inject a `Clock` and use fixed or mutable clocks in tests: expiry, cache backoff and insight windows need no sleeps. The token bucket takes a `LongSupplier` nano clock for the same reason.

---

## 9. Performance methodology

- **Choice:** ApacheBench with keep-alive, a discarded warmup, concurrency 1 (latency) and 32 (throughput), and the median of 3 runs on fresh JVMs. Raw results are committed.
- **Lesson:** sanity-check every tool with Little's law. The first tool reported impossible latencies and was discarded, and the load test also found the click-loss bug that led to batching.

**Q: What's the bottleneck at 82k redirects/s?**
Probably the single-threaded load generator sharing the CPU, which is why that figure is reported as a lower bound. Without the cache, it's the 10-connection DB pool, which is why throughput drops to 47k.

**Q: What would you measure next?**
Data stores across a real network, a cold cache over millions of links, multiple instances behind a load balancer, and a long soak test for GC and table growth.

---

## 10. Frontend

- **Choice:**
  - React with a two-route History-API router instead of a routing library.
  - Data fetching with `useState` and one hook.
  - An inline-SVG chart instead of a chart library: one series, validated colors, hover and keyboard readout, and a table view.
  - Pure logic in `lib/` with Vitest tests.
- **Trade-off:** less framework, slightly more hand-written code. Fine at this size, and easy to replace.

**Q: Why no chart library?**
One single-series column chart is ~150 lines of SVG. A library would add ~100 kB and its own styling system to fight. With more chart types I'd adopt one.

**Q: How is the chart accessible?**
- The SVG has a summary `aria-label` and is focusable, with arrow-key navigation through the days.
- Tooltips repeat values that are also in a table view.
- Colors were checked for contrast in both themes.
- Status in insights is never color alone: each one has an icon and a text label.
