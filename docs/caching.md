# Caching

Implementation: `backend/src/main/java/com/linklens/cache/ShortUrlCache.java`.

## What is cached

Only short-code lookups, the input to every redirect:

```
key:   linklens:v1:link:{code}                    (v1 = version of the JSON shape)
value: {"id":42,"originalUrl":"https://…","expiresAt":"2026-11-01T00:00:00Z"}
       {"id":null,…}                              (negative entry: "this code doesn't exist")
```

Analytics responses are not cached: they change with every click and are already cheap (index-only scans).

## Pattern: cache-aside with write-through on create

```
redirect:  cache.get ──hit──► use it
              └─miss─► DB findByShortCode ─► cache.put ─► use it
create:    DB INSERT (committed) ─► cache.put        (also overwrites a negative entry)
```

## TTL and expiry

| Entry | TTL |
|---|---|
| Link without expiry | `CACHE_TTL` (1 h) |
| Link with expiry | `min(1 h, time until expiresAt)`, so an entry never outlives its link |
| Already-expired link | not cached; the database answers 410 |
| Unknown code (negative) | `CACHE_NEGATIVE_TTL` (60 s): absorbs scans and typos |

The TTL is not what enforces expiry. Every redirect, cached or not, compares `expiresAt` with the clock, so a link stops working on the exact second it expires. The TTL only bounds memory and staleness. Integration test: `cachedLinkStopsRedirectingTheMomentItExpires`.

## Invalidation

Links are immutable after creation: no update or delete API. That makes invalidation simple:
- **Creation** writes the new value, replacing any negative entry for that code.
- **Expiry** is handled by the TTL cap plus the per-request check.
- **Manual DB edits** (outside the app) become visible after at most one TTL. If edit or delete endpoints are added, they must `DEL` the key after their transaction commits. For multiple instances, the same rule holds because the cache is shared.

## Failure handling: fail open

Redis is an optimization, never a dependency.
- Every Redis call is wrapped. Any error is counted (`linklens.cache{result=error}`), logged, and treated as a miss.
- After an error, Redis is **bypassed for 30 s** (`CACHE_FAILURE_BACKOFF`), so a dead Redis costs one 250 ms timeout instead of 250 ms on every request.
- An unreadable entry (for example, written by a different version) is a miss. It does not disable the cache.
- Redis is excluded from `/actuator/health`, so a cache outage doesn't take instances out of a load balancer.

Measured with Redis killed mid-run: the first redirect took 0.26 s (the timeout), the next ones took 2–4 ms from PostgreSQL, and health stayed `UP`.

## What it buys (measured; see performance.md)

| Redirect, 32 concurrent connections | req/s (median of 3) |
|---|---|
| Cache on | 81,955 |
| Cache off | 47,030 |

At one request at a time, cache-off was slightly faster (0.079 vs 0.102 ms). Locally, PostgreSQL serves a hot indexed row from memory as fast as a Redis round trip plus JSON decoding. The cache pays off under concurrency (no competition for the 10 pooled DB connections) and protects the database from read load. The write-through also costs something: creation is slower with the cache on (one extra round trip).

## Alternatives considered

| Option | Why not (here) |
|---|---|
| Spring `@Cacheable` + `RedisCacheManager` | No per-entry TTL tied to the link's expiry, awkward negative caching, and less explicit failure handling |
| In-process cache (Caffeine) | Fastest, but each instance has its own copy, so invalidation across instances needs pub/sub. A good **L1** in front of Redis later. |
| Write-behind / refresh-ahead | No benefit for immutable data |
| Caching 404s forever | A later creation of that alias would be invisible; hence the short negative TTL plus write-through |
