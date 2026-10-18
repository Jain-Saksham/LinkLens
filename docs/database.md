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

**What is not stored**, by design: IP address, user agent, cookies, and the full referrer URL (which can contain search terms or tokens).

## Indexes and the queries they serve

| Index | Query | Why |
|---|---|---|
| `uk_short_urls_short_code` (unique B-tree, created by the constraint) | `WHERE short_code = ?` on every uncached redirect, and every insert | Enforces uniqueness and serves the lookup; a second index would be redundant |
| `idx_click_events_link_time (short_url_id, clicked_at) INCLUDE (referrer_host)` | `WHERE short_url_id = ? AND clicked_at >= ?` for the daily trend and the referrer breakdown | Equality column first, range column second, so the scan touches only one link's recent rows. `INCLUDE` makes it covering, which enables index-only scans. It also serves the FK on delete. |

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
