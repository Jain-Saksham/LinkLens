package com.linklens.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.linklens.dto.LinkHealthResponse;
import com.linklens.exception.ShortUrlNotFoundException;
import com.linklens.health.LinkHealthService;
import com.linklens.ratelimit.RateLimiter;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(LinkHealthController.class)
@Import(RateLimiter.class)
class LinkHealthControllerTest {

    private static final LinkHealthResponse HEALTHY = new LinkHealthResponse("HEALTHY", null, "ok", 200,
            "https://example.com", "Example", true, List.of(), 0, null, null, null);

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private LinkHealthService service;

    @Test
    void returnsHealthAnd404ForUnknownLinks() throws Exception {
        when(service.getHealth("abc")).thenReturn(HEALTHY);
        when(service.getHealth("nope")).thenThrow(new ShortUrlNotFoundException("nope"));

        mockMvc.perform(get("/api/v1/urls/abc/health")).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("HEALTHY"));
        mockMvc.perform(get("/api/v1/urls/nope/health")).andExpect(status().isNotFound());
    }

    @Test
    void onDemandChecksAreRateLimitedBecauseEachOneFetchesAThirdPartySite() throws Exception {
        when(service.checkNow("abc")).thenReturn(HEALTHY);
        for (int i = 0; i < 20; i++) {
            mockMvc.perform(check("10.7.7.7")).andExpect(status().isOk());
        }
        mockMvc.perform(check("10.7.7.7")).andExpect(status().isTooManyRequests());
        mockMvc.perform(get("/api/v1/urls/abc/health").with(r -> {
            r.setRemoteAddr("10.7.7.7");
            return r;
        })).andExpect(status().isOk()); // reads are never limited
    }

    private static MockHttpServletRequestBuilder check(String ip) {
        return post("/api/v1/urls/abc/health/check").with(r -> {
            r.setRemoteAddr(ip);
            return r;
        });
    }
}
