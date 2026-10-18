package com.linklens.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.linklens.dto.LinkHealthResponse;
import com.linklens.dto.ShortUrlResponse;
import com.linklens.health.LinkHealthRepository;
import com.linklens.health.LinkHealthService;
import com.linklens.inspection.InspectionResult.Hop;
import com.linklens.support.IntegrationTest;
import com.linklens.support.TestLinks;
import com.linklens.support.TestSite;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/** The scheduler is disabled in tests; each test drives checkDueBatch() itself for determinism. */
@IntegrationTest
class LinkHealthIntegrationTest {

    private final TestSite site = TestSite.get();

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private LinkHealthService health;
    @Autowired
    private LinkHealthRepository healthRepository;

    @BeforeEach
    void onlyThisTestsLinksAreDue() {
        // The database is shared with other test classes; keep their links out of these batches.
        jdbc.update("UPDATE link_health SET next_check_at = now() + interval '1 day'");
    }

    @Test
    void healthyLinkThatStartsFailingWarnsOnceThenIsFlaggedBroken() {
        site.page("/article", 200, "Release notes 2.0");
        String code = create(site.url("/article"));
        assertThat(get(code).state()).isEqualTo("PENDING");

        checkNow(code);
        LinkHealthResponse healthy = get(code);
        assertThat(healthy.state()).isEqualTo("HEALTHY");
        assertThat(healthy.title()).isEqualTo("Release notes 2.0");
        assertThat(healthy.nextCheckAt()).isAfter(Instant.now().plus(Duration.ofHours(20)));

        site.page("/article", 404, null);
        checkNow(code);
        assertThat(get(code).state()).isEqualTo("WARNING");
        assertThat(details(code).healthState()).isEqualTo("WARNING");

        checkNow(code);
        LinkHealthResponse broken = get(code);
        assertThat(broken.state()).isEqualTo("BROKEN");
        assertThat(broken.issue()).isEqualTo("NOT_FOUND");
        assertThat(broken.message()).startsWith("Your link points to a dead page.");
        assertThat(broken.consecutiveFailures()).isEqualTo(2);
        assertThat(broken.lastHealthyAt()).isEqualTo(healthy.lastHealthyAt());
        // Backoff: second failure -> 30 minutes (±10% jitter), not tomorrow.
        assertThat(broken.nextCheckAt()).isBetween(Instant.now().plus(Duration.ofMinutes(26)), Instant.now().plus(Duration.ofMinutes(34)));
        assertThat(details(code).healthState()).isEqualTo("BROKEN");
    }

    @Test
    void recordsRedirectChainAndFinalDestination() {
        site.redirect("/old-path", 301, "/new-path");
        site.page("/new-path", 200, "New home");
        String code = create(site.url("/old-path"));

        checkNow(code);
        LinkHealthResponse h = get(code);
        assertThat(h.state()).isEqualTo("HEALTHY");
        assertThat(h.finalUrl()).isEqualTo(site.url("/new-path"));
        assertThat(h.redirectChain()).containsExactly(new Hop(site.url("/old-path"), 301), new Hop(site.url("/new-path"), 200));
        assertThat(h.https()).isFalse();
    }

    @Test
    void drasticTitleChangeIsAWarning() {
        site.page("/product", 200, "Acme Analytics Pricing");
        String code = create(site.url("/product"));
        checkNow(code);

        site.page("/product", 200, "This domain may be for sale");
        checkNow(code);
        LinkHealthResponse h = get(code);
        assertThat(h.state()).isEqualTo("WARNING");
        assertThat(h.issue()).isEqualTo("TITLE_CHANGED");
        assertThat(h.message()).contains("Acme Analytics Pricing", "This domain may be for sale");
    }

