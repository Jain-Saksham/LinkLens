package com.linklens.inspection;

import static org.assertj.core.api.Assertions.assertThat;

import com.linklens.inspection.InspectionResult.Hop;
import com.linklens.inspection.InspectionResult.Outcome;
import com.linklens.support.TestHealthProps;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Real HTTP against an in-process server on 127.0.0.1 (loopback allowed for this test only). */
class DestinationInspectorTest {

    private static HttpServer server;
    private static String base;
    private static final AtomicInteger bodyBytesServed = new AtomicInteger();

    private final DestinationInspector inspector =
            new DestinationInspector(TestHealthProps.withLoopback(true), Clock.systemUTC());

    @BeforeAll
    static void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", ex -> html(ex, 200, "<html><head><title>Hello &amp; welcome</title></head></html>"));
        server.createContext("/missing", ex -> html(ex, 404, "<title>Not Found</title>"));
        server.createContext("/gone", ex -> html(ex, 410, ""));
        server.createContext("/forbidden", ex -> html(ex, 403, "<title>Just a moment...</title>"));
        server.createContext("/error", ex -> html(ex, 503, ""));
        server.createContext("/linkedin-style", ex -> html(ex, 999, ""));
        server.createContext("/hop1", ex -> redirect(ex, 301, "/hop2"));
        server.createContext("/hop2", ex -> redirect(ex, 302, base + "/ok"));
        server.createContext("/loop-a", ex -> redirect(ex, 302, "/loop-b"));
        server.createContext("/loop-b", ex -> redirect(ex, 302, "/loop-a"));
        server.createContext("/chain", ex -> {
            int n = Integer.parseInt(ex.getRequestURI().getQuery().substring(2));
            redirect(ex, 302, "/chain?n=" + (n + 1));
        });
        server.createContext("/to-metadata", ex -> redirect(ex, 302, "http://169.254.169.254/latest/meta-data/"));
        server.createContext("/to-ftp", ex -> redirect(ex, 302, "ftp://example.com/file"));
        server.createContext("/slow", ex -> {
            sleep(2_000);
            html(ex, 200, "<title>late</title>");
        });
        server.createContext("/late-title", ex -> {
            // ~400 KB of inline CSS before the title, like many React sites; the title straddles reads.
            String css = "<style>" + ".a{color:red}".repeat(30_000) + "</style>";
            html(ex, 200, "<html><head>" + css + "<title>Found it late</title></head><body>" + "x".repeat(500_000) + "</body></html>");
        });
        server.createContext("/json", ex -> send(ex, 200, "application/json", "{\"title\":\"no\"}"));
        server.createContext("/huge", ex -> {
            ex.getResponseHeaders().set("Content-Type", "text/html");
            ex.sendResponseHeaders(200, 0);
            try (OutputStream out = ex.getResponseBody()) {
                byte[] chunk = "<p>filler</p>".repeat(1000).getBytes(StandardCharsets.UTF_8);
                for (int i = 0; i < 200; i++) { // ~2.6 MB, no title
                    out.write(chunk);
                    bodyBytesServed.addAndGet(chunk.length);
                }
            } catch (IOException ignored) {
                // client stopped reading: expected
            }
        });
        server.setExecutor(Executors.newCachedThreadPool()); // default is one thread: /slow would block everyone
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    @Test
    void healthyPageWithTitle() {
        InspectionResult r = inspector.inspect(base + "/ok");
        assertThat(r.outcome()).isEqualTo(Outcome.OK);
        assertThat(r.httpStatus()).isEqualTo(200);
        assertThat(r.title()).isEqualTo("Hello & welcome");
        assertThat(r.finalIsHttps()).isFalse();
    }

    @Test
    void findsTitlesBuriedDeepInTheHeadButStopsReadingRightAfter() {
        InspectionResult r = inspector.inspect(base + "/late-title");
        assertThat(r.title()).isEqualTo("Found it late");
    }

    @Test
    void closingTitleIsFoundCaseInsensitively() {
        byte[] bytes = "abc</TiTlE>".getBytes(StandardCharsets.US_ASCII);
        assertThat(DestinationInspector.containsClosingTitle(bytes, bytes.length)).isTrue();
        assertThat(DestinationInspector.containsClosingTitle(bytes, 6)).isFalse(); // cut before the tag ends
    }

