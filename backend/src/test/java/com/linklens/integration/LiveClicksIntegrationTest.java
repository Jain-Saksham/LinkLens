package com.linklens.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.linklens.support.IntegrationTest;
import com.linklens.support.TestLinks;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;

@IntegrationTest
class LiveClicksIntegrationTest {

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate rest;

    @Test
    void streamSendsTheCurrentCountThenEachUpdateAfterClicks() throws Exception {
        String alias = TestLinks.uniqueAlias("live");
        rest.postForEntity("/api/v1/urls", Map.of("url", "https://example.com/live", "customAlias", alias), String.class);

        BlockingQueue<String> events = new LinkedBlockingQueue<>();
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/urls/" + alias + "/clicks/stream"))
                .header("Accept", "text/event-stream").build();
        CompletableFuture<Void> stream = client.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                .thenAccept(response -> response.body().filter(l -> l.startsWith("data:")).forEach(events::add));
        try {
            assertThat(events.poll(5, TimeUnit.SECONDS)).contains("\"clickCount\":0");

            TestLinks.noRedirects(rest).getForEntity("/" + alias, Void.class);
            TestLinks.noRedirects(rest).getForEntity("/" + alias, Void.class);

            // Pushed after the click batch commits (flush every 200ms); both clicks may land in one batch.
            String update = events.poll(5, TimeUnit.SECONDS);
            assertThat(update).matches(".*\"clickCount\":[12].*");
            if (!update.contains("\"clickCount\":2")) {
                assertThat(events.poll(5, TimeUnit.SECONDS)).contains("\"clickCount\":2");
            }
        } finally {
            stream.cancel(true);
            client.shutdownNow();
        }
    }

    @Test
    void unknownLinkHasNoStream() {
        assertThat(rest.getForEntity("/api/v1/urls/nope-nope-nope/clicks/stream", String.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
