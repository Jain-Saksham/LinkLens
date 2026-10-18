package com.linklens.dto;

import com.linklens.health.HealthMessages;
import com.linklens.health.StoredHealth;
import com.linklens.inspection.InspectionResult.Hop;
import java.time.Instant;
import java.util.List;

/**
 * @param state   PENDING | HEALTHY | WARNING | BROKEN
 * @param issue   machine-readable cause (e.g. NOT_FOUND, TIMEOUT, TITLE_CHANGED), null when healthy
 * @param message plain-language explanation for people
 */
public record LinkHealthResponse(
        String state,
        String issue,
        String message,
        Integer httpStatus,
        String finalUrl,
        String title,
        boolean https,
        List<Hop> redirectChain,
        int consecutiveFailures,
        Instant checkedAt,
        Instant lastHealthyAt,
        Instant nextCheckAt) {

    public static LinkHealthResponse from(StoredHealth h) {
        return new LinkHealthResponse(h.state().name(), h.issue(), HealthMessages.describe(h), h.httpStatus(),
                h.finalUrl(), h.title(), h.finalIsHttps(), h.redirectChain(), h.consecutiveFailures(),
                h.checkedAt(), h.lastHealthyAt(), h.nextCheckAt());
    }
}
