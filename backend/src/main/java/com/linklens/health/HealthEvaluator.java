package com.linklens.health;

import com.linklens.config.HealthProperties;
import com.linklens.health.StoredHealth.HealthState;
import com.linklens.inspection.InspectionResult;
import com.linklens.inspection.InspectionResult.Outcome;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Pure state transition: previous health + a new inspection becomes the next health. No I/O.
 *
 * <ul>
 *   <li><b>Debounced</b>: one failure is a WARNING. Only {@code failureThreshold} consecutive failures
 *       make a link BROKEN, so a 30-second outage or a deploy doesn't produce a false alarm.</li>
 *   <li><b>Backoff</b>: a failing link is retried after 15m, 30m, 1h, 2h... (capped), healthy links
 *       daily. Every delay gets ±10% jitter so links created together don't stay synchronized and
 *       hammer the scheduler (or one destination) at the same moment forever.</li>
 *   <li><b>Unverifiable is not broken</b>: 401/403/429 (bot walls) and private-network targets are
 *       WARNINGs that never count toward BROKEN.</li>
 *   <li><b>Content drift</b>: the first title seen is the baseline. A later title sharing under 30% of
 *       its words ("Pricing - Acme" becoming "Domain for sale") is flagged: the URL works but the page
 *       it pointed at is gone.</li>
 * </ul>
 */
public final class HealthEvaluator {

    static final double TITLE_SIMILARITY_THRESHOLD = 0.3;
    public static final String TITLE_CHANGED = "TITLE_CHANGED";

    private HealthEvaluator() {
    }

    /** @param jitter random factor in [0.9, 1.1], injected so tests are deterministic */
    public static StoredHealth evaluate(StoredHealth previous, InspectionResult r, HealthProperties props,
                                        Instant now, double jitter) {
        Outcome outcome = r.outcome();
        if (outcome == Outcome.OK) {
            String baseline = previous.baselineTitle() != null ? previous.baselineTitle() : r.title();
            boolean drifted = baseline != null && r.title() != null
                    && similarity(baseline, r.title()) < TITLE_SIMILARITY_THRESHOLD;
            return new StoredHealth(drifted ? HealthState.WARNING : HealthState.HEALTHY,
                    drifted ? TITLE_CHANGED : null, r.httpStatus(), r.finalUrl(), r.title(), baseline,
                    r.redirectChain(), 0, now, now, now.plus(scale(props.healthyInterval(), jitter)));
        }
        if (!outcome.isFailure()) { // ACCESS_RESTRICTED, BLOCKED: can't tell, so don't count it
            return new StoredHealth(HealthState.WARNING, outcome.name(), r.httpStatus(), r.finalUrl(), null,
                    previous.baselineTitle(), r.redirectChain(), 0, now, previous.lastHealthyAt(),
                    now.plus(scale(props.healthyInterval(), jitter)));
        }
        int failures = previous.consecutiveFailures() + 1;
        HealthState state = failures >= props.failureThreshold() ? HealthState.BROKEN : HealthState.WARNING;
        return new StoredHealth(state, outcome.name(), r.httpStatus(), r.finalUrl(), null,
                previous.baselineTitle(), r.redirectChain(), failures, now, previous.lastHealthyAt(),
                now.plus(scale(retryDelay(failures, props), jitter)));
    }

    /** retryBase × 2^(failures-1), capped at retryMax. */
    static Duration retryDelay(int failures, HealthProperties props) {
        Duration delay = props.retryBase().multipliedBy(1L << Math.min(failures - 1, 20));
        return delay.compareTo(props.retryMax()) > 0 ? props.retryMax() : delay;
    }

    /** Jaccard similarity of the two titles' word sets (case-insensitive, words of 2+ letters/digits). */
    static double similarity(String a, String b) {
        Set<String> wa = words(a);
        Set<String> wb = words(b);
        if (wa.isEmpty() || wb.isEmpty()) {
            return 1.0; // nothing to compare: don't raise an alarm
        }
        Set<String> intersection = new HashSet<>(wa);
        intersection.retainAll(wb);
        Set<String> union = new HashSet<>(wa);
        union.addAll(wb);
        return (double) intersection.size() / union.size();
    }

    private static Set<String> words(String text) {
        Set<String> words = new HashSet<>();
        for (String w : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (w.length() >= 2) {
                words.add(w);
            }
        }
        return words;
    }

    private static Duration scale(Duration d, double factor) {
        return Duration.ofMillis(Math.round(d.toMillis() * factor));
    }
}
