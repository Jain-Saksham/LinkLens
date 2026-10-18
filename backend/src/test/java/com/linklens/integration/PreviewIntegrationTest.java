package com.linklens.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.linklens.support.IntegrationTest;
import com.linklens.support.TestLinks;
import com.linklens.support.TestSite;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@IntegrationTest
class PreviewIntegrationTest {

    private final TestSite site = TestSite.get();

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void previewInspectsTheDestinationWithoutCountingAClick() {
        site.page("/guide", 200, "The Complete Guide <to> Things");
        String alias = TestLinks.uniqueAlias("pv");
        rest.postForEntity("/api/v1/urls", Map.of("url", site.url("/guide"), "customAlias", alias), String.class);

        ResponseEntity<String> page = rest.getForEntity("/" + alias + "+", String.class);

        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML)).isTrue();
        assertThat(page.getHeaders().getFirst("Content-Security-Policy")).contains("default-src 'none'");
        assertThat(page.getBody()).contains("127.0.0.1", "Working", "Not encrypted (HTTP)", "href=\"/" + alias + "\"");
        assertThat(page.getBody()).doesNotContain("<to>"); // escaped
        assertThat(jdbc.queryForObject("SELECT click_count FROM short_urls WHERE short_code = ?", Long.class, alias)).isZero();
        // The inspection was stored, so the next visitor doesn't trigger another fetch.
        assertThat(jdbc.queryForObject("SELECT state FROM link_health h JOIN short_urls s ON s.id = h.short_url_id "
                + "WHERE s.short_code = ?", String.class, alias)).isEqualTo("HEALTHY");
    }

    @Test
    void unknownAndExpiredLinks() {
        assertThat(rest.getForEntity("/no-such-link+", String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        String alias = TestLinks.uniqueAlias("pvexp");
        jdbc.update("INSERT INTO short_urls (short_code, original_url, expires_at) VALUES (?, ?, now() - interval '1 day')",
                alias, site.url("/guide"));
        ResponseEntity<String> expired = rest.getForEntity("/" + alias + "+", String.class);
        assertThat(expired.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(expired.getBody()).contains("This link has expired");
    }
}
