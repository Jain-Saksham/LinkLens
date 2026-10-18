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

## Outbound requests: SSRF protection

Link-rot monitoring and the preview page make LinkLens **fetch URLs that anyone can submit**. That's the classic server-side request forgery setup. An attacker shortens `http://169.254.169.254/latest/meta-data/` (the cloud metadata service that hands out credentials), `http://localhost:6379` or `http://10.0.0.5/admin`, and waits for the server to fetch it.

Defences (`backend/src/main/java/com/linklens/inspection/`):

| Layer | What it does |
|---|---|
| **Address policy** (`AddressPolicy`) | Allows only globally routable unicast addresses. Blocks loopback, `0.0.0.0/8`, RFC 1918, link-local `169.254/16` + `fe80::/10` (metadata), CGNAT `100.64/10`, test/benchmark ranges, multicast/broadcast, IPv6 unique-local `fc00::/7` (AWS `fd00:ec2::254`), and IPv4 smuggled inside IPv6 (`::ffff:a.b.c.d`, NAT64 `64:ff9b::/96`). 38 address cases are unit-tested. |
| **Check at connect time** (`SafeDnsResolver`) | Plugged into Apache HttpClient's DNS resolver, so the client connects only to addresses that were just validated. This closes **DNS rebinding**: with check-then-fetch, an attacker's DNS answers "public" to the check and `127.0.0.1` to the fetch. |
| **Every hop** | Redirects are followed manually (at most 5, with loop detection), and each hop resolves through the same resolver. A public page that 302s to the metadata IP is refused before any connection (tested). IP-literal hosts are also checked up front. |
| **Schemes** | Only `http`/`https`. A redirect to `ftp:`, `file:` or `gopher:` is reported as `INVALID_REDIRECT`, not followed. |
| **Bounded work** | 3 s connect, 5 s read, a 10 s hard deadline per inspection, reads stop at `</title>` or 1 MiB (HTML only), no retries, no cookies, no credentials, no system proxy. |
| **Bounded volume** | Scheduled checks are batched (20 per poll, 8 concurrent). Manual checks are rate-limited (20/min per IP) with a 60 s per-link cooldown. The preview reuses stored results for 6 h and deduplicates concurrent checks. So LinkLens can't be used to hammer a third-party site. |
| **Honest identity** | `User-Agent: LinkLensBot/1.0 (+link health check)`, so site owners can identify and block us. |

`app.health.allow-loopback=true` exists only so tests can fetch a local HTTP server. It allows `127.0.0.0/8` and nothing else, and must never be set in production.

**Production hardening beyond the app:** run the fetcher with no network route to internal services (egress-only network policy or an egress proxy). Application checks are defence in depth, not a substitute.

## Preview page: output encoding

Page titles and URLs come from third-party sites, so treat them as attacker-controlled HTML.
- **Every value is escaped** (`HtmlUtils.htmlEscape`) where it's inserted.
- **Single-pass templating.** Chained `String.replace` calls would expand a placeholder that arrives *inside* content: a site titled `{{action}}` could splice markup into the page. Tested.
- **CSP `default-src 'none'`** with no scripts at all, so even a missed escape could not execute. `frame-ancestors 'none'` blocks clickjacking, `nosniff` stops MIME sniffing, `no-referrer` keeps preview URLs out of other sites' logs.
- The short code is case-sensitive and shown verbatim (never upper-cased), so the page can't display a different link.

## Live streams (SSE): resource limits

Each open stream holds a connection, which makes it a cheap denial-of-service lever.
- **Caps:** 50 per link, 1,000 per instance; over the cap → 503 + `Retry-After`.
- **Bounded lifetime:** streams time out after 30 min, and heartbeats detect dead clients.
- **Isolation:** sends run on their own thread, so a stalled client can't block the click writer.
- **No sensitive data:** streams carry only a link's public click count.

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

- Malware/phishing reputation for destinations (e.g. Google Safe Browsing). The preview shows the real host, which already helps against look-alike domains.

There is no authentication, so anyone with a code can see its analytics, the way stats pages are typically public on bit.ly-style services. When accounts are added: per-owner analytics authorization, an abuse-report flow, and checking target URLs against a malware/phishing list (e.g. Google Safe Browsing).
