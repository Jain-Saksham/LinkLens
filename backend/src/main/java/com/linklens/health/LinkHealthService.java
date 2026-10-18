package com.linklens.health;

import com.linklens.config.HealthProperties;
import com.linklens.domain.ShortUrl;
import com.linklens.dto.LinkHealthResponse;
import com.linklens.exception.ShortUrlNotFoundException;
import com.linklens.inspection.DestinationInspector;
import com.linklens.inspection.InspectionResult;
import com.linklens.repository.ShortUrlRepository;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Link-rot monitoring: decides when to check, runs checks safely, and records the outcome.
 *
 * <p>Checks run on virtual threads (cheap to block on network I/O), with a semaphore capping how many
 * hit the network at once. Concurrent requests for the same link share one in-flight check, so a
 * burst of preview visitors costs a single outbound request.
 */
@Service
public class LinkHealthService {

    private static final Logger log = LoggerFactory.getLogger(LinkHealthService.class);
    /** A claimed link becomes due again after this long if its checker died mid-check. */
    static final Duration CLAIM_LEASE = Duration.ofMinutes(10);

    private final LinkHealthRepository healthRepository;
    private final ShortUrlRepository shortUrls;
    private final DestinationInspector inspector;
    private final HealthProperties props;
    private final Clock clock;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore permits;
    private final ConcurrentMap<Long, CompletableFuture<StoredHealth>> inFlight = new ConcurrentHashMap<>();

    public LinkHealthService(LinkHealthRepository healthRepository, ShortUrlRepository shortUrls,
                             DestinationInspector inspector, HealthProperties props, Clock clock) {
        this.healthRepository = healthRepository;
        this.shortUrls = shortUrls;
        this.inspector = inspector;
        this.props = props;
        this.clock = clock;
        this.permits = new Semaphore(props.concurrency());
    }

    /** One scheduler pass: claim due links, check them in parallel, wait for the batch. */
    public int checkDueBatch() {
        List<LinkHealthRepository.DueLink> due = healthRepository.claimDue(props.batchSize(), CLAIM_LEASE);
        List<CompletableFuture<StoredHealth>> running = new ArrayList<>();
        for (var link : due) {
            if (link.expiresAt() != null && !Instant.now(clock).isBefore(link.expiresAt())) {
                healthRepository.retire(link.shortUrlId());
                continue;
            }
            running.add(check(link.shortUrlId(), link.originalUrl(), link.previous()));
        }
        try {
            CompletableFuture.allOf(running.toArray(CompletableFuture[]::new))
                    .get(props.totalTimeout().plusSeconds(5).toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("Health batch still running after the deadline; leases will retry stragglers");
        } catch (Exception e) {
            log.warn("Health batch failed: {}", e.toString());
        }
        return running.size();
    }

    public LinkHealthResponse getHealth(String shortCode) {
        ShortUrl link = find(shortCode);
        return LinkHealthResponse.from(stored(link.getId()));
    }

    /** On-demand check, at most once per {@code manualCooldown} per link (it costs an outbound request). */
    public LinkHealthResponse checkNow(String shortCode) {
        ShortUrl link = find(shortCode);
        StoredHealth current = stored(link.getId());
        if (current.checkedAt() != null
                && current.checkedAt().plus(props.manualCooldown()).isAfter(Instant.now(clock))) {
            return LinkHealthResponse.from(current);
        }
        try {
            return LinkHealthResponse.from(check(link.getId(), link.getOriginalUrl(), current).join());
        } catch (RuntimeException e) {
            return LinkHealthResponse.from(current); // check itself failed (e.g. DB): report last known state
        }
    }

    /**
     * For the preview page: the stored inspection if it's fresh; otherwise starts (or joins) a check
     * and waits up to {@code waitAtMost}. Empty means "still checking". The page then auto-refreshes.
     */
    public Optional<StoredHealth> freshForPreview(ShortUrl link, Duration waitAtMost) {
        StoredHealth current = stored(link.getId());
        boolean fresh = current.checkedAt() != null
                && current.checkedAt().plus(props.previewMaxAge()).isAfter(Instant.now(clock));
        if (fresh) {
            return Optional.of(current);
        }
        try {
            return Optional.of(check(link.getId(), link.getOriginalUrl(), current)
                    .get(waitAtMost.toMillis(), TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            return Optional.empty();
        } catch (Exception e) {
            return current.checkedAt() != null ? Optional.of(current) : Optional.empty();
        }
    }

    public Optional<StoredHealth> storedHealth(long shortUrlId) {
        return healthRepository.find(shortUrlId);
    }

    /** Starts a check, or returns the one already running for this link. */
    CompletableFuture<StoredHealth> check(long shortUrlId, String url, StoredHealth previous) {
        CompletableFuture<StoredHealth> mine = new CompletableFuture<>();
        CompletableFuture<StoredHealth> existing = inFlight.putIfAbsent(shortUrlId, mine);
        if (existing != null) {
            return existing;
        }
        executor.execute(() -> {
            try {
                permits.acquire();
                try {
                    InspectionResult result = inspector.inspect(url);
                    double jitter = 0.9 + ThreadLocalRandom.current().nextDouble() * 0.2;
                    StoredHealth next = HealthEvaluator.evaluate(previous, result, props, Instant.now(clock), jitter);
                    healthRepository.save(shortUrlId, next);
                    mine.complete(next);
                } finally {
                    permits.release();
                }
            } catch (Throwable t) {
                log.warn("Health check of link {} failed: {}", shortUrlId, t.toString());
                mine.completeExceptionally(t);
            } finally {
                inFlight.remove(shortUrlId, mine);
            }
        });
        return mine;
    }

    private StoredHealth stored(long shortUrlId) {
        return healthRepository.find(shortUrlId).orElseGet(() -> StoredHealth.pending(Instant.now(clock)));
    }

    private ShortUrl find(String shortCode) {
        return shortUrls.findByShortCode(shortCode).orElseThrow(() -> new ShortUrlNotFoundException(shortCode));
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
