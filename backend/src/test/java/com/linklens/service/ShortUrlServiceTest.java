package com.linklens.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.linklens.config.AppProperties;
import com.linklens.domain.ShortUrl;
import com.linklens.dto.CreateShortUrlRequest;
import com.linklens.dto.ShortUrlResponse;
import com.linklens.exception.AliasUnavailableException;
import com.linklens.exception.InvalidUrlException;
import com.linklens.exception.ShortUrlExpiredException;
import com.linklens.exception.ShortUrlNotFoundException;
import com.linklens.repository.ShortUrlRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ShortUrlServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");

    @Mock
    private ShortUrlRepository repository;
    @Mock
    private ShortCodeGenerator codeGenerator;

    private ShortUrlService service;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties("http://lnk.test/", 7, List.of("http://localhost:5173"));
        service = new ShortUrlService(repository, codeGenerator, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsLinkWithGeneratedCode() {
        when(codeGenerator.generate()).thenReturn("abc1234");
        when(repository.existsByShortCode("abc1234")).thenReturn(false);
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        ShortUrlResponse response = service.create(new CreateShortUrlRequest("  https://example.com/a  ", null, null));

        assertThat(response.shortCode()).isEqualTo("abc1234");
        assertThat(response.shortUrl()).isEqualTo("http://lnk.test/abc1234");
        assertThat(response.originalUrl()).isEqualTo("https://example.com/a");
        assertThat(response.customAlias()).isFalse();
        assertThat(response.createdAt()).isEqualTo(NOW);
    }

    @Test
    void retriesWhenGeneratedCodeCollides() {
        when(codeGenerator.generate()).thenReturn("taken01", "free001");
        when(repository.existsByShortCode("taken01")).thenReturn(true);
        when(repository.existsByShortCode("free001")).thenReturn(false);
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        ShortUrlResponse response = service.create(new CreateShortUrlRequest("https://example.com", null, null));

        assertThat(response.shortCode()).isEqualTo("free001");
    }

    @Test
    void failsAfterTooManyCollisions() {
        when(codeGenerator.generate()).thenReturn("taken01");
        when(repository.existsByShortCode("taken01")).thenReturn(true);

        assertThatThrownBy(() -> service.create(new CreateShortUrlRequest("https://example.com", null, null)))
                .isInstanceOf(IllegalStateException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void createsLinkWithCustomAliasAndExpiry() {
        Instant expiry = NOW.plus(Duration.ofDays(7));
        when(repository.existsByShortCode("my-link")).thenReturn(false);
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        ShortUrlResponse response = service.create(new CreateShortUrlRequest("https://example.com", "my-link", expiry));

        assertThat(response.shortCode()).isEqualTo("my-link");
        assertThat(response.customAlias()).isTrue();
        assertThat(response.expiresAt()).isEqualTo(expiry);
        verify(codeGenerator, never()).generate();
    }

    @Test
    void rejectsAliasAlreadyInUse() {
        when(repository.existsByShortCode("taken")).thenReturn(true);

        assertThatThrownBy(() -> service.create(new CreateShortUrlRequest("https://example.com", "taken", null)))
                .isInstanceOf(AliasUnavailableException.class);
    }

    @Test
    void rejectsReservedAliasCaseInsensitively() {
        assertThatThrownBy(() -> service.create(new CreateShortUrlRequest("https://example.com", "API", null)))
                .isInstanceOf(AliasUnavailableException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void translatesConcurrentAliasInsertRaceToConflict() {
        when(repository.existsByShortCode("race")).thenReturn(false);
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service.create(new CreateShortUrlRequest("https://example.com", "race", null)))
                .isInstanceOf(AliasUnavailableException.class);
    }

    @Test
    void rejectsUrlPointingBackAtLinkLens() {
        assertThatThrownBy(() -> service.create(new CreateShortUrlRequest("http://LNK.test/abc", null, null)))
                .isInstanceOf(InvalidUrlException.class);
        verify(repository, never()).existsByShortCode(anyString());
    }

    @Test
    void resolveReturnsTargetAndIncrementsClicks() {
        ShortUrl link = linkWithId(42L, NOW.plusSeconds(60));
        when(repository.findByShortCode("abc1234")).thenReturn(Optional.of(link));

        String target = service.resolveAndRecordClick("abc1234");

        assertThat(target).isEqualTo("https://example.com");
        ArgumentCaptor<Long> id = ArgumentCaptor.forClass(Long.class);
        verify(repository).incrementClickCount(id.capture());
        assertThat(id.getValue()).isEqualTo(42L);
    }

    @Test
    void resolveRejectsExpiredLinkWithoutCountingClick() {
        when(repository.findByShortCode("abc1234")).thenReturn(Optional.of(linkWithId(1L, NOW)));

        assertThatThrownBy(() -> service.resolveAndRecordClick("abc1234"))
                .isInstanceOf(ShortUrlExpiredException.class);
        verify(repository, never()).incrementClickCount(any());
    }

    @Test
    void resolveRejectsUnknownCode() {
        when(repository.findByShortCode("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveAndRecordClick("missing"))
                .isInstanceOf(ShortUrlNotFoundException.class);
    }

    private static ShortUrl linkWithId(Long id, Instant expiresAt) {
        ShortUrl link = new ShortUrl("abc1234", "https://example.com", false, NOW.minusSeconds(3600), expiresAt);
        ReflectionTestUtils.setField(link, "id", id);
        return link;
    }
}
