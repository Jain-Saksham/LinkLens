package com.linklens.service;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns raw request headers into the only facts we keep about a click. Pure functions, no state.
 */
public final class ClickSource {

    private static final int MAX_HOST_LENGTH = 255;

    /**
     * Link-preview fetchers (Slack, WhatsApp, Twitter cards...) and crawlers request a link without a
     * human clicking it. Counting them would inflate numbers the moment a link is pasted anywhere.
     */
    private static final Pattern BOT_USER_AGENT = Pattern.compile(
            "bot|crawl|spider|slurp|preview|facebookexternalhit|embedly",
            Pattern.CASE_INSENSITIVE);

    private ClickSource() {
    }

    /**
     * The referring host only ("news.ycombinator.com"), never the path or query, which can contain
     * search terms, IDs or tokens. Returns null for direct traffic, unparseable values, non-http
     * schemes, or referrals from LinkLens itself.
     */
    public static String referrerHost(String refererHeader, String ownHost) {
        if (refererHeader == null || refererHeader.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(refererHeader.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (host == null || scheme == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return null;
            }
            host = host.toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }
            if (host.equalsIgnoreCase(ownHost) || host.length() > MAX_HOST_LENGTH) {
                return null;
            }
            return host;
        } catch (Exception e) {
            return null;
        }
    }

    /** Used transiently to skip recording; the user agent itself is never stored. */
    public static boolean isLikelyBot(String userAgent) {
        return userAgent != null && BOT_USER_AGENT.matcher(userAgent).find();
    }
}
