package com.linklens.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.linklens.config.AppProperties;
import com.linklens.domain.ShortUrl;
import com.linklens.dto.AnalyticsResponse;
import com.linklens.dto.AnalyticsResponse.ReferrerClicks;
import com.linklens.exception.ShortUrlNotFoundException;
import com.linklens.repository.ClickEventRepository;
import com.linklens.repository.ShortUrlRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-15T09:00:00Z");

    @Mock
    private ShortUrlRepository shortUrls;
    @Mock
    private ClickEventRepository clickEvents;

    private AnalyticsService service;

    @BeforeEach
    void setUp() {
        service = new AnalyticsService(shortUrls, clickEvents, new InsightGenerator(),
                new AppProperties("http://lnk.test", 7, List.of("x")), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void zeroFillsDaysWithoutClicksAndSlicesThePeriod() {
        stubLink(9);
        when(clickEvents.countDaily(eq(1L), any())).thenReturn(List.of(day("2026-03-13", 4), day("2026-03-15", 5)));
        when(clickEvents.topReferrers(eq(1L), any(), anyInt())).thenReturn(List.of());

        AnalyticsResponse response = service.analyticsFor("abc", 3);

        assertThat(response.daily()).extracting(d -> d.date().toString() + "=" + d.clicks())
                .containsExactly("2026-03-13=4", "2026-03-14=0", "2026-03-15=5");
        assertThat(response.periodClicks()).isEqualTo(9);
        assertThat(response.totalClicks()).isEqualTo(9);
        assertThat(response.shortUrl()).isEqualTo("http://lnk.test/abc");
        // History for insights always covers at least 15 days, starting at UTC midnight.
        verify(clickEvents).countDaily(1L, LocalDate.of(2026, 3, 1).atStartOfDay(ZoneOffset.UTC).toInstant());
        // Referrers are queried for the displayed period only.
        verify(clickEvents).topReferrers(1L, Instant.parse("2026-03-13T00:00:00Z"), AnalyticsService.TOP_REFERRERS);
    }

    @Test
    void labelsDirectTrafficAndGroupsTheTailAsOther() {
        stubLink(10);
        when(clickEvents.countDaily(eq(1L), any())).thenReturn(List.of(day("2026-03-15", 10)));
        when(clickEvents.topReferrers(eq(1L), any(), anyInt()))
                .thenReturn(List.of(referrer("a.com", 6), referrer(null, 3)));

        assertThat(service.analyticsFor("abc", 7).referrers()).containsExactly(
                new ReferrerClicks("a.com", 6, 0.6),
                new ReferrerClicks("Direct", 3, 0.3),
                new ReferrerClicks("Other", 1, 0.1));
    }

    @Test
    void unknownLinkIs404() {
        when(shortUrls.findByShortCode("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.analyticsFor("nope", 7)).isInstanceOf(ShortUrlNotFoundException.class);
    }

    private void stubLink(long clicks) {
        ShortUrl link = new ShortUrl("abc", "https://example.com", true, NOW.minusSeconds(86_400 * 30), null);
        ReflectionTestUtils.setField(link, "id", 1L);
        ReflectionTestUtils.setField(link, "clickCount", clicks);
        when(shortUrls.findByShortCode("abc")).thenReturn(Optional.of(link));
    }

    private static ClickEventRepository.DailyCount day(String day, long clicks) {
        return new ClickEventRepository.DailyCount() {
            public String getDay() {
                return day;
            }

            public long getClicks() {
                return clicks;
            }
        };
    }

    private static ClickEventRepository.ReferrerCount referrer(String host, long clicks) {
        return new ClickEventRepository.ReferrerCount() {
            public String getReferrerHost() {
                return host;
            }

            public long getClicks() {
                return clicks;
            }
        };
    }
}
