package com.linklens.live;

import com.linklens.config.LiveProperties;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Open Server-Sent-Event streams, grouped by link.
 *
 * <ul>
 *   <li>Streams use servlet async I/O: an open stream holds a socket, not a request thread.</li>
 *   <li>Capped per link and in total, because each stream is a held connection (a cheap DoS lever).</li>
 *   <li>Sends happen on a dedicated thread: a slow or stalled client can block a write, and that must
 *       never be the click flusher's thread.</li>
 *   <li>A heartbeat every 20s keeps proxies from closing idle streams and detects dead clients.</li>
 *   <li>Per instance: with several instances, the flusher would publish "link X changed" over Redis
 *       pub/sub and every instance would fan out to its own subscribers.</li>
 * </ul>
 */
@Component
public class LiveClickRegistry {

    static final Duration STREAM_TIMEOUT = Duration.ofMinutes(30); // client (EventSource) reconnects after

    private final ConcurrentMap<Long, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();
    private final AtomicInteger total = new AtomicInteger();
    private final LiveProperties props;
    private final ExecutorService sender = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("sse-sender").factory());

    public LiveClickRegistry(LiveProperties props) {
        this.props = props;
    }

    public SseEmitter subscribe(long shortUrlId, long currentCount) {
        Set<SseEmitter> forLink = subscribers.computeIfAbsent(shortUrlId, id -> new CopyOnWriteArraySet<>());
        // Not atomic across the two checks: a burst may overshoot the per-link cap by a few, which is fine.
        if (forLink.size() >= props.maxSubscribersPerLink()) {
            throw new TooManySubscribersException();
        }
        if (total.incrementAndGet() > props.maxSubscribers()) {
            total.decrementAndGet();
            throw new TooManySubscribersException();
        }
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT.toMillis());
        forLink.add(emitter);
        Runnable remove = () -> {
            if (forLink.remove(emitter)) {
                total.decrementAndGet();
            }
        };
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(e -> remove.run());
        send(emitter, currentCount); // the current value immediately, so the UI is right from the start
        return emitter;
    }

    /** Which of these links does anyone watch? Lets the flusher skip the count query when nobody does. */
    public Set<Long> watched(Collection<Long> ids) {
        return ids.stream().filter(id -> !subscribers.getOrDefault(id, Set.of()).isEmpty()).collect(Collectors.toSet());
    }

    /** Fire-and-forget: returns immediately, delivery happens on the sender thread. */
    public void publish(Map<Long, Long> clickCounts) {
        if (clickCounts.isEmpty()) {
            return;
        }
        sender.execute(() -> clickCounts.forEach((id, count) ->
                subscribers.getOrDefault(id, Set.of()).forEach(emitter -> send(emitter, count))));
    }

    @Scheduled(fixedRate = 20_000)
    public void heartbeat() {
        sender.execute(() -> subscribers.values().forEach(set -> set.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event().comment("keep-alive"));
            } catch (IOException | IllegalStateException e) {
                emitter.completeWithError(e);
            }
        })));
    }

    int subscriberCount() {
        return total.get();
    }

    private static void send(SseEmitter emitter, long count) {
        try {
            emitter.send(SseEmitter.event().name("clicks").data(Map.of("clickCount", count), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            emitter.completeWithError(e); // client went away: triggers removal
        }
    }

    @PreDestroy
    void shutdown() {
        subscribers.values().forEach(set -> set.forEach(SseEmitter::complete));
        sender.shutdownNow();
    }

    public static class TooManySubscribersException extends RuntimeException {
        public TooManySubscribersException() {
            super("Too many live viewers right now. Try again shortly.");
        }
    }
}
