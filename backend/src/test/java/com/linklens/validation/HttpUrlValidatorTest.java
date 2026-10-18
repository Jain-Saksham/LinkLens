package com.linklens.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HttpUrlValidatorTest {

    private final HttpUrlValidator validator = new HttpUrlValidator();

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com",
            "http://example.com/path?q=1#frag",
            "HTTPS://Example.com:8443/a/b",
            "https://sub.domain.co.uk/x",
            "http://localhost:3000"
    })
    void acceptsHttpUrls(String url) {
        assertThat(validator.isValid(url, null)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "example.com",
            "ftp://example.com/file",
            "javascript:alert(1)",
            "file:///etc/passwd",
            "https://",
            "http://exa mple.com",
            "not a url"
    })
    void rejectsNonHttpOrMalformedUrls(String url) {
        assertThat(validator.isValid(url, null)).isFalse();
    }
}
