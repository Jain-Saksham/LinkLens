package com.linklens.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.linklens.exception.ShortUrlExpiredException;
import com.linklens.exception.ShortUrlNotFoundException;
import com.linklens.ratelimit.RateLimiter;
import com.linklens.service.ClickContext;
import com.linklens.service.ShortUrlService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RedirectController.class)
@Import(RateLimiter.class)
class RedirectControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ShortUrlService service;

    @Test
    void redirectsWith302AndNoStore() throws Exception {
        when(service.resolveAndRecordClick(eq("abc1234"), any())).thenReturn("https://example.com/target");

        mockMvc.perform(get("/abc1234"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/target"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void passesRefererAndUserAgentToTheService() throws Exception {
        when(service.resolveAndRecordClick(eq("abc1234"), any())).thenReturn("https://example.com");

        mockMvc.perform(get("/abc1234").header("Referer", "https://t.co/x").header("User-Agent", "Mozilla/5.0"));

        verify(service).resolveAndRecordClick("abc1234", new ClickContext("https://t.co/x", "Mozilla/5.0"));
    }

    @Test
    void returns404ForUnknownCode() throws Exception {
        when(service.resolveAndRecordClick(eq("missing"), any())).thenThrow(new ShortUrlNotFoundException("missing"));

        mockMvc.perform(get("/missing")).andExpect(status().isNotFound());
    }

    @Test
    void returns410ForExpiredCode() throws Exception {
        when(service.resolveAndRecordClick(eq("old1234"), any())).thenThrow(new ShortUrlExpiredException("old1234"));

        mockMvc.perform(get("/old1234")).andExpect(status().isGone());
    }

    @Test
    void pathsOutsideTheCodePatternAreNotTreatedAsCodes() throws Exception {
        mockMvc.perform(get("/ab")).andExpect(status().isNotFound());           // too short
        mockMvc.perform(get("/has.dot")).andExpect(status().isNotFound());      // invalid char
    }
}
