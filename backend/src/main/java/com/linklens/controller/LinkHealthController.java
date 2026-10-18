package com.linklens.controller;

import com.linklens.dto.LinkHealthResponse;
import com.linklens.health.LinkHealthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/urls/{shortCode}/health")
public class LinkHealthController {

    private final LinkHealthService service;

    public LinkHealthController(LinkHealthService service) {
        this.service = service;
    }

    @GetMapping
    public LinkHealthResponse get(@PathVariable String shortCode) {
        return service.getHealth(shortCode);
    }

    /** Rate limited (it makes an outbound request) and subject to a per-link cooldown. */
    @PostMapping("/check")
    public LinkHealthResponse checkNow(@PathVariable String shortCode) {
        return service.checkNow(shortCode);
    }
}
