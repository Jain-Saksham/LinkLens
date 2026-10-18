package com.linklens.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.linklens.config.CacheProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class ShortUrlCacheTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    private static final CacheProperties PROPS =
            new CacheProperties(true, Duration.ofHours(1), Duration.ofSeconds(60), Duration.ofSeconds(30));

    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final MutableClock clock = new MutableClock(NOW);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private ShortUrlCache cache;

    @BeforeEach
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(ops);
        cache = new ShortUrlCache(redis, mapper, PROPS, clock, meters);
    }

    @Test
    void roundTripsLinksAndNegativeEntriesThroughJson() throws Exception {
        CachedLink link = new CachedLink(5L, "https://example.com", NOW.plusSeconds(600));
        when(ops.get(ShortUrlCache.key("a"))).thenReturn(mapper.writeValueAsString(link));
        when(ops.get(ShortUrlCache.key("b"))).thenReturn(mapper.writeValueAsString(CachedLink.notFound()));

        assertThat(cache.get("a")).contains(link);
        assertThat(cache.get("b")).hasValueSatisfying(l -> assertThat(l.isNotFound()).isTrue());
        assertThat(cache.get("c")).isEmpty();
        assertThat(meters.counter("linklens.cache", "result", "hit").count()).isEqualTo(2);
        assertThat(meters.counter("linklens.cache", "result", "miss").count()).isEqualTo(1);
    }

    @Test
    void ttlIsCappedByTheLinksExpiry() {
        assertThat(cache.ttlFor(new CachedLink(1L, "u", null))).isEqualTo(Duration.ofHours(1));
        assertThat(cache.ttlFor(new CachedLink(1L, "u", NOW.plusSeconds(90)))).isEqualTo(Duration.ofSeconds(90));
        assertThat(cache.ttlFor(new CachedLink(1L, "u", NOW.plus(Duration.ofDays(3))))).isEqualTo(Duration.ofHours(1));
        assertThat(cache.ttlFor(CachedLink.notFound())).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void alreadyExpiredLinksAreNotCached() {
        cache.put("old", new CachedLink(1L, "u", NOW.minusSeconds(1)));
        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void redisFailureFailsOpenAndBacksOffBeforeRetrying() {
        when(ops.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(cache.get("a")).isEmpty();          // error -> treated as miss
        assertThat(cache.get("b")).isEmpty();          // bypassed: Redis not called again
        cache.put("c", new CachedLink(1L, "u", null)); // writes bypassed too
        verify(ops, times(1)).get(anyString());
        verify(ops, never()).set(anyString(), anyString(), any(Duration.class));
        assertThat(meters.counter("linklens.cache", "result", "error").count()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(31));          // backoff elapsed: try Redis again
        assertThat(cache.get("d")).isEmpty();
        verify(ops, times(2)).get(anyString());
    }

    @Test
    void unreadableEntryIsTreatedAsMissWithoutDisablingTheCache() {
        when(ops.get(anyString())).thenReturn("{not json");

        assertThat(cache.get("a")).isEmpty();
        assertThat(cache.get("b")).isEmpty();
        verify(ops, times(2)).get(anyString());
    }

    @Test
    void disabledCacheNeverTalksToRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ShortUrlCache off = new ShortUrlCache(redis,
                mapper, new CacheProperties(false, Duration.ofHours(1), Duration.ofSeconds(60), Duration.ofSeconds(30)),
                clock, meters);

        assertThat(off.get("a")).isEqualTo(Optional.empty());
        off.put("a", new CachedLink(1L, "u", null));
        verify(redis, never()).opsForValue();
    }

    /** A Clock tests can move forward. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
