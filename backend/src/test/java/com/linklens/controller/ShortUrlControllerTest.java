package com.linklens.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.linklens.dto.ShortUrlResponse;
import com.linklens.exception.AliasUnavailableException;
import com.linklens.exception.ShortUrlNotFoundException;
import com.linklens.service.ShortUrlService;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ShortUrlController.class)
class ShortUrlControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ShortUrlService service;

    private static final ShortUrlResponse RESPONSE = new ShortUrlResponse(
            "abc1234", "http://localhost:8080/abc1234", "https://example.com",
            false, 0, Instant.parse("2026-01-01T00:00:00Z"), null);

    @Test
    void createReturns201WithLocationAndBody() throws Exception {
        when(service.create(any())).thenReturn(RESPONSE);

        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/urls/abc1234"))
                .andExpect(jsonPath("$.shortUrl").value("http://localhost:8080/abc1234"))
                .andExpect(jsonPath("$.createdAt").value("2026-01-01T00:00:00Z"));
    }

    @Test
    void createReturns400WithFieldErrorsForInvalidInput() throws Exception {
        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"ftp://example.com","customAlias":"no spaces!","expiresAt":"2000-01-01T00:00:00Z"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.url").exists())
                .andExpect(jsonPath("$.errors.customAlias").exists())
                .andExpect(jsonPath("$.errors.expiresAt").exists());
        verifyNoInteractions(service);
    }

    @Test
    void createReturns400ForMalformedJson() throws Exception {
        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createReturns409WhenAliasUnavailable() throws Exception {
        when(service.create(any())).thenThrow(new AliasUnavailableException("taken"));

        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://example.com\",\"customAlias\":\"taken\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Alias unavailable"));
    }

    @Test
    void getDetailsReturns404ForUnknownCode() throws Exception {
        when(service.getDetails("nope")).thenThrow(new ShortUrlNotFoundException("nope"));

        mockMvc.perform(get("/api/v1/urls/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}
