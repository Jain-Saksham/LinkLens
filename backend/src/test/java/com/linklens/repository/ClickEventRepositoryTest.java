package com.linklens.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.linklens.domain.ClickEvent;
import com.linklens.domain.ShortUrl;
import com.linklens.support.PostgresTestConfig;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/** The native aggregation SQL, the UTC day boundaries and the schema constraints, on real PostgreSQL. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class ClickEventRepositoryTest {

    private static final Instant JAN_1 = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private ShortUrlRepository shortUrls;
    @Autowired
    private ClickEventRepository events;
    @Autowired
    private JdbcTemplate jdbc;

    private Long linkId;

    @BeforeEach
    void setUp() {
        linkId = shortUrls.saveAndFlush(new ShortUrl("repo-" + System.nanoTime(), "https://example.com", true,
                JAN_1.minusSeconds(86_400 * 60), null)).getId();
    }

    @Test
    void countsPerUtcDayRespectingMidnightAndTheLowerBound() {
        click("2025-12-01T12:00:00Z", null);           // before the window
        click("2026-01-01T23:59:59Z", "a.com");        // last second of Jan 1
        click("2026-01-02T00:00:00Z", "a.com");        // first second of Jan 2
        click("2026-01-02T18:30:00Z", null);

        assertThat(events.countDaily(linkId, JAN_1))
                .extracting(ClickEventRepository.DailyCount::getDay, ClickEventRepository.DailyCount::getClicks)
                .containsExactly(tuple("2026-01-01", 1L), tuple("2026-01-02", 2L));
    }

    @Test
    void groupsReferrersWithDirectTrafficAsNullOrderedByVolume() {
        click("2026-01-05T10:00:00Z", "b.com");
        click("2026-01-05T11:00:00Z", "a.com");
        click("2026-01-05T12:00:00Z", "a.com");
        click("2026-01-05T13:00:00Z", null);
        click("2026-01-05T14:00:00Z", null);
        click("2026-01-05T15:00:00Z", null);

        assertThat(events.topReferrers(linkId, JAN_1, 2))
                .extracting(ClickEventRepository.ReferrerCount::getReferrerHost, ClickEventRepository.ReferrerCount::getClicks)
                .containsExactly(tuple(null, 3L), tuple("a.com", 2L));
    }

    @Test
    void eventsOfOtherLinksAreNotCounted() {
        Long other = shortUrls.saveAndFlush(new ShortUrl("other-" + System.nanoTime(), "https://x.com", true, JAN_1, null)).getId();
        events.saveAndFlush(new ClickEvent(other, Instant.parse("2026-01-03T00:00:00Z"), null));

        assertThat(events.countDaily(linkId, JAN_1)).isEmpty();
    }

    @Test
    void deletingALinkCascadesToItsEvents() {
        click("2026-01-03T00:00:00Z", null);
        jdbc.update("DELETE FROM short_urls WHERE id = ?", linkId);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM click_events WHERE short_url_id = ?", Long.class, linkId))
                .isZero();
    }

    @Test
    void analyticsIndexExists() {
        assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'click_events'", String.class))
                .contains("idx_click_events_link_time");
    }

    private void click(String at, String referrerHost) {
        events.saveAndFlush(new ClickEvent(linkId, Instant.parse(at), referrerHost));
    }
}
