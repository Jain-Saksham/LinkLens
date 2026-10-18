package com.linklens.controller;

import com.linklens.dto.AnalyticsResponse;
import com.linklens.dto.CreateShortUrlRequest;
import com.linklens.dto.ShortUrlResponse;
import com.linklens.service.AnalyticsService;
import com.linklens.service.ShortUrlService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/urls")
public class ShortUrlController {

    private final ShortUrlService service;
    private final AnalyticsService analyticsService;

    public ShortUrlController(ShortUrlService service, AnalyticsService analyticsService) {
        this.service = service;
        this.analyticsService = analyticsService;
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

    @GetMapping("/{shortCode}/analytics")
    public AnalyticsResponse getAnalytics(@PathVariable String shortCode,
                                          @RequestParam(defaultValue = "30") @Min(1) @Max(AnalyticsService.MAX_PERIOD_DAYS) int days) {
        return analyticsService.analyticsFor(shortCode, days);
    }
}
