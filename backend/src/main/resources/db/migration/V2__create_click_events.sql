-- One row per (non-bot) redirect. Deliberately minimal: no IP address, no user agent and no full
-- referrer URL (which can carry search terms or tokens), only the referring host.
CREATE TABLE click_events (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    short_url_id   BIGINT       NOT NULL REFERENCES short_urls (id) ON DELETE CASCADE,
    clicked_at     TIMESTAMPTZ  NOT NULL,
    referrer_host  VARCHAR(255)
);

-- Every analytics query has the shape "events of ONE link in a time range":
--   WHERE short_url_id = ? AND clicked_at >= ?
-- Equality column first, range column second, so the scan touches only that link's recent rows.
-- INCLUDE (referrer_host) makes the index covering, so both the daily-trend and the referrer
-- aggregation can run as index-only scans. The index also serves the foreign key on delete.
CREATE INDEX idx_click_events_link_time
    ON click_events (short_url_id, clicked_at) INCLUDE (referrer_host);
