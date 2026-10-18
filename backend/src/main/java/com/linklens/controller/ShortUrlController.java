package com.linklens.controller;

import com.linklens.dto.CreateShortUrlRequest;
import com.linklens.dto.ShortUrlResponse;
import com.linklens.service.ShortUrlService;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/urls")
public class ShortUrlController {

    private final ShortUrlService service;

    public ShortUrlController(ShortUrlService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ShortUrlResponse> create(@Valid @RequestBody CreateShortUrlRequest request) {
        ShortUrlResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/urls/" + created.shortCode())).body(created);
    }

    @GetMapping("/{shortCode}")
    public ShortUrlResponse getDetails(@PathVariable String shortCode) {
        return service.getDetails(shortCode);
    }
}
