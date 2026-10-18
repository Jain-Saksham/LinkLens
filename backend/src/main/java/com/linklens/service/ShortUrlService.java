package com.linklens.service;

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
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShortUrlService {

    /** With 62^7 possible codes, even a single retry is rare; repeated collisions mean something is wrong. */
    static final int MAX_GENERATION_ATTEMPTS = 5;

    /** Paths a custom alias must never shadow. Compared case-insensitively. */
    static final Set<String> RESERVED_ALIASES = Set.of("api", "actuator", "health", "admin", "static", "assets");

    static final String SHORT_CODE_CONSTRAINT = "uk_short_urls_short_code";

    private final ShortUrlRepository repository;
    private final ShortCodeGenerator codeGenerator;
    private final ShortUrlCache cache;
    private final ClickRecorder clickRecorder;
    private final AppProperties properties;
    private final Clock clock;
    private final String ownHost;

    public ShortUrlService(ShortUrlRepository repository, ShortCodeGenerator codeGenerator, ShortUrlCache cache,
                           ClickRecorder clickRecorder, AppProperties properties, Clock clock) {
        this.repository = repository;
        this.codeGenerator = codeGenerator;
        this.cache = cache;
        this.clickRecorder = clickRecorder;
        this.properties = properties;
        this.clock = clock;
        this.ownHost = URI.create(properties.normalizedBaseUrl()).getHost();
    }

    /**
     * Deliberately not {@code @Transactional}: each insert attempt commits (or fails) on its own, so
     * a unique-constraint failure can be retried with a fresh code, and the cache is only written
     * after the row is committed (never caching data that might roll back).
     */
    public ShortUrlResponse create(CreateShortUrlRequest request) {
        String url = request.url().trim();
        rejectSelfReferencingUrl(url);

        ShortUrl saved = request.customAlias() != null
                ? insertWithAlias(request.customAlias(), url, request.expiresAt())
                : insertWithGeneratedCode(url, request.expiresAt());

        // Write-through: also replaces a cached "not found" for this code.
        cache.put(saved.getShortCode(), toCached(saved));
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public ShortUrlResponse getDetails(String shortCode) {
        return toResponse(findOrThrow(shortCode));
    }

    /** Resolves a code to its destination and records the click (asynchronously). */
    public String resolveAndRecordClick(String shortCode, ClickContext context) {
        CachedLink link = cache.get(shortCode).orElseGet(() -> loadAndCache(shortCode));
        if (link.isNotFound()) {
            throw new ShortUrlNotFoundException(shortCode);
        }
        Instant now = Instant.now(clock);
        if (link.isExpiredAt(now)) {
            throw new ShortUrlExpiredException(shortCode);
        }
        if (!ClickSource.isLikelyBot(context.userAgent())) {
            clickRecorder.record(link.id(), ClickSource.referrerHost(context.referer(), ownHost), now);
        }
        return link.originalUrl();
    }

    private CachedLink loadAndCache(String shortCode) {
        CachedLink link = repository.findByShortCode(shortCode)
                .map(ShortUrlService::toCached)
                .orElseGet(CachedLink::notFound);
        cache.put(shortCode, link);
        return link;
    }

    private ShortUrl findOrThrow(String shortCode) {
        return repository.findByShortCode(shortCode)
                .orElseThrow(() -> new ShortUrlNotFoundException(shortCode));
    }

    /**
     * No "exists?" pre-check: the unique constraint is the single source of truth, which also makes
     * two concurrent requests for the same alias safe (exactly one INSERT wins, the other gets 409).
     */
    private ShortUrl insertWithAlias(String alias, String url, Instant expiresAt) {
        if (RESERVED_ALIASES.contains(alias.toLowerCase(Locale.ROOT))) {
            throw new AliasUnavailableException(alias);
        }
        try {
            return repository.saveAndFlush(new ShortUrl(alias, url, true, Instant.now(clock), expiresAt));
        } catch (DataIntegrityViolationException e) {
            if (isShortCodeConflict(e)) {
                throw new AliasUnavailableException(alias);
            }
            throw e;
        }
    }

    /** Optimistic insert: try a random code, and on the (rare) collision just try another one. */
    private ShortUrl insertWithGeneratedCode(String url, Instant expiresAt) {
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            String candidate = codeGenerator.generate();
            try {
                return repository.saveAndFlush(new ShortUrl(candidate, url, false, Instant.now(clock), expiresAt));
            } catch (DataIntegrityViolationException e) {
                if (!isShortCodeConflict(e)) {
                    throw e;
                }
            }
        }
        throw new IllegalStateException("Could not generate a unique short code after "
                + MAX_GENERATION_ATTEMPTS + " attempts");
    }

    /** Only a duplicate short code is a conflict; any other integrity failure is a real bug. */
    static boolean isShortCodeConflict(DataIntegrityViolationException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve) {
                return SHORT_CODE_CONSTRAINT.equalsIgnoreCase(cve.getConstraintName());
            }
        }
        return false;
    }

    /** Prevents redirect loops: a LinkLens link that points at another LinkLens link. */
    private void rejectSelfReferencingUrl(String url) {
        String targetHost = URI.create(url).getHost();
        if (ownHost != null && ownHost.equalsIgnoreCase(targetHost)) {
            throw new InvalidUrlException("URL must not point to LinkLens itself");
        }
    }

    private static CachedLink toCached(ShortUrl s) {
        return new CachedLink(s.getId(), s.getOriginalUrl(), s.getExpiresAt());
    }

    private ShortUrlResponse toResponse(ShortUrl s) {
        return new ShortUrlResponse(
                s.getShortCode(),
                properties.normalizedBaseUrl() + "/" + s.getShortCode(),
                s.getOriginalUrl(),
                s.isCustomAlias(),
                s.getClickCount(),
                s.getCreatedAt(),
                s.getExpiresAt());
    }
}
