package com.linklens.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * @param totalClicks  all-time clicks (the counter on the link)
 * @param periodClicks clicks within the last {@code periodDays} UTC days, including today
 * @param daily        one entry per UTC day of the period, oldest first, zero-filled
 * @param referrers    top sources in the period; "Direct" is traffic without a usable referrer
 */
public record AnalyticsResponse(
        String shortCode,
        String shortUrl,
        String originalUrl,
        Instant createdAt,
        Instant expiresAt,
        long totalClicks,
        int periodDays,
        long periodClicks,
        List<DailyClicks> daily,
        List<ReferrerClicks> referrers,
        List<Insight> insights,
        Instant generatedAt) {

    public record DailyClicks(LocalDate date, long clicks) {
    }

    public record ReferrerClicks(String source, long clicks, double share) {
    }

    public record Insight(String type, Severity severity, String title, String message) {
    }

    public enum Severity {
        POSITIVE, NEUTRAL, WARNING
    }
}
