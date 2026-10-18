package com.linklens.exception;

public class ShortUrlExpiredException extends RuntimeException {

    public ShortUrlExpiredException(String shortCode) {
        super("Short URL '" + shortCode + "' has expired");
    }
}