    @Test
    void followsRedirectsAndRecordsTheChain() {
        InspectionResult r = inspector.inspect(base + "/hop1");
        assertThat(r.outcome()).isEqualTo(Outcome.OK);
        assertThat(r.redirectChain()).containsExactly(
                new Hop(base + "/hop1", 301), new Hop(base + "/hop2", 302), new Hop(base + "/ok", 200));
        assertThat(r.finalUrl()).isEqualTo(base + "/ok");
    }

    @Test
    void classifiesErrorStatuses() {
        assertThat(inspector.inspect(base + "/missing").outcome()).isEqualTo(Outcome.NOT_FOUND);
        assertThat(inspector.inspect(base + "/gone").outcome()).isEqualTo(Outcome.NOT_FOUND);
        assertThat(inspector.inspect(base + "/forbidden").outcome()).isEqualTo(Outcome.ACCESS_RESTRICTED);
        assertThat(inspector.inspect(base + "/error").outcome()).isEqualTo(Outcome.SERVER_ERROR);
        assertThat(inspector.inspect(base + "/linkedin-style").outcome()).isEqualTo(Outcome.ACCESS_RESTRICTED);
    }

    @Test
    void detectsLoopsAndTooManyRedirects() {
        assertThat(inspector.inspect(base + "/loop-a").outcome()).isEqualTo(Outcome.REDIRECT_LOOP);
        InspectionResult tooMany = inspector.inspect(base + "/chain?n=0");
        assertThat(tooMany.outcome()).isEqualTo(Outcome.TOO_MANY_REDIRECTS);
        assertThat(tooMany.redirectChain()).hasSize(6); // the original + 5 followed redirects
    }

    @Test
    void refusesToFollowARedirectIntoThePrivateNetwork() {
        InspectionResult r = inspector.inspect(base + "/to-metadata");
        assertThat(r.outcome()).isEqualTo(Outcome.BLOCKED);
        assertThat(r.redirectChain().getLast().url()).contains("169.254.169.254");
        assertThat(r.redirectChain().getLast().status()).isNull(); // never requested
    }

    @Test
    void refusesNonWebRedirectTargets() {
        assertThat(inspector.inspect(base + "/to-ftp").outcome()).isEqualTo(Outcome.INVALID_REDIRECT);
    }

    @Test
    void loopbackIsBlockedWhenNotExplicitlyAllowed() {
        DestinationInspector strict = new DestinationInspector(TestHealthProps.withLoopback(false), Clock.systemUTC());
        assertThat(strict.inspect(base + "/ok").outcome()).isEqualTo(Outcome.BLOCKED);
        assertThat(strict.inspect("http://localhost:" + server.getAddress().getPort() + "/ok").outcome())
                .isEqualTo(Outcome.BLOCKED); // via DNS resolution, not just the literal check
    }

    @Test
    void slowServersTimeOut() {
        assertThat(inspector.inspect(base + "/slow").outcome()).isEqualTo(Outcome.TIMEOUT);
    }

    @Test
    void unreachableAndUnknownHosts() {
        assertThat(inspector.inspect("http://127.0.0.1:1/").outcome()).isEqualTo(Outcome.UNREACHABLE);
        assertThat(inspector.inspect("http://no-such-host.invalid/").outcome()).isEqualTo(Outcome.DNS_FAILURE);
    }

    @Test
    void neverReadsNonHtmlAndCapsHtmlBodies() {
        assertThat(inspector.inspect(base + "/json").title()).isNull();

        InspectionResult huge = inspector.inspect(base + "/huge");
        assertThat(huge.outcome()).isEqualTo(Outcome.OK);
        assertThat(huge.title()).isNull();
    }

    private static void redirect(HttpExchange ex, int status, String location) throws IOException {
        ex.getResponseHeaders().set("Location", location);
        ex.sendResponseHeaders(status, -1);
        ex.close();
    }

    private static void html(HttpExchange ex, int status, String body) throws IOException {
        send(ex, status, "text/html; charset=utf-8", body);
    }

    private static void send(HttpExchange ex, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        }
        ex.close();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
