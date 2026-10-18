# Database & indexing

PostgreSQL. The schema is owned by Flyway (`backend/src/main/resources/db/migration`), and Hibernate runs with `ddl-auto=validate`.

## Schema

```sql
short_urls                                   -- V1
  id            bigint identity  PK
  short_code    varchar(32)      NOT NULL  UNIQUE (uk_short_urls_short_code)
  original_url  varchar(2048)    NOT NULL
  custom_alias  boolean          NOT NULL
  click_count   bigint           NOT NULL  CHECK (click_count >= 0)
  created_at    timestamptz      NOT NULL
  expires_at    timestamptz      NULL

click_events                                 -- V2
  id            bigint identity  PK
  short_url_id  bigint           NOT NULL  FK → short_urls(id) ON DELETE CASCADE
  clicked_at    timestamptz      NOT NULL
  referrer_host varchar(255)     NULL       -- NULL = direct/unknown
```

```sql
link_health                                  -- V3, one-to-one with short_urls
  short_url_id          bigint      PK, FK → short_urls(id) ON DELETE CASCADE
  state                 varchar(16) NOT NULL CHECK IN (PENDING, HEALTHY, WARNING, BROKEN)
  issue                 varchar(32)            -- NOT_FOUND, TIMEOUT, TITLE_CHANGED, ...
  http_status           integer
  final_url             varchar(2048)
  title, baseline_title varchar(300)           -- current title, and the first one seen
  redirect_chain        jsonb                  -- [{"url": ..., "status": 301}, ...]
  consecutive_failures  integer     NOT NULL   CHECK >= 0
  checked_at, last_healthy_at  timestamptz
  next_check_at         timestamptz NOT NULL   -- 'infinity' once the link expires
```

`link_health` is separate from `short_urls` on purpose. `short_urls` is the redirect hot path and the cache source, while health rows are rewritten by a background job on a different schedule. Keeping them apart means health writes never touch, lock or bloat the rows redirects read.

**What is not stored**, by design: IP address, user agent, cookies, and the full referrer URL (which can contain search terms or tokens).

## Indexes and the queries they serve

| Index | Query | Why |
|---|---|---|
| `uk_short_urls_short_code` (unique B-tree, created by the constraint) | `WHERE short_code = ?` on every uncached redirect, and every insert | Enforces uniqueness and serves the lookup; a second index would be redundant |
| `idx_click_events_link_time (short_url_id, clicked_at) INCLUDE (referrer_host)` | `WHERE short_url_id = ? AND clicked_at >= ?` for the daily trend and the referrer breakdown | Equality column first, range column second, so the scan touches only one link's recent rows. `INCLUDE` makes it covering, which enables index-only scans. It also serves the FK on delete. |
| `idx_link_health_next_check (next_check_at)` | the scheduler's `WHERE next_check_at <= now() ORDER BY next_check_at LIMIT n` | Due links are found with an index range scan, oldest first |

### Claiming work safely: `FOR UPDATE SKIP LOCKED`

```sql
UPDATE link_health h SET next_check_at = now() + interval '10 minutes'   -- the lease
FROM short_urls s
WHERE s.id = h.short_url_id AND h.short_url_id IN (
    SELECT short_url_id FROM link_health WHERE next_check_at <= now()
    ORDER BY next_check_at LIMIT 20 FOR UPDATE SKIP LOCKED)
RETURNING h.*, s.original_url, s.expires_at;
```

- **Multi-instance safe:** two schedulers polling at the same moment get *disjoint* batches. Locked rows are skipped, not waited on (integration-tested with concurrent claims).
- **Network calls outside the transaction:** the claim commits immediately, the slow HTTP checks run afterwards, and each result is written in its own short `UPDATE`. A transaction never stays open across network I/O.
- **Crash recovery:** a claim pushes `next_check_at` 10 minutes out, which works as a lease. If the instance dies mid-check, the link simply becomes due again.

### Measured plans

On a local table of **789,240 events across 235 links** (after load tests, `VACUUM ANALYZE`):

```
Index Only Scan using idx_click_events_link_time on click_events
  Index Cond: ((short_url_id = $0) AND (clicked_at >= (now() - '30 days')))
  Heap Fetches: 0      Buffers: shared hit=10      → referrer query: 0.28 ms total
                                                   → daily query:    2.26 ms total (4 pages read from disk)
Index Scan using uk_short_urls_short_code on short_urls   → 0.005 ms
```

`Heap Fetches: 0` confirms the covering index works: the table itself is never read.

### Trade-offs

- **Write cost and size:** the covering index is **41 MB vs 39 MB** for the table. Every click updates it. That's acceptable because writes are batched (1,000 rows per transaction), and analytics reads stay flat as the table grows.
- **No index on `clicked_at` alone.** No current query filters by time across all links. A future retention job (`DELETE … WHERE clicked_at < ?`) would need one, or better, monthly partitions that can be dropped.
- **No index on `expires_at`.** Expiry is checked on read and nothing scans by it yet.
- **No index on `original_url`.** Shortening the same URL twice intentionally creates two links, each with its own expiry and stats.

## Write patterns

| Write | SQL | Concurrency |
|---|---|---|
| Create link | single `INSERT`, no pre-check | The unique constraint decides races. The service checks the **constraint name**, so only a short-code clash becomes 409 or a retry; anything else stays an error. |
| Record clicks | `INSERT … VALUES (…),(…)…` (pgjdbc `reWriteBatchedInserts=true`) + `UPDATE short_urls SET click_count = click_count + ? WHERE id = ?` per link, in one transaction | Atomic in-database increments (no lost updates). Ids are sorted so concurrent writers lock rows in the same order. |

`click_count` is a denormalized counter, kept so that link details and totals don't need `COUNT(*)`. It is written in the same transaction as the events, so the two cannot disagree. Load tests confirmed it: 273,000 events = counter 273,000.

## Time zones

Everything is stored as `timestamptz` and handled as `Instant` in Java. Analytics buckets by **UTC** day (`clicked_at AT TIME ZONE 'UTC'`), the same for every viewer. Per-user time zones would be a `tz` query parameter fed into the same expression.

## Scaling path, in order of need

1. **Retention:** roll old events into a `daily_clicks(short_url_id, day, referrer_host, clicks)` table, or partition `click_events` by month and drop old partitions.
2. **Read replicas** for analytics, so aggregate queries never compete with redirects or creates.
3. Only at far larger scale: a columnar store for events. Not needed here.
