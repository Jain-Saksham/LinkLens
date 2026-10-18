package com.linklens.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.linklens.dto.AnalyticsResponse;
import com.linklens.dto.AnalyticsResponse.ReferrerClicks;
import com.linklens.support.IntegrationTest;
import com.linklens.support.TestLinks;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class ClickAnalyticsIntegrationTest {

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void recordsClicksWithReferrerHostOnlyAndAggregatesThem() {
        String alias = TestLinks.uniqueAlias("stats");
        rest.postForEntity("/api/v1/urls", Map.of("url", "https://example.com", "customAlias", alias), String.class);

        click(alias, "https://www.google.com/search?q=private+search+terms", null);
        click(alias, "https://www.google.com/", null);
        click(alias, "https://news.ycombinator.com/item?id=123", null);
        click(alias, null, null);
        click(alias, "https://news.ycombinator.com/", "Slackbot-LinkExpanding 1.0 (+https://api.slack.com/robots)");

        Long linkId = jdbc.queryForObject("SELECT id FROM short_urls WHERE short_code = ?", Long.class, alias);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT click_count FROM short_urls WHERE id = ?", Long.class, linkId))
                        .isEqualTo(4L)); // the bot request redirected but wasn't counted

        // Privacy: only hosts are stored. No paths, queries, IPs or user agents.
        List<String> stored = jdbc.queryForList(
                "SELECT referrer_host FROM click_events WHERE short_url_id = ?", String.class, linkId);
        assertThat(stored).hasSize(4).containsOnly("google.com", "news.ycombinator.com", null);

        AnalyticsResponse analytics = rest.getForObject("/api/v1/urls/" + alias + "/analytics?days=7", AnalyticsResponse.class);
        assertThat(analytics.totalClicks()).isEqualTo(4);
        assertThat(analytics.periodClicks()).isEqualTo(4);
        assertThat(analytics.daily()).hasSize(7);
        assertThat(analytics.daily().getLast().clicks()).isEqualTo(4);
        assertThat(analytics.referrers()).containsExactly(
                new ReferrerClicks("google.com", 2, 0.5),
                new ReferrerClicks("news.ycombinator.com", 1, 0.25),
                new ReferrerClicks("Direct", 1, 0.25)); // ties: hosts A-Z, NULL (direct) sorts last
        assertThat(analytics.insights()).isNotEmpty();
    }

    @Test
    void rejectsInvalidPeriodsAndUnknownLinks() {
        String alias = TestLinks.uniqueAlias("period");
        rest.postForEntity("/api/v1/urls", Map.of("url", "https://example.com", "customAlias", alias), String.class);

        assertThat(rest.getForEntity("/api/v1/urls/" + alias + "/analytics?days=0", String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.getForEntity("/api/v1/urls/" + alias + "/analytics?days=91", String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.getForEntity("/api/v1/urls/" + alias + "/analytics?days=abc", String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.getForEntity("/api/v1/urls/nope-nope-nope/analytics", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    private void click(String alias, String referer, String userAgent) {
        HttpHeaders headers = new HttpHeaders();
        if (referer != null) {
            headers.set(HttpHeaders.REFERER, referer);
        }
        if (userAgent != null) {
            headers.set(HttpHeaders.USER_AGENT, userAgent);
        }
        var response = TestLinks.noRedirects(rest)
                .exchange("/" + alias, HttpMethod.GET, new HttpEntity<>(headers), Void.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
    }
}
