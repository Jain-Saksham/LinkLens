package com.linklens.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.linklens.dto.AnalyticsResponse.DailyClicks;
import com.linklens.dto.AnalyticsResponse.Insight;
import com.linklens.dto.AnalyticsResponse.ReferrerClicks;
import com.linklens.dto.AnalyticsResponse.Severity;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class InsightGeneratorTest {

    private static final Instant NOW = Instant.parse("2026-03-15T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 15);

    private final InsightGenerator generator = new InsightGenerator();

    @Test
    void noClicksAtAll() {
        assertThat(types(generate(series(15, 0, 0, 0), List.of(), null)))
                .containsExactly("NO_CLICKS");
    }

    @Test
    void weekOverWeekIncreaseUsesCompleteDaysOnly() {
        // previous week: 1/day (7), last complete week: 2/day (14), today: 0 (still in progress)
        List<Insight> insights = generate(series(15, 1, 2, 0), List.of(), null);

        assertThat(insights.getFirst().type()).isEqualTo("TRAFFIC_UP");
        assertThat(insights.getFirst().severity()).isEqualTo(Severity.POSITIVE);
        assertThat(insights.getFirst().title()).isEqualTo("Traffic is up 100%");
    }

    @Test
    void significantDropIsAWarning() {
        assertThat(types(generate(series(15, 2, 0, 1), List.of(), null)))
                .doesNotContain("INACTIVE") // today has a click
                .contains("TRAFFIC_DOWN");
    }

    @Test
    void smallBaselinesProduceNoTrendNoise() {
        // 1 click -> 3 clicks would be "+200%", but the baseline is below the minimum sample.
        List<DailyClicks> history = series(15, 0, 0, 0);
        history.set(2, new DailyClicks(history.get(2).date(), 1));
        history.set(10, new DailyClicks(history.get(10).date(), 3));

        assertThat(types(generate(history, List.of(), null))).containsExactly("COLLECTING");
    }

    @Test
    void trafficFromNothing() {
        assertThat(types(generate(series(15, 0, 1, 0), List.of(), null))).contains("NEW_TRAFFIC");
    }

    @Test
    void stoppedActivity() {
        List<DailyClicks> history = series(30, 0, 0, 0);
        history.set(0, new DailyClicks(history.getFirst().date(), 9)); // clicks a month ago, none since

        assertThat(types(generate(history, List.of(), null))).containsExactly("INACTIVE");
    }

    @Test
    void dominantReferrerAndMostlyDirect() {
        List<DailyClicks> history = series(15, 1, 1, 0);
        assertThat(types(generate(history, List.of(new ReferrerClicks("reddit.com", 10, 0.71)), null)))
                .contains("DOMINANT_SOURCE");
        assertThat(types(generate(history, List.of(new ReferrerClicks("Direct", 10, 0.71)), null)))
                .contains("MOSTLY_DIRECT");
        assertThat(types(generate(history, List.of(new ReferrerClicks("reddit.com", 5, 0.4)), null)))
                .doesNotContain("DOMINANT_SOURCE");
    }

    @Test
    void warnsWhenAnActiveLinkIsAboutToExpire() {
        List<DailyClicks> history = series(15, 1, 1, 0);
        assertThat(types(generate(history, List.of(), NOW.plus(Duration.ofHours(20))))).contains("EXPIRING_WHILE_ACTIVE");
        assertThat(types(generate(history, List.of(), NOW.plus(Duration.ofDays(10))))).doesNotContain("EXPIRING_WHILE_ACTIVE");
    }

    @Test
    void reportsThePeakDayAndCapsTheNumberOfInsights() {
        List<DailyClicks> history = series(15, 1, 3, 0);
        history.set(11, new DailyClicks(history.get(11).date(), 40)); // index 11 = Mar 12

        List<Insight> insights = generate(history, List.of(new ReferrerClicks("x.com", 50, 0.9)), NOW.plusSeconds(3600));
        assertThat(insights).hasSizeLessThanOrEqualTo(InsightGenerator.MAX_INSIGHTS);
        assertThat(types(insights)).contains("PEAK_DAY");
        assertThat(insights.stream().filter(i -> i.type().equals("PEAK_DAY")).findFirst().orElseThrow().title())
                .isEqualTo("Busiest day: Thu, Mar 12");
    }

    private List<Insight> generate(List<DailyClicks> history, List<ReferrerClicks> referrers, Instant expiresAt) {
        long total = history.stream().mapToLong(DailyClicks::clicks).sum();
        List<DailyClicks> period = history.subList(Math.max(0, history.size() - 7), history.size());
        long periodClicks = period.stream().mapToLong(DailyClicks::clicks).sum();
        long referred = referrers.stream().mapToLong(ReferrerClicks::clicks).sum();
        return generator.generate(history, period, referrers, Math.max(periodClicks, referred), total, expiresAt, NOW);
    }

    /** {@code days} ending today: the older days get {@code older}/day, the last complete 7 get {@code recent}/day. */
    private static List<DailyClicks> series(int days, long older, long recent, long today) {
        List<DailyClicks> list = new ArrayList<>();
        for (int i = days - 1; i >= 0; i--) {
            long clicks = i == 0 ? today : i <= 7 ? recent : older;
            list.add(new DailyClicks(TODAY.minusDays(i), clicks));
        }
        return list;
    }

    private static List<String> types(List<Insight> insights) {
        return insights.stream().map(Insight::type).toList();
    }
}
