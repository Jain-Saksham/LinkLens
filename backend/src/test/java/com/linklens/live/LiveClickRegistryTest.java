package com.linklens.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.linklens.config.LiveProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class LiveClickRegistryTest {

    @Test
    void capsSubscribersPerLink() {
        LiveClickRegistry registry = new LiveClickRegistry(new LiveProperties(2, 100));
        registry.subscribe(1L, 0);
        registry.subscribe(1L, 0);

        assertThatThrownBy(() -> registry.subscribe(1L, 0)).isInstanceOf(LiveClickRegistry.TooManySubscribersException.class);
        registry.subscribe(2L, 0); // other links unaffected
        assertThat(registry.subscriberCount()).isEqualTo(3);
    }

    @Test
    void capsSubscribersInTotalWithoutLeakingTheCount() {
        LiveClickRegistry registry = new LiveClickRegistry(new LiveProperties(10, 2));
        registry.subscribe(1L, 0);
        registry.subscribe(2L, 0);

        assertThatThrownBy(() -> registry.subscribe(3L, 0)).isInstanceOf(LiveClickRegistry.TooManySubscribersException.class);
        assertThat(registry.subscriberCount()).isEqualTo(2);
    }

    @Test
    void reportsOnlyWatchedLinksSoTheFlusherCanSkipWork() {
        LiveClickRegistry registry = new LiveClickRegistry(new LiveProperties(10, 10));
        registry.subscribe(7L, 0);

        assertThat(registry.watched(List.of(5L, 6L, 7L))).containsExactly(7L);
        assertThat(registry.watched(List.of(5L))).isEmpty();
    }
}
