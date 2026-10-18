package com.linklens.inspection;

import com.linklens.config.HealthProperties;
import com.linklens.inspection.InspectionResult.Hop;
import com.linklens.inspection.InspectionResult.Outcome;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.net.ssl.SSLException;
import org.apache.hc.client5.http.HttpHostConnectException;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

/**
 * Visits a destination the way a careful browser would, and reports what it found.
 *
 * <ul>
 *   <li><b>SSRF-safe</b>: every hop's addresses are validated at connect time ({@link SafeDnsResolver});
 *       IP-literal hosts are checked up front too.</li>
 *   <li><b>Bounded</b>: connect/read timeouts per hop, a hard deadline for the whole visit, at most
 *       {@code maxRedirects} hops, at most {@code maxBodyBytes} read (only from HTML), no retries.</li>
 *   <li><b>Anonymous</b>: no cookies, no credentials, an honest bot User-Agent.</li>
 * </ul>
 */
@Component
public class DestinationInspector {

    static final String USER_AGENT = "LinkLensBot/1.0 (+link health check)";

    private final CloseableHttpClient http;
    private final AddressPolicy policy;
    private final HealthProperties props;
    private final Clock clock;

    public DestinationInspector(HealthProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        this.policy = new AddressPolicy(props.allowLoopback());
        this.http = HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(new SafeDnsResolver(policy))
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(Timeout.of(props.connectTimeout()))
                                .setSocketTimeout(Timeout.of(props.readTimeout()))
                                .build())
                        .setMaxConnTotal(32)
                        .setMaxConnPerRoute(4)
                        .build())
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setResponseTimeout(Timeout.of(props.readTimeout()))
                        .build())
                .disableRedirectHandling()   // followed manually so each hop is recorded and checked
                .disableAutomaticRetries()
                .disableCookieManagement()
                .disableAuthCaching()
                .setUserAgent(USER_AGENT)
                .build();
    }

    public InspectionResult inspect(String url) {
        Instant start = Instant.now(clock);
        long deadline = System.nanoTime() + props.totalTimeout().toNanos();
        List<Hop> chain = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        URI current;
        try {
            current = new URI(url);
        } catch (URISyntaxException e) {
            return result(Outcome.INVALID_REDIRECT, null, chain, null, start, "Invalid URL");
        }

        for (int hop = 0; ; hop++) {
            String scheme = current.getScheme() == null ? "" : current.getScheme().toLowerCase(Locale.ROOT);
            if ((!scheme.equals("http") && !scheme.equals("https")) || current.getHost() == null) {
                chain.add(new Hop(current.toString(), null));
                return result(Outcome.INVALID_REDIRECT, null, chain, null, start, "Redirects to a non-web address");
            }
            if (!visited.add(normalize(current))) {
                chain.add(new Hop(current.toString(), null));
                return result(Outcome.REDIRECT_LOOP, null, chain, null, start, "Redirects in a loop");
            }
            if (hop > props.maxRedirects()) {
                return result(Outcome.TOO_MANY_REDIRECTS, null, chain, null, start,
                        "More than " + props.maxRedirects() + " redirects");
            }
            if (isBlockedLiteral(current.getHost())) {
                chain.add(new Hop(current.toString(), null));
                return result(Outcome.BLOCKED, null, chain, null, start, "Points to a private network address");
            }
            if (System.nanoTime() > deadline) {
                return result(Outcome.TIMEOUT, null, chain, null, start, "Took too long to respond");
            }

            final URI target = current;
            Step step;
            try {
                HttpGet get = new HttpGet(target);
                get.setHeader("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.5");
                get.setHeader("Accept-Language", "en");
                step = http.execute(get, response -> {
                    int status = response.getCode();
                    Header location = response.getFirstHeader("Location");
                    String title = status >= 200 && status < 300 ? readTitle(response.getEntity(), deadline) : null;
                    return new Step(status, location == null ? null : location.getValue(), title);
                });
            } catch (SafeDnsResolver.BlockedAddressException e) {
                chain.add(new Hop(target.toString(), null));
                return result(Outcome.BLOCKED, null, chain, null, start, "Points to a private network address");
            } catch (UnknownHostException e) {
                chain.add(new Hop(target.toString(), null));
                return result(Outcome.DNS_FAILURE, null, chain, null, start, "The domain no longer exists");
            } catch (SocketTimeoutException e) { // includes connect timeouts
                chain.add(new Hop(target.toString(), null));
                return result(Outcome.TIMEOUT, null, chain, null, start, "Took too long to respond");
            } catch (SSLException e) {
                chain.add(new Hop(target.toString(), null));
                return result(Outcome.TLS_ERROR, null, chain, null, start, "Secure connection failed (certificate or TLS error)");
            } catch (HttpHostConnectException e) {
                chain.add(new Hop(target.toString(), null));
                return result(Outcome.UNREACHABLE, null, chain, null, start, "The server refused the connection");
            } catch (IOException e) {
                chain.add(new Hop(target.toString(), null));
                return result(Outcome.UNREACHABLE, null, chain, null, start, "Could not connect");
            }

            chain.add(new Hop(target.toString(), step.status()));
            if (step.status() >= 300 && step.status() < 400 && step.location() != null) {
                try {
                    current = target.resolve(new URI(step.location().trim()));
                } catch (URISyntaxException | IllegalArgumentException e) {
                    return result(Outcome.INVALID_REDIRECT, step.status(), chain, null, start, "Redirects to an invalid address");
                }
                continue;
            }
            return result(classify(step.status()), step.status(), chain, step.title(), start, describe(step.status()));
        }
    }

    private record Step(int status, String location, String title) {
    }

    static Outcome classify(int status) {
        if (status >= 200 && status < 300) return Outcome.OK;
        if (status == 404 || status == 410) return Outcome.NOT_FOUND;
        // 401/403/429 are bot walls or logins; >599 are non-standard anti-bot codes (LinkedIn sends 999).
        if (status == 401 || status == 403 || status == 429 || status > 599) return Outcome.ACCESS_RESTRICTED;
        if (status >= 500) return Outcome.SERVER_ERROR;
        return Outcome.CLIENT_ERROR; // other 4xx, or a 3xx without Location
    }

    private static String describe(int status) {
        return switch (classify(status)) {
            case OK -> null;
            case NOT_FOUND -> status == 410 ? "The page has been removed (410)" : "Page not found (404)";
            case ACCESS_RESTRICTED -> "The site blocks automated checks (" + status + "); it may still work in a browser";
            case SERVER_ERROR -> "The server is failing (" + status + ")";
            default -> "The server rejected the request (" + status + ")";
        };
    }

    /** Reads at most maxBodyBytes of an HTML body, stopping at the deadline; never touches non-HTML. */
    private String readTitle(HttpEntity entity, long deadline) throws IOException {
        if (entity == null) {
            return null;
        }
        ContentType type = entity.getContentType() == null ? null : ContentType.parseLenient(entity.getContentType());
        String mime = type == null ? "" : type.getMimeType().toLowerCase(Locale.ROOT);
        if (!mime.contains("html")) {
            return null;
        }
        Charset charset = type.getCharset() != null ? type.getCharset() : StandardCharsets.UTF_8;
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        // Sliding window = last few bytes of the previous read + this read, so "</title" split across
        // two reads is still found, and each byte is scanned once (no re-scanning the whole body).
        byte[] window = new byte[CLOSING_TITLE.length - 1 + buffer.length];
        int carry = 0;
        try (InputStream in = entity.getContent()) {
            int read;
            while (body.size() < props.maxBodyBytes() && System.nanoTime() < deadline
                    && (read = in.read(buffer, 0, Math.min(buffer.length, props.maxBodyBytes() - body.size()))) != -1) {
                body.write(buffer, 0, read);
                System.arraycopy(buffer, 0, window, carry, read);
                int filled = carry + read;
                if (containsClosingTitle(window, filled)) {
                    break; // got what we need; don't download the rest of the page
                }
                carry = Math.min(CLOSING_TITLE.length - 1, filled);
                System.arraycopy(window, filled - carry, window, 0, carry);
            }
        }
        return HtmlTitle.extract(body.toString(charset));
    }

    private static final byte[] CLOSING_TITLE = "</title".getBytes(StandardCharsets.US_ASCII);

    /** Case-insensitive search for "</title" in {@code bytes[0, length)}. */
    static boolean containsClosingTitle(byte[] bytes, int length) {
        outer:
        for (int i = 0; i <= length - CLOSING_TITLE.length; i++) {
            for (int j = 0; j < CLOSING_TITLE.length; j++) {
                if (Character.toLowerCase((char) bytes[i + j]) != CLOSING_TITLE[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private boolean isBlockedLiteral(String host) {
        String h = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (!h.matches("[0-9.]+") && !h.contains(":")) {
            return false; // a name: checked by SafeDnsResolver at connect time
        }
        try {
            return !policy.isAllowed(InetAddress.getByName(h)); // literal: parsed locally, no DNS lookup
        } catch (UnknownHostException e) {
            return true;
        }
    }

    private static String normalize(URI uri) {
        return uri.normalize().toString().replaceFirst("#.*$", "").toLowerCase(Locale.ROOT);
    }

    private InspectionResult result(Outcome outcome, Integer status, List<Hop> chain, String title, Instant start, String detail) {
        long ms = Duration.between(start, Instant.now(clock)).toMillis();
        return new InspectionResult(outcome, status, List.copyOf(chain), title, start, ms, detail);
    }

    @PreDestroy
    void close() throws IOException {
        http.close();
    }
}
