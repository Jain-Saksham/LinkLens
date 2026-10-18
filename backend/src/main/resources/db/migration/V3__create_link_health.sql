-- Destination health per link (one-to-one). Kept out of short_urls: that table is the redirect hot
-- path and cache source, while health rows are rewritten by a background job on their own schedule.
CREATE TABLE link_health (
    short_url_id          BIGINT        PRIMARY KEY REFERENCES short_urls (id) ON DELETE CASCADE,
    state                 VARCHAR(16)   NOT NULL DEFAULT 'PENDING',  -- PENDING | HEALTHY | WARNING | BROKEN
    issue                 VARCHAR(32),                               -- e.g. NOT_FOUND, TIMEOUT, TITLE_CHANGED
    http_status           INTEGER,
    final_url             VARCHAR(2048),
    title                 VARCHAR(300),
    baseline_title        VARCHAR(300),                              -- first title seen, for drift detection
    redirect_chain        JSONB,                                     -- [{"url": "...", "status": 301}, ...]
    consecutive_failures  INTEGER       NOT NULL DEFAULT 0,
    checked_at            TIMESTAMPTZ,
    last_healthy_at       TIMESTAMPTZ,
    next_check_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT ck_link_health_state CHECK (state IN ('PENDING', 'HEALTHY', 'WARNING', 'BROKEN')),
    CONSTRAINT ck_link_health_failures CHECK (consecutive_failures >= 0)
);

-- The scheduler's only query: "which links are due?" -> WHERE next_check_at <= now() ORDER BY next_check_at.
CREATE INDEX idx_link_health_next_check ON link_health (next_check_at);

-- Existing links get checked on the scheduler's first passes.
INSERT INTO link_health (short_url_id) SELECT id FROM short_urls;
