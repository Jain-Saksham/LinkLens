package com.linklens.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ClickSourceTest {

    @ParameterizedTest
    @CsvSource({
            "https://www.google.com/search?q=secret, google.com",
            "https://News.YCombinator.com/item?id=1, news.ycombinator.com",
            "http://t.co/abc, t.co",
            "https://sub.example.co.uk:8443/path#frag, sub.example.co.uk"
    })
    void keepsOnlyTheNormalisedHost(String referer, String expected) {
        assertThat(ClickSource.referrerHost(referer, "lnk.test")).isEqualTo(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "not a url", "android-app://com.slack/", "file:///etc/passwd", "https://lnk.test/abc"})
    void treatsMissingInvalidOrSelfReferrersAsDirect(String referer) {
        assertThat(ClickSource.referrerHost(referer, "lnk.test")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Slackbot-LinkExpanding 1.0 (+https://api.slack.com/robots)",
            "Twitterbot/1.0",
            "facebookexternalhit/1.1",
            "Mozilla/5.0 (compatible; Googlebot/2.1)",
            "WhatsApp/2.23 link preview"
    })
    void detectsCommonBotsAndPreviewFetchers(String userAgent) {
        assertThat(ClickSource.isLikelyBot(userAgent)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 Version/17.0 Safari/605.1.15",
            "curl/8.4.0"
    })
    void doesNotFlagBrowsersOrPlainHttpClients(String userAgent) {
        assertThat(ClickSource.isLikelyBot(userAgent)).isFalse();
    }
}
