package com.linklens.service;

import com.linklens.config.AppProperties;
import com.linklens.domain.ShortUrl;
import com.linklens.dto.AnalyticsResponse;
import com.linklens.dto.AnalyticsResponse.DailyClicks;
import com.linklens.dto.AnalyticsResponse.ReferrerClicks;
import com.linklens.exception.ShortUrlNotFoundException;
import com.linklens.repository.ClickEventRepository;
import com.linklens.repository.ShortUrlRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalyticsService {

    public static final int MAX_PERIOD_DAYS = 90;
    /** Week-over-week insights need two complete weeks plus today, whatever period is displayed. */
    static final int MIN_HISTORY_DAYS = 15;
    static final int TOP_REFERRERS = 8;
    static final String DIRECT = "Direct";
    static final String OTHER = "Other";

    private final ShortUrlRepository shortUrls;
    private final ClickEventRepository clickEvents;
    private final InsightGenerator insightGenerator;
    private final AppProperties properties;
    private final Clock clock;

    public AnalyticsService(ShortUrlRepository shortUrls, ClickEventRepository clickEvents,
                            InsightGenerator insightGenerator, AppProperties properties, Clock clock) {
        this.shortUrls = shortUrls;
        this.clickEvents = clickEvents;
        this.insightGenerator = insightGenerator;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AnalyticsResponse analyticsFor(String shortCode, int periodDays) {
        ShortUrl link = shortUrls.findByShortCode(shortCode)
                .orElseThrow(() -> new ShortUrlNotFoundException(shortCode));
        Instant now = Instant.now(clock);
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);

        int historyDays = Math.max(periodDays, MIN_HISTORY_DAYS);
        List<DailyClicks> history = zeroFilledDaily(link.getId(), today, historyDays);
        List<DailyClicks> period = history.subList(historyDays - periodDays, historyDays);
        long periodClicks = period.stream().mapToLong(DailyClicks::clicks).sum();

        Instant periodStart = today.minusDays(periodDays - 1L).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<ReferrerClicks> referrers = referrers(link.getId(), periodStart, periodClicks);

        return new AnalyticsResponse(
                link.getShortCode(),
                properties.normalizedBaseUrl() + "/" + link.getShortCode(),
                link.getOriginalUrl(),
                link.getCreatedAt(),
                link.getExpiresAt(),
                link.getClickCount(),
                periodDays,
                periodClicks,
                List.copyOf(period),
                referrers,
                insightGenerator.generate(history, period, referrers, periodClicks, link.getClickCount(),
                        link.getExpiresAt(), now),
                now);
    }

    /** The database only returns days that had clicks; the chart needs every day, so fill the gaps. */
    private List<DailyClicks> zeroFilledDaily(Long shortUrlId, LocalDate today, int days) {
        LocalDate first = today.minusDays(days - 1L);
        Map<LocalDate, Long> counts = new HashMap<>();
        clickEvents.countDaily(shortUrlId, first.atStartOfDay(ZoneOffset.UTC).toInstant())
                .forEach(row -> counts.put(LocalDate.parse(row.getDay()), row.getClicks()));

        List<DailyClicks> series = new ArrayList<>(days);
        for (LocalDate day = first; !day.isAfter(today); day = day.plusDays(1)) {
            series.add(new DailyClicks(day, counts.getOrDefault(day, 0L)));
        }
        return series;
    }

    private List<ReferrerClicks> referrers(Long shortUrlId, Instant from, long periodClicks) {
        if (periodClicks == 0) {
            return List.of();
        }
        List<ReferrerClicks> result = new ArrayList<>();
        long listed = 0;
        for (var row : clickEvents.topReferrers(shortUrlId, from, TOP_REFERRERS)) {
            String source = row.getReferrerHost() == null ? DIRECT : row.getReferrerHost();
            result.add(new ReferrerClicks(source, row.getClicks(), share(row.getClicks(), periodClicks)));
            listed += row.getClicks();
        }
        if (periodClicks > listed) {
            result.add(new ReferrerClicks(OTHER, periodClicks - listed, share(periodClicks - listed, periodClicks)));
        }
        return result;
    }

    private static double share(long part, long whole) {
        return Math.round(part * 1000.0 / whole) / 1000.0;
    }
}