    @Test
    void manualChecksAreSubjectToACooldown() {
        site.page("/cool", 200, "Cool");
        String code = create(site.url("/cool"));

        LinkHealthResponse first = rest.postForObject("/api/v1/urls/" + code + "/health/check", null, LinkHealthResponse.class);
        site.page("/cool", 500, null);
        LinkHealthResponse second = rest.postForObject("/api/v1/urls/" + code + "/health/check", null, LinkHealthResponse.class);

        assertThat(first.state()).isEqualTo("HEALTHY");
        assertThat(second.checkedAt()).isEqualTo(first.checkedAt()); // not re-fetched within 60s
    }

    @Test
    void neverFetchesPrivateNetworkTargets() {
        String code = create("http://169.254.169.254/latest/meta-data/");

        LinkHealthResponse h = rest.postForObject("/api/v1/urls/" + code + "/health/check", null, LinkHealthResponse.class);
        assertThat(h.state()).isEqualTo("WARNING");
        assertThat(h.issue()).isEqualTo("BLOCKED");
    }

    @Test
    void schedulerBatchChecksDueLinksAndRetiresExpiredOnes() {
        site.page("/due", 200, "Due");
        String due = create(site.url("/due"));
        String expired = TestLinks.uniqueAlias("expired");
        jdbc.update("INSERT INTO short_urls (short_code, original_url, expires_at) VALUES (?, ?, now() - interval '1 minute')",
                expired, site.url("/due"));
        jdbc.update("INSERT INTO link_health (short_url_id) SELECT id FROM short_urls WHERE short_code = ?", expired);
        makeDue(due);
        makeDue(expired);

        assertThat(health.checkDueBatch()).isEqualTo(1); // the expired one is retired, not fetched
        assertThat(get(due).state()).isEqualTo("HEALTHY");
        assertThat(get(expired).state()).isEqualTo("PENDING");
        assertThat(get(expired).nextCheckAt()).isNull(); // 'infinity': never again
        assertThat(health.checkDueBatch()).isZero();
    }

    @Test
    void concurrentSchedulersClaimDisjointBatches() throws Exception {
        site.page("/many", 200, "Many");
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            String code = create(site.url("/many"));
            makeDue(code);
            ids.add(jdbc.queryForObject("SELECT id FROM short_urls WHERE short_code = ?", Long.class, code));
        }

        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<List<LinkHealthRepository.DueLink>> a = CompletableFuture.supplyAsync(() -> claim(start));
        CompletableFuture<List<LinkHealthRepository.DueLink>> b = CompletableFuture.supplyAsync(() -> claim(start));
        start.countDown();

        Set<Long> claimedByA = new HashSet<>();
        a.get().forEach(d -> claimedByA.add(d.shortUrlId()));
        Set<Long> claimedByB = new HashSet<>();
        b.get().forEach(d -> claimedByB.add(d.shortUrlId()));

        assertThat(claimedByA).doesNotContainAnyElementsOf(claimedByB); // SKIP LOCKED: nobody double-checks
        Set<Long> all = new HashSet<>(claimedByA);
        all.addAll(claimedByB);
        assertThat(all).containsExactlyInAnyOrderElementsOf(ids);
    }

    private List<LinkHealthRepository.DueLink> claim(CountDownLatch start) {
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return healthRepository.claimDue(5, Duration.ofMinutes(10));
    }

    private String create(String url) {
        String alias = TestLinks.uniqueAlias("health");
        rest.postForEntity("/api/v1/urls", Map.of("url", url, "customAlias", alias), String.class);
        return alias;
    }

    /** Runs one scheduler pass with only this link due, as the background job would. */
    private void checkNow(String code) {
        makeDue(code);
        assertThat(health.checkDueBatch()).isEqualTo(1);
    }

    private void makeDue(String code) {
        jdbc.update("UPDATE link_health SET next_check_at = now() - interval '1 second' "
                + "WHERE short_url_id = (SELECT id FROM short_urls WHERE short_code = ?)", code);
    }

    private LinkHealthResponse get(String code) {
        return rest.getForObject("/api/v1/urls/" + code + "/health", LinkHealthResponse.class);
    }

    private ShortUrlResponse details(String code) {
        return rest.getForObject("/api/v1/urls/" + code, ShortUrlResponse.class);
    }
}
