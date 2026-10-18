package com.linklens.health;

import com.linklens.inspection.InspectionResult.Hop;
import java.time.Instant;
import java.util.List;

/** One row of {@code link_health}. */
public record StoredHealth(
        HealthState state,
        String issue,
        Integer httpStatus,
        String finalUrl,
        String title,
        String baselineTitle,
        List<Hop> redirectChain,
        int consecutiveFailures,
        Instant checkedAt,
        Instant lastHealthyAt,
        Instant nextCheckAt) {

    public enum HealthState {
        /** Not checked yet. */
        PENDING,
        HEALTHY,
        /** One failure so far, an unverifiable destination, or the page title changed drastically. */
        WARNING,
        /** Failed {@code failureThreshold} checks in a row: "your link points to a dead page". */
        BROKEN
    }

    public static StoredHealth pending(Instant nextCheckAt) {
        return new StoredHealth(HealthState.PENDING, null, null, null, null, null, List.of(), 0, null, null, nextCheckAt);
    }

    public boolean finalIsHttps() {
        return finalUrl != null && finalUrl.regionMatches(true, 0, "https:", 0, 6);
    }
}
