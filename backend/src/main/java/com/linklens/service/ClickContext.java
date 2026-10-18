package com.linklens.service;

/** The raw request facts a redirect passes to the service. Neither value is stored as-is. */
public record ClickContext(String referer, String userAgent) {

    public static final ClickContext NONE = new ClickContext(null, null);
}
