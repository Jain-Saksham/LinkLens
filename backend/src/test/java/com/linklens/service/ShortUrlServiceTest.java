package com.linklens.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.linklens.cache.CachedLink;
import com.linklens.cache.ShortUrlCache;
import com.linklens.config.AppProperties;
import com.linklens.domain.ShortUrl;
import com.linklens.dto.CreateShortUrlRequest;
import com.linklens.dto.ShortUrlResponse;
import com.linklens.exception.AliasUnavailableException;
import com.linklens.exception.InvalidUrlException;
import com.linklens.exception.ShortUrlExpiredException;
import com.linklens.exception.ShortUrlNotFoundException;
import com.linklens.repository.ShortUrlRepository;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class ShortUrlServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

    @Mock
    private ShortUrlRepository repository;
    @Mock
    private ShortCodeGenerator codeGenerator;
    @Mock
    private ShortUrlCache cache;
    @Mock
    private ClickRecorder clickRecorder;

    private ShortUrlService service;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties("http://lnk.test/", 7, List.of("http://localhost:5173"));
        service = new ShortUrlService(repository, codeGenerator, cache, clickRecorder, properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Nested
    class Create {

        @Test
        void createsLinkWithGeneratedCodeAndWritesThroughToCache() {
            when(codeGenerator.generate()).thenReturn("abc1234");
            when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

            ShortUrlResponse response = service.create(new CreateShortUrlRequest("  https://example.com/a  ", null, null));

            assertThat(response.shortCode()).isEqualTo("abc1234");
            assertThat(response.shortUrl()).isEqualTo("http://lnk.test/abc1234");
            assertThat(response.originalUrl()).isEqualTo("https://example.com/a");
            assertThat(response.createdAt()).isEqualTo(NOW);
            verify(cache).put(eq("abc1234"), any(CachedLink.class));
        }

        @Test
        void retriesWithANewCodeWhenTheGeneratedOneCollides() {
            when(codeGenerator.generate()).thenReturn("taken01", "free001");
            when(repository.saveAndFlush(any()))
                    .thenThrow(shortCodeConflict())
                    .thenAnswer(inv -> inv.getArgument(0));

            assertThat(service.create(new CreateShortUrlRequest("https://example.com", null, null)).shortCode())
                    .isEqualTo("free001");
        }

        @Test
        void givesUpAfterTooManyCollisions() {
            when(codeGenerator.generate()).thenReturn("taken01");
            when(repository.saveAndFlush(any())).thenThrow(shortCodeConflict());

            assertThatThrownBy(() -> service.create(new CreateShortUrlRequest("https://example.com", null, null)))
                    .isInstanceOf(IllegalStateException.class);
            verify(repository, times(ShortUrlService.MAX_GENERATION_ATTEMPTS)).saveAndFlush(any());
            verify(cache, never()).put(anyString(), any());
        }

        @Test
        void createsLinkWithCustomAliasAndExpiry() {
            Instant expiry = NOW.plus(Duration.ofDays(7));
            when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

            ShortUrlResponse response = service.create(new CreateShortUrlRequest("https://example.com", "my-link", expiry));

            assertThat(response.shortCode()).isEqualTo("my-link");
            assertThat(response.customAlias()).isTrue();
            assertThat(response.expiresAt()).isEqualTo(expiry);
            verifyNoInteractions(codeGenerator);
        }

        @Test
        void aliasConflictFromTheUniqueConstraintBecomesAliasUnavailable() {
            when(repository.saveAndFlush(any())).thenThrow(shortCodeConflict());

            assertThatThrownBy(() -> service.create(new CreateShortUrlRequest("https://example.com", "taken", null)))
                    .isInstanceOf(AliasUnavailableException.class);
            verify(cache, never()).put(anyString(), any());
        }

        @Test
        void otherIntegrityViolationsAreNotDisguisedAsConflicts() {
            DataIntegrityViolationException other = new DataIntegrityViolationException("check failed",
                    new ConstraintViolationException("x", new SQLException(), "ck_short_urls_click_count"));
            when(repository.saveAndFlush(any())).thenThrow(other);

            assertThatThrownBy(() -> service.create(new CreateShortUrlRequest("https://example.com", "alias", null)))
                    .isSameAs(other);
        }

        @Test
        void rejectsReservedAliasCaseInsensitively() {
            assertThatThrownBy(() -> service.create(new CreateShortUrlRequest("https://example.com", "API", null)))
                    .isInstanceOf(AliasUnavailableException.class);
            verifyNoInteractions(repository);
        }

        @Test
        void rejectsUrlPointingBackAtLinkLens() {
            assertThatThrownBy(() -> service.create(new CreateShortUrlRequest("http://LNK.test/abc", null, null)))
                    .isInstanceOf(InvalidUrlException.class);
            verifyNoInteractions(repository);
        }
    }

    @Nested
    class Resolve {

        private final ClickContext fromHackerNews =
                new ClickContext("https://news.ycombinator.com/item?id=1", "Mozilla/5.0");

        @Test
        void cacheHitRedirectsWithoutTouchingTheDatabase() {
            when(cache.get("abc1234")).thenReturn(Optional.of(new CachedLink(42L, "https://example.com", null)));

            assertThat(service.resolveAndRecordClick("abc1234", fromHackerNews)).isEqualTo("https://example.com");
            verifyNoInteractions(repository);
            verify(clickRecorder).record(42L, "news.ycombinator.com", NOW);
        }

        @Test
        void cacheMissLoadsFromDatabaseAndPopulatesCache() {
            when(cache.get("abc1234")).thenReturn(Optional.empty());
            when(repository.findByShortCode("abc1234")).thenReturn(Optional.of(link(7L, null)));

            assertThat(service.resolveAndRecordClick("abc1234", ClickContext.NONE)).isEqualTo("https://example.com");
            verify(cache).put("abc1234", new CachedLink(7L, "https://example.com", null));
            verify(clickRecorder).record(eq(7L), isNull(), eq(NOW));
        }

        @Test
        void unknownCodeIsCachedAsNotFound() {
            when(cache.get("missing")).thenReturn(Optional.empty());
            when(repository.findByShortCode("missing")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.resolveAndRecordClick("missing", ClickContext.NONE))
                    .isInstanceOf(ShortUrlNotFoundException.class);
            verify(cache).put("missing", CachedLink.notFound());
        }

        @Test
        void cachedNotFoundIsServedWithoutTheDatabase() {
            when(cache.get("missing")).thenReturn(Optional.of(CachedLink.notFound()));

            assertThatThrownBy(() -> service.resolveAndRecordClick("missing", ClickContext.NONE))
                    .isInstanceOf(ShortUrlNotFoundException.class);
            verifyNoInteractions(repository);
        }

        @Test
        void expiredLinkIsRejectedEvenWhenServedFromCache() {
            when(cache.get("old1234")).thenReturn(Optional.of(new CachedLink(1L, "https://example.com", NOW)));

            assertThatThrownBy(() -> service.resolveAndRecordClick("old1234", ClickContext.NONE))
                    .isInstanceOf(ShortUrlExpiredException.class);
            verifyNoInteractions(clickRecorder);
        }

        @Test
        void botsAreRedirectedButNotCounted() {
            when(cache.get("abc1234")).thenReturn(Optional.of(new CachedLink(42L, "https://example.com", null)));

            service.resolveAndRecordClick("abc1234", new ClickContext(null, "Slackbot-LinkExpanding 1.0"));
            verifyNoInteractions(clickRecorder);
        }
    }

    private static DataIntegrityViolationException shortCodeConflict() {
        return new DataIntegrityViolationException("duplicate key",
                new ConstraintViolationException("duplicate", new SQLException(), ShortUrlService.SHORT_CODE_CONSTRAINT));
    }

    private static ShortUrl link(Long id, Instant expiresAt) {
        ShortUrl link = new ShortUrl("abc1234", "https://example.com", false, NOW.minusSeconds(3600), expiresAt);
        org.springframework.test.util.ReflectionTestUtils.setField(link, "id", id);
        return link;
    }
}
