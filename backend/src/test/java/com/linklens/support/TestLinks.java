package com.linklens.support;

import java.util.UUID;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings.Redirects;
import org.springframework.boot.test.web.client.TestRestTemplate;

/** Helpers shared by integration tests. */
public final class TestLinks {

    public static final String CACHE_KEY_PREFIX = "linklens:v1:link:";

    private TestLinks() {
    }

    /** Unique per call, so tests sharing one database and cache never collide. */
    public static String uniqueAlias(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Observe the 302 itself instead of following it to the (external) target. */
    public static TestRestTemplate noRedirects(TestRestTemplate rest) {
        return rest.withRedirects(Redirects.DONT_FOLLOW);
    }
}
