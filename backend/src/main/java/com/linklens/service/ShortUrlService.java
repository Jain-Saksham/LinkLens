package com.linklens.service;

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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShortUrlService {

    /** With 62^7 possible codes, even a single retry is rare; repeated collisions mean something is wrong. */
    static final int MAX_GENERATION_ATTEMPTS = 5;

    /** Paths a custom alias must never shadow. Compared case-insensitively. */
    static final Set<String> RESERVED_ALIASES = Set.of("api", "actuator", "health", "admin", "static", "assets");

    private final ShortUrlRepository repository;
    private final ShortCodeGenerator codeGenerator;
    private final AppProperties properties;
    private final Clock clock;

    public ShortUrlService(ShortUrlRepository repository, ShortCodeGenerator codeGenerator,
                           AppProperties properties, Clock clock) {
        this.repository = repository;
        this.codeGenerator = codeGenerator;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public ShortUrlResponse create(CreateShortUrlRequest request) {
        String url = request.url().trim();
        rejectSelfReferencingUrl(url);

        boolean custom = request.customAlias() != null;
        String code = custom ? reserveCustomAlias(request.customAlias()) : generateUniqueCode();

        ShortUrl entity = new ShortUrl(code, url, custom, Instant.now(clock), request.expiresAt());
        try {
            // saveAndFlush forces the INSERT now, so a unique-constraint race surfaces here
            // (as a 409) instead of at commit time (as a 500).
            return toResponse(repository.saveAndFlush(entity));
        } catch (DataIntegrityViolationException e) {
            throw new AliasUnavailableException(code);
        }
    }

    @Transactional(readOnly = true)
    public ShortUrlResponse getDetails(String shortCode) {
        return toResponse(findOrThrow(shortCode));
    }

    /** Resolves a code to its destination and records the click. */
    @Transactional
    public String resolveAndRecordClick(String shortCode) {
        ShortUrl shortUrl = findOrThrow(shortCode);
        if (shortUrl.isExpiredAt(Instant.now(clock))) {
            throw new ShortUrlExpiredException(shortCode);
        }
        repository.incrementClickCount(shortUrl.getId());
        return shortUrl.getOriginalUrl();
    }

    private ShortUrl findOrThrow(String shortCode) {
        return repository.findByShortCode(shortCode)
                .orElseThrow(() -> new ShortUrlNotFoundException(shortCode));
    }

    private String reserveCustomAlias(String alias) {
        if (RESERVED_ALIASES.contains(alias.toLowerCase(Locale.ROOT)) || repository.existsByShortCode(alias)) {
            throw new AliasUnavailableException(alias);
        }
        return alias;
    }

    private String generateUniqueCode() {
        for (int attempt = 0; attempt < MAX_GENERATION_ATTEMPTS; attempt++) {
            String candidate = codeGenerator.generate();
            if (!repository.existsByShortCode(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique short code after "
                + MAX_GENERATION_ATTEMPTS + " attempts");
    }

    /** Prevents redirect loops: a LinkLens link that points at another LinkLens link. */
    private void rejectSelfReferencingUrl(String url) {
        String ownHost = URI.create(properties.normalizedBaseUrl()).getHost();
        String targetHost = URI.create(url).getHost();
        if (ownHost != null && ownHost.equalsIgnoreCase(targetHost)) {
            throw new InvalidUrlException("URL must not point to LinkLens itself");
        }
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
