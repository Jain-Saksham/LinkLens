package com.linklens.controller;

import com.linklens.service.ShortUrlService;
import java.net.URI;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RedirectController {

    private final ShortUrlService service;

    public RedirectController(ShortUrlService service) {
        this.service = service;
    }

    /**
     * 302 (not 301): browsers cache 301s permanently, which would skip this endpoint on repeat
     * visits, so clicks would go uncounted and expiration couldn't be enforced.
     * The regex keeps this root-level route from capturing paths like /api or /actuator.
     */
    @GetMapping("/{shortCode:[A-Za-z0-9_-]{3,32}}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode) {
        String target = service.resolveAndRecordClick(shortCode);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(target))
                .cacheControl(CacheControl.noStore())
                .build();
    }
}
