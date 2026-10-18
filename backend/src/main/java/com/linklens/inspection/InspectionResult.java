package com.linklens.inspection;

import java.time.Instant;
import java.util.List;

/**
 * What one visit to a destination found.
 *
 * @param redirectChain every hop in order; the last one is the final destination
 * @param title         page title of the final HTML page, if any
 */
public record InspectionResult(
        Outcome outcome,
        Integer httpStatus,
        List<Hop> redirectChain,
        String title,
        Instant checkedAt,
        long durationMs,
        String detail) {

    public record Hop(String url, Integer status) {
    }

    public enum Outcome {
        OK(false),
        /** 404 / 410: the page is gone. */
        NOT_FOUND(true),
        /** Other 4xx, except the "can't verify" ones below. */
        CLIENT_ERROR(true),
        /** 401 / 403 / 429: bot protection or login wall, so the page may be fine for humans. */
        ACCESS_RESTRICTED(false),
        SERVER_ERROR(true),
        REDIRECT_LOOP(true),
        TOO_MANY_REDIRECTS(true),
        INVALID_REDIRECT(true),
        /** The domain no longer resolves: a strong sign of an abandoned site. */
        DNS_FAILURE(true),
        TIMEOUT(true),
        TLS_ERROR(true),
        UNREACHABLE(true),
        /** The destination (or a hop) points into a private network: refused, never fetched. */
        BLOCKED(false);

        private final boolean failure;

        Outcome(boolean failure) {
            this.failure = failure;
        }

        /** Counts toward "broken". ACCESS_RESTRICTED and BLOCKED are "unverifiable", not failures. */
        public boolean isFailure() {
            return failure;
        }
    }

    public String finalUrl() {
        return redirectChain.isEmpty() ? null : redirectChain.getLast().url();
    }

    public boolean finalIsHttps() {
        String url = finalUrl();
        return url != null && url.regionMatches(true, 0, "https:", 0, 6);
    }
}
