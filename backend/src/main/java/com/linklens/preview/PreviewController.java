package com.linklens.preview;

import com.linklens.config.AppProperties;
import com.linklens.domain.ShortUrl;
import com.linklens.health.LinkHealthService;
import com.linklens.health.StoredHealth;
import com.linklens.repository.ShortUrlRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /{code}+}: shows where a link goes before anyone follows it.
 *
 * <p>Abuse bound: the page reuses the stored inspection while it's fresh, and concurrent viewers share
 * one in-flight check, so at most one outbound request per link per {@code previewMaxAge}, however
 * many people open the preview. It also never follows the link for the viewer or counts as a click.
 */
@RestController
public class PreviewController {

    static final Duration WAIT_FOR_CHECK = Duration.ofMillis(2500);
    /** No scripts at all; inline styles only; can't be framed (clickjacking). */
    static final String CSP = "default-src 'none'; style-src 'unsafe-inline'; img-src 'self' data:; "
            + "base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

    private final ShortUrlRepository shortUrls;
    private final LinkHealthService health;
    private final AppProperties properties;
    private final Clock clock;

    public PreviewController(ShortUrlRepository shortUrls, LinkHealthService health, AppProperties properties, Clock clock) {
        this.shortUrls = shortUrls;
        this.health = health;
        this.properties = properties;
        this.clock = clock;
    }

    @GetMapping(path = "/{shortCode:[A-Za-z0-9_-]{3,32}}+", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> preview(@PathVariable String shortCode) {
        Optional<ShortUrl> found = shortUrls.findByShortCode(shortCode);
        if (found.isEmpty()) {
            return html(HttpStatus.NOT_FOUND, PreviewPage.notFound(shortCode));
        }
        ShortUrl link = found.get();
        Instant now = Instant.now(clock);
        // Expired links are not inspected: there is nothing left to visit.
        StoredHealth state = link.isExpiredAt(now)
                ? health.storedHealth(link.getId()).orElse(null)
                : health.freshForPreview(link, WAIT_FOR_CHECK).orElse(null);
        String shortUrl = properties.normalizedBaseUrl() + "/" + link.getShortCode();
        return html(link.isExpiredAt(now) ? HttpStatus.GONE : HttpStatus.OK,
                PreviewPage.render(link, shortUrl, state, now));
    }

    private static ResponseEntity<String> html(HttpStatus status, String body) {
        return ResponseEntity.status(status)
                .contentType(new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8))
                .cacheControl(CacheControl.noStore())
                .header("Content-Security-Policy", CSP)
                .header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer")
                .body(body);
    }
}
