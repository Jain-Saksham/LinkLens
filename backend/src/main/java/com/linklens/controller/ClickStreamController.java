package com.linklens.controller;

import com.linklens.domain.ShortUrl;
import com.linklens.exception.ShortUrlNotFoundException;
import com.linklens.live.LiveClickRegistry;
import com.linklens.repository.ShortUrlRepository;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Live click counter as Server-Sent Events: one-way, auto-reconnecting, plain HTTP. */
@RestController
public class ClickStreamController {

    private final ShortUrlRepository shortUrls;
    private final LiveClickRegistry registry;

    public ClickStreamController(ShortUrlRepository shortUrls, LiveClickRegistry registry) {
        this.shortUrls = shortUrls;
        this.registry = registry;
    }

    @GetMapping(path = "/api/v1/urls/{shortCode}/clicks/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String shortCode) {
        ShortUrl link = shortUrls.findByShortCode(shortCode)
                .orElseThrow(() -> new ShortUrlNotFoundException(shortCode));
        return registry.subscribe(link.getId(), link.getClickCount());
    }
}
