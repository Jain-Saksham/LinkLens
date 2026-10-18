CREATE TABLE short_urls (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    short_code    VARCHAR(32)   NOT NULL,
    original_url  VARCHAR(2048) NOT NULL,
    custom_alias  BOOLEAN       NOT NULL DEFAULT FALSE,
    click_count   BIGINT        NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ,

    -- The unique constraint also creates the B-tree index that serves the hot path
    -- (redirect lookup by short_code), so no separate index is needed for it.
    CONSTRAINT uk_short_urls_short_code UNIQUE (short_code),
    CONSTRAINT ck_short_urls_click_count CHECK (click_count >= 0)
);
