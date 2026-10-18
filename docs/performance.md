# Performance

Every number here was measured with `loadtest/run.sh`. Raw outputs are in [`loadtest/results/`](../loadtest/results/). Nothing is estimated.

## How to reproduce

```bash
# terminal 1: backend without the creation rate limit, with metrics exposed
cd backend
RATE_LIMIT_ENABLED=false MANAGEMENT_ENDPOINTS=health,metrics ./mvnw spring-boot:run

# terminal 2
cd loadtest
./run.sh cache-on            # label is free text; results/<label>-<timestamp>.md
# restart the backend with CACHE_ENABLED=false as well, then:
./run.sh cache-off
```

Method:
- **Tool:** ApacheBench (`ab -k`). It ships with macOS and is in `apache2-utils` on Linux.
- **Requests:** 20,000 per measurement at 32 concurrent keep-alive connections, and 3,000 at concurrency 1.
- **Warmup:** a discarded 3,000-request warmup before every measurement.
- **Repeats:** for the baseline, each configuration got a fresh JVM, one full discarded run, then **3 measured runs**. The tables show the median with the min–max of requests/s.

## Environment

| | |
|---|---|
| Machine | Apple M4, 10 cores, 16 GB, macOS 15.5. The load generator ran on the same machine. |
| Runtime | JDK 25.0.1 (bytecode targets Java 21), Spring Boot 3.5.16, default Tomcat and Hikari (10 connections) |
| Data stores | PostgreSQL 14.20 and Redis 8.6.1, both local (Homebrew) |
| Date | 2026-10-18 |

## Baseline: cache on (median of 3)

| Scenario | concurrency | req/s (min–max) | mean ms | p50 | p90 | p99 | errors |
|---|---|---|---|---|---|---|---|
| redirect (302) | 1 | 9,833 (9,358–9,954) | 0.102 | 0 | 0 | 0 | 0 |
| unknown code (404) | 1 | 6,871 (6,541–6,877) | 0.146 | 0 | 0 | 0 | 0 |
| create link (201) | 1 | 2,129 (2,024–2,431) | 0.470 | 0 | 1 | 1 | 0 |
| analytics, 30 days | 1 | 244 (228–284) | 4.085 | 4 | 6 | 6 | 0 |
| redirect (302) | 32 | **81,955** (81,019–82,934) | 0.390 | 0 | 0 | 1 | 0 |
| unknown code (404) | 32 | 40,515 (39,839–40,579) | 0.790 | 1 | 1 | 2 | 0 |
| create link (201) | 32 | 15,969 (15,513–16,517) | 2.004 | 2 | 3 | 7 | 0 |
| analytics, 30 days | 32 | 3,609 (3,592–4,902) | 8.867 | 9 | 11 | 16 | 0 |

## Comparison: cache off (`CACHE_ENABLED=false`, median of 3)

| Scenario | concurrency | req/s (min–max) | mean ms | p50 | p90 | p99 | errors |
|---|---|---|---|---|---|---|---|
| redirect (302) | 1 | 12,613 (12,328–12,680) | 0.079 | 0 | 0 | 0 | 0 |
| unknown code (404) | 1 | 8,165 (8,110–8,322) | 0.122 | 0 | 0 | 0 | 0 |
| create link (201) | 1 | 4,612 (4,607–4,742) | 0.217 | 0 | 0 | 0 | 0 |
| analytics, 30 days | 1 | 232 (232–282) | 4.298 | 4 | 6 | 6 | 0 |
| redirect (302) | 32 | **47,030** (46,188–48,391) | 0.680 | 1 | 1 | 2 | 0 |
| unknown code (404) | 32 | 31,829 (26,593–33,581) | 1.005 | 1 | 1 | 2 | 0 |
| create link (201) | 32 | 17,670 (17,355–18,332) | 1.811 | 2 | 2 | 7 | 0 |
| analytics, 30 days | 32 | 3,560 (3,559–4,814) | 8.988 | 9 | 11 | 17 | 0 |

No click events were dropped in any of the six measured runs.

## Reading the results

- **The cache's value is under concurrency.** Redirects went from 47.0k to 82.0k req/s (+74%) at 32 connections. Without it, every redirect competes for 10 pooled DB connections; Lettuce multiplexes one Redis connection.
- **At concurrency 1, cache-off was faster** (0.079 vs 0.102 ms). Here PostgreSQL serves a hot, indexed row from shared buffers as fast as Redis answers, and the cache path adds JSON decoding. Over a real network, with a larger working set or a busy database, the balance moves further toward the cache.
- **Write-through costs creates.** At concurrency 1, creating took 0.47 ms with the cache versus 0.22 ms without, because of the extra Redis `SET`. That's acceptable: redirects vastly outnumber creates.
- **Analytics is the slowest endpoint** (~4 ms serial), which is expected for two aggregations plus insight rules. That is still fast because both queries are index-only scans (see database.md).
- **Ceiling caveat.** At ~82k req/s the single-threaded `ab` client sharing the CPU may itself be a limit, so the cache-on redirect figure is a lower bound for the server.
- **`ab` percentiles are whole milliseconds**, so p50 = 0 means under 1 ms. The `mean ms` column carries the precision.

## Finding: per-click writes lost clicks under load, so they were batched

The first implementation wrote each click in its own transaction on a small thread pool. The load test exposed the limit:

| 200,000 redirects at ~77k req/s | persisted | dropped |
|---|---|---|
| Per-click transactions (2 threads) | 13,847 (~2.9k clicks/s) | 186,153 (93%) |
| Batched writes (1,000 per transaction, multi-row INSERT, aggregated counter UPDATE) | 136,000 and 117,000 in two runs (~35–45k clicks/s) | 64,000 / 83,000 |

After the change, the counter on the test link equalled its event rows exactly (273,000 = 273,000). Drops now happen only when a sustained burst outruns the 50k buffer at a rate far above realistic per-instance traffic, and they're counted (`linklens.clicks.dropped`), never silent.

## Finding: validate the load tool before trusting it

`autocannon` (Node.js) was tried first. It reported 44k req/s with a 70 ms median latency on 32 connections. Little's law (throughput ≈ concurrency ÷ latency) says that's impossible: 32 ÷ 0.070 s ≈ 457 req/s. Even at a gentle fixed 2,000 req/s it reported ~450 ms. ApacheBench, on the same server, gave numbers that agree with Little's law (32 ÷ 0.39 ms ≈ 82k). Those autocannon numbers were discarded. The likely cause is the generator's single saturated event loop, which counts its own queueing as server latency.

## Not measured (yet)

- Redis or PostgreSQL over a real network, and multiple app instances.
- Long soak tests (memory, GC, table growth). Each scenario here ran for seconds.
- Redirect latency with a cold cache over a large working set (millions of links).
