package com.linklens.service;

import com.linklens.dto.AnalyticsResponse.DailyClicks;
import com.linklens.dto.AnalyticsResponse.Insight;
import com.linklens.dto.AnalyticsResponse.ReferrerClicks;
import com.linklens.dto.AnalyticsResponse.Severity;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Plain, explainable rules over aggregated counts. Two principles keep the output trustworthy:
 * <ol>
 *   <li><b>Minimum sample sizes</b>: "+100%" from 1 to 2 clicks is noise, so trend rules need at
 *       least {@link #MIN_TREND_SAMPLE} clicks in the baseline week.</li>
 *   <li><b>Complete days only</b> for week-over-week: comparing a half-finished today against a full
 *       day would make every morning look like a decline.</li>
 * </ol>
 */
@Component
public class InsightGenerator {

    static final int MIN_TREND_SAMPLE = 5;
    static final double SIGNIFICANT_CHANGE = 0.5;
    static final int MIN_SOURCE_SAMPLE = 10;
    static final double DOMINANT_SHARE = 0.6;
    static final int MIN_PEAK_CLICKS = 5;
    static final Duration EXPIRY_WARNING = Duration.ofDays(3);
    static final int MAX_INSIGHTS = 4;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH);

    /**
     * @param history   daily counts, oldest first, ending with today; at least 15 days long
     * @param period    the trailing slice of {@code history} the user is looking at
     * @param referrers top sources within the period
     */
    public List<Insight> generate(List<DailyClicks> history, List<DailyClicks> period,
                                  List<ReferrerClicks> referrers, long periodClicks, long totalClicks,
                                  Instant expiresAt, Instant now) {
        if (totalClicks == 0) {
            return List.of(new Insight("NO_CLICKS", Severity.NEUTRAL, "No clicks yet",
                    "Share your link to start collecting data."));
        }

        List<Insight> insights = new ArrayList<>();
        // Last 7 complete days vs the 7 days before them (today is excluded as it's still in progress).
        int today = history.size() - 1;
        long lastWeek = sum(history, today - 7, today);
        long previousWeek = sum(history, today - 14, today - 7);

        if (lastWeek == 0 && history.get(today).clicks() == 0) {
            insights.add(new Insight("INACTIVE", Severity.WARNING, "Activity has stopped",
                    "No clicks in the last 7 days."));
        } else if (previousWeek >= MIN_TREND_SAMPLE) {
            double change = (double) (lastWeek - previousWeek) / previousWeek;
            if (change >= SIGNIFICANT_CHANGE) {
                insights.add(new Insight("TRAFFIC_UP", Severity.POSITIVE,
                        "Traffic is up " + percent(change),
                        lastWeek + " clicks in the last 7 days, vs " + previousWeek + " the week before."));
            } else if (change <= -SIGNIFICANT_CHANGE) {
                insights.add(new Insight("TRAFFIC_DOWN", Severity.WARNING,
                        "Activity is declining (" + percent(change) + ")",
                        lastWeek + " clicks in the last 7 days, vs " + previousWeek + " the week before."));
            }
        } else if (previousWeek == 0 && lastWeek >= MIN_TREND_SAMPLE) {
            insights.add(new Insight("NEW_TRAFFIC", Severity.POSITIVE, "Picking up traffic",
                    lastWeek + " clicks in the last 7 days, up from none the week before."));
        }

        if (expiresAt != null && now.isBefore(expiresAt)
                && Duration.between(now, expiresAt).compareTo(EXPIRY_WARNING) <= 0 && lastWeek + history.get(today).clicks() > 0) {
            insights.add(new Insight("EXPIRING_WHILE_ACTIVE", Severity.WARNING, "Expires soon",
                    "This link is still getting clicks but stops working in " + humanize(Duration.between(now, expiresAt)) + "."));
        }

        if (periodClicks >= MIN_SOURCE_SAMPLE && !referrers.isEmpty()) {
            ReferrerClicks top = referrers.getFirst();
            if (top.share() >= DOMINANT_SHARE) {
                insights.add("Direct".equals(top.source())
                        ? new Insight("MOSTLY_DIRECT", Severity.NEUTRAL, "Mostly direct traffic",
                                percent(top.share()) + " of clicks had no referrer: shared in apps, email or chat.")
                        : new Insight("DOMINANT_SOURCE", Severity.NEUTRAL, "Most clicks come from " + top.source(),
                                percent(top.share()) + " of clicks in this period."));
            }
        }

        period.stream()
                .max(Comparator.comparingLong(DailyClicks::clicks))
                .filter(peak -> peak.clicks() >= MIN_PEAK_CLICKS)
                .ifPresent(peak -> insights.add(new Insight("PEAK_DAY", Severity.NEUTRAL,
                        "Busiest day: " + DAY.format(peak.date()),
                        peak.clicks() + " clicks (UTC).")));

        if (insights.isEmpty()) {
            // Say so explicitly rather than showing nothing: thresholds above suppress noisy claims.
            insights.add(new Insight("COLLECTING", Severity.NEUTRAL, "Collecting data",
                    "Trends appear once there is about a week of clicks to compare."));
        }
        return insights.stream().limit(MAX_INSIGHTS).toList();
    }

    /** Sum of history[fromInclusive, toExclusive), clamped to the list bounds. */
    private static long sum(List<DailyClicks> history, int fromInclusive, int toExclusive) {
        long total = 0;
        for (int i = Math.max(0, fromInclusive); i < Math.min(history.size(), toExclusive); i++) {
            total += history.get(i).clicks();
        }
        return total;
    }

    private static String percent(double ratio) {
        return Math.round(Math.abs(ratio) * 100) + "%";
    }

    private static String humanize(Duration d) {
        if (d.toDays() >= 1) {
            return d.toDays() + (d.toDays() == 1 ? " day" : " days");
        }
        if (d.toHours() >= 1) {
            return d.toHours() + (d.toHours() == 1 ? " hour" : " hours");
        }
        return Math.max(1, d.toMinutes()) + " minutes";
    }
}
