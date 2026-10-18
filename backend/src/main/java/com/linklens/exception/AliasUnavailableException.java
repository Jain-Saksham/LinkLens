package com.linklens.exception;

/** The requested custom alias is already in use or reserved. */
public class AliasUnavailableException extends RuntimeException {

    public AliasUnavailableException(String alias) {
        super("Alias '" + alias + "' is not available");
    }
}
