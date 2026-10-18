package com.linklens.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.linklens.config.HealthProperties;
import com.linklens.health.StoredHealth.HealthState;
import com.linklens.inspection.InspectionResult;
import com.linklens.inspection.InspectionResult.Hop;
import com.linklens.inspection.InspectionResult.Outcome;
import com.linklens.support.TestHealthProps;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class HealthEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final HealthProperties PROPS = TestHealthProps.withLoopback(false);
    private static final StoredHealth NEW = StoredHealth.pending(NOW);

    @Test
    void healthyCheckRecordsBaselineTitleAndSchedulesDaily() {
        StoredHealth h = HealthEvaluator.evaluate(NEW, ok("Spring Boot Reference"), PROPS, NOW, 1.0);

        assertThat(h.state()).isEqualTo(HealthState.HEALTHY);
        assertThat(h.baselineTitle()).isEqualTo("Spring Boot Reference");
        assertThat(h.lastHealthyAt()).isEqualTo(NOW);
        assertThat(h.nextCheckAt()).isEqualTo(NOW.plus(Duration.ofHours(24)));
    }

    @Test
    void oneFailureWarnsAndTheThresholdBreaks() {
        StoredHealth first = HealthEvaluator.evaluate(NEW, fail(Outcome.NOT_FOUND, 404), PROPS, NOW, 1.0);
        assertThat(first.state()).isEqualTo(HealthState.WARNING);
        assertThat(first.consecutiveFailures()).isEqualTo(1);

        StoredHealth second = HealthEvaluator.evaluate(first, fail(Outcome.NOT_FOUND, 404), PROPS, NOW, 1.0);
        assertThat(second.state()).isEqualTo(HealthState.BROKEN);
        assertThat(second.issue()).isEqualTo("NOT_FOUND");
        assertThat(HealthMessages.describe(second)).startsWith("Your link points to a dead page.");
    }

    @Test
    void recoveryResetsTheFailureCount() {
        StoredHealth broken = HealthEvaluator.evaluate(
                HealthEvaluator.evaluate(NEW, fail(Outcome.TIMEOUT, null), PROPS, NOW, 1.0),
                fail(Outcome.TIMEOUT, null), PROPS, NOW, 1.0);

        StoredHealth recovered = HealthEvaluator.evaluate(broken, ok("Home"), PROPS, NOW, 1.0);
        assertThat(recovered.state()).isEqualTo(HealthState.HEALTHY);
        assertThat(recovered.consecutiveFailures()).isZero();
    }

    @Test
    void retriesBackOffExponentiallyUpToTheCap() {
        assertThat(HealthEvaluator.retryDelay(1, PROPS)).isEqualTo(Duration.ofMinutes(15));
        assertThat(HealthEvaluator.retryDelay(2, PROPS)).isEqualTo(Duration.ofMinutes(30));
        assertThat(HealthEvaluator.retryDelay(4, PROPS)).isEqualTo(Duration.ofHours(2));
        assertThat(HealthEvaluator.retryDelay(30, PROPS)).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void jitterSpreadsTheNextCheck() {
        StoredHealth low = HealthEvaluator.evaluate(NEW, fail(Outcome.SERVER_ERROR, 503), PROPS, NOW, 0.9);
        StoredHealth high = HealthEvaluator.evaluate(NEW, fail(Outcome.SERVER_ERROR, 503), PROPS, NOW, 1.1);
        assertThat(low.nextCheckAt()).isEqualTo(NOW.plus(Duration.ofSeconds(810)));
        assertThat(high.nextCheckAt()).isEqualTo(NOW.plus(Duration.ofSeconds(990)));
    }

    @Test
    void botWallsAndPrivateTargetsAreWarningsThatNeverBecomeBroken() {
        StoredHealth h = NEW;
        for (int i = 0; i < 5; i++) {
            h = HealthEvaluator.evaluate(h, fail(Outcome.ACCESS_RESTRICTED, 403), PROPS, NOW, 1.0);
        }
        assertThat(h.state()).isEqualTo(HealthState.WARNING);
        assertThat(h.consecutiveFailures()).isZero();
    }

    @Test
    void drasticTitleChangeIsFlaggedButSmallEditsAreNot() {
        StoredHealth baseline = HealthEvaluator.evaluate(NEW, ok("Pricing - Acme Analytics"), PROPS, NOW, 1.0);

        StoredHealth edited = HealthEvaluator.evaluate(baseline, ok("Pricing & Plans - Acme Analytics"), PROPS, NOW, 1.0);
        assertThat(edited.state()).isEqualTo(HealthState.HEALTHY);

        StoredHealth parked = HealthEvaluator.evaluate(baseline, ok("This domain is for sale"), PROPS, NOW, 1.0);
        assertThat(parked.state()).isEqualTo(HealthState.WARNING);
        assertThat(parked.issue()).isEqualTo(HealthEvaluator.TITLE_CHANGED);
        assertThat(parked.baselineTitle()).isEqualTo("Pricing - Acme Analytics"); // baseline is kept
        assertThat(HealthMessages.describe(parked)).contains("Pricing - Acme Analytics", "This domain is for sale");
    }

    @Test
    void similarityIsWordBasedAndCaseInsensitive() {
        assertThat(HealthEvaluator.similarity("Spring Boot", "spring boot")).isEqualTo(1.0);
        assertThat(HealthEvaluator.similarity("Spring Boot", "Django")).isZero();
        assertThat(HealthEvaluator.similarity("", "anything")).isEqualTo(1.0);
    }

    private static InspectionResult ok(String title) {
        return new InspectionResult(Outcome.OK, 200, List.of(new Hop("https://example.com", 200)), title, NOW, 10, null);
    }

    private static InspectionResult fail(Outcome outcome, Integer status) {
        return new InspectionResult(outcome, status, List.of(new Hop("https://example.com", status)), null, NOW, 10, "x");
    }
}
