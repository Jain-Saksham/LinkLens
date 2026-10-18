package com.linklens.preview;

import static org.assertj.core.api.Assertions.assertThat;

import com.linklens.domain.ShortUrl;
import com.linklens.health.StoredHealth;
import com.linklens.health.StoredHealth.HealthState;
import com.linklens.inspection.InspectionResult.Hop;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PreviewPageTest {

    private static final Instant NOW = Instant.parse("2026-05-01T12:00:00Z");
    private static final ShortUrl LINK = new ShortUrl("docs", "https://spring.io/projects", true, NOW.minus(Duration.ofDays(3)), null);

    @Test
    void showsDestinationTitleSecurityAndRedirects() {
        StoredHealth health = healthy("Spring | Projects", "https://spring.io/projects/",
                List.of(new Hop("https://spring.io/projects", 301), new Hop("https://spring.io/projects/", 200)));

        String html = PreviewPage.render(LINK, "http://lnk.test/docs", health, NOW);

        assertThat(html).contains("<h1>spring.io</h1>", "Spring | Projects", "Encrypted (HTTPS)", "Working",
                "after 1 redirect", "Redirect chain (2 steps)", "href=\"/docs\"", "Continue to spring.io");
        assertThat(html).doesNotContain("http-equiv=\"refresh\"");
        // Inline icon (allowed by the CSP's img-src data:): browsers don't request /favicon.ico (a 404).
        assertThat(html).contains("<link rel=\"icon\" href=\"data:image/svg+xml,");
    }

    @Test
    void attackerControlledTitlesAndUrlsAreEscaped() {
        StoredHealth evil = healthy("<script>alert(1)</script><img src=x onerror=alert(2)>",
                "https://spring.io/\"><script>x</script>", List.of(new Hop("https://spring.io/\"><b>", 200)));

        String html = PreviewPage.render(LINK, "http://lnk.test/docs", evil, NOW);

        assertThat(html).doesNotContain("<script>", "<img src=x", "\"><b>");
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
    }

    @Test
    void placeholdersInsideContentAreNotExpanded() {
        // A title that looks like a template placeholder must come out verbatim, not as our markup.
        String html = PreviewPage.render(LINK, "http://lnk.test/docs", healthy("{{action}} {{rows}}", null, List.of()), NOW);
        assertThat(html).contains("{{action}} {{rows}}");
    }

    @Test
    void stillCheckingPageRefreshesItself() {
        String html = PreviewPage.render(LINK, "http://lnk.test/docs", null, NOW);
        assertThat(html).contains("<meta http-equiv=\"refresh\" content=\"2\">", "Checking…");
    }

    @Test
    void brokenLinksWarnButStillLetPeopleContinue() {
        StoredHealth broken = new StoredHealth(HealthState.BROKEN, "NOT_FOUND", 404, "https://spring.io/projects", null,
                "Spring", List.of(new Hop("https://spring.io/projects", 404)), 2, NOW, null, NOW.plusSeconds(900));

        String html = PreviewPage.render(LINK, "http://lnk.test/docs", broken, NOW);
        assertThat(html).contains("Your link points to a dead page", "Continue anyway", "Broken");
    }

    @Test
    void expiredLinksOfferNoWayThrough() {
        ShortUrl expired = new ShortUrl("old", "https://spring.io", true, NOW.minus(Duration.ofDays(9)), NOW.minus(Duration.ofDays(2)));
        String html = PreviewPage.render(expired, "http://lnk.test/old", null, NOW);
        assertThat(html).contains("This link has expired", "Expired 2 days ago").doesNotContain("Continue");
    }

    @Test
    void notFoundPageEscapesTheCode() {
        assertThat(PreviewPage.notFound("<b>x</b>")).contains("&lt;b&gt;x&lt;/b&gt;").doesNotContain("<b>x</b>");
    }

    @Test
    void fillIsSinglePass() {
        assertThat(PreviewPage.fill("{{a}}-{{b}}", Map.of("a", "{{b}}", "b", "B"))).isEqualTo("{{b}}-B");
    }

    private static StoredHealth healthy(String title, String finalUrl, List<Hop> chain) {
        return new StoredHealth(HealthState.HEALTHY, null, 200, finalUrl, title, title, chain, 0,
                NOW.minus(Duration.ofHours(1)), NOW.minus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(23)));
    }
}
