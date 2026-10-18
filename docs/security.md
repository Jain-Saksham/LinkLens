# Security & privacy

## Rate limiting (link creation)

Implementation: `backend/src/main/java/com/linklens/ratelimit/`.

**Algorithm: token bucket, one per client IP.**
- A bucket holds up to `capacity` tokens (default 20) and starts full.
- Each `POST /api/v1/urls` takes one token. With none left, the response is `429` with `Retry-After` (seconds until the next token).
- Tokens refill continuously at `capacity / refillPeriod` (default 20 per minute). The refill is computed lazily from elapsed time on each request, so there is no background thread.
- The result: bursts up to 20, and a sustained rate of at most 20/min. Every response carries `X-RateLimit-Limit` and `X-RateLimit-Remaining`.

**Why a token bucket:**
- *Fixed window:* simplest, but allows 2× the limit across a window boundary.
- *Sliding log:* exact, but stores every timestamp.
- *Sliding window counter:* a good approximation, but harder to explain.
- *Token bucket:* exact enough, O(1) memory per client, naturally allows bursts, and the "next token in N seconds" gives an honest `Retry-After`.

**Concurrency trade-offs:**
- **Per-bucket `synchronized`.** Refill, check and decrement must be atomic, or two requests could both spend the last token. Locking per bucket means clients never contend with each other, only a client with itself. Tested: 64 threads racing for 10 tokens get exactly 10.
- **`ConcurrentHashMap.computeIfAbsent`** creates the bucket atomically, so a client's first two concurrent requests share one bucket.
- **Eviction race.** Full (idle) buckets are removed every minute to bound memory. If a request races with removal, the client may get one extra request. Accepted: the alternative is a global lock.
- **In-memory means per instance.** With N instances, the effective limit is N×. The fix is moving buckets to Redis with an atomic Lua script, at the cost of a network hop per create and a new failure mode (the limiter needs Redis). At one instance it isn't worth that.

**Scope:**
- Only creation is limited. Redirects are cheap, cached and must survive legitimate viral spikes. Their protection is the cache plus infrastructure (a CDN or WAF).
- The client key is `request.getRemoteAddr()`. `X-Forwarded-For` is *not* read directly, because any client could then choose its own key. Behind a trusted proxy, set `FORWARD_HEADERS_STRATEGY=native` (or `framework`) so Spring resolves the real client IP safely.

## Input validation

| Input | Rule | Where |
|---|---|---|
| `url` | absolute `http`/`https` with a host, ≤ 2048 chars | `@HttpUrl` Bean Validation |
| `url` | not pointing at LinkLens itself (prevents redirect loops) | service |
| `customAlias` | `[A-Za-z0-9_-]{3,32}`, not reserved (`api`, `actuator`, …) | `@Pattern` + service |
| `expiresAt` | in the future | `@Future` |
| `days` | 1–90 | `@Min/@Max` (method validation) |
| path `{code}` | `[A-Za-z0-9_-]{3,32}` or the route doesn't match | request mapping regex |

- **No `javascript:`, `data:` or `file:` targets.** The scheme allow-list makes the open-redirect surface strictly http(s).
- **SQL:** JPA/Spring Data parameters and `JdbcTemplate` placeholders only, never string-built SQL.
- **Frontend:** React escapes all rendered text, and no `dangerouslySetInnerHTML` is used. Referrer hosts and URLs from the API are rendered as text.

## Error handling

- RFC 7807 bodies with stable titles. Unexpected errors return a generic 500 message; stack traces, SQL and hostnames stay in server logs.
- Database outages return **503 + `Retry-After`** (they're temporary), not 500. The connection-pool timeout is 3 s, so requests fail fast instead of hanging for 30 s.

## Privacy (analytics)

Collected per click: link id, timestamp, referring host. Nothing else.
- **No IP address** (personal data under GDPR), so there's no geo-lookup.
- **No user agent stored.** It is read in memory only to skip obvious bots and link-preview fetchers, so pasting a link into Slack doesn't count as a click.
- **No full referrer URL.** Search queries and tokens often sit in the path or query string. Modern browsers mostly send only the origin cross-site anyway (`strict-origin-when-cross-origin`).
- **No cookies or fingerprinting.** Counts are clicks, not unique visitors. That's the honest metric when you deliberately don't identify people.

The UI states this next to the source breakdown.

## Operational hardening

- Graceful shutdown: in-flight requests complete and buffered clicks are flushed.
- Actuator exposes only `health` by default. Metrics are opt-in (`MANAGEMENT_ENDPOINTS`) and should be network-restricted if enabled in production.
- Secrets come from environment variables or a git-ignored `backend/.env`, never from the repo.

## Not in scope yet

There is no authentication, so anyone with a code can see its analytics, the way stats pages are typically public on bit.ly-style services. When accounts are added: per-owner analytics authorization, an abuse-report flow, and checking target URLs against a malware/phishing list (e.g. Google Safe Browsing).
