package com.linklens.exception;

/** A syntactically valid URL that violates a business rule (e.g. it points back at LinkLens). */
public class InvalidUrlException extends RuntimeException {

    public InvalidUrlException(String message) {
        super(message);
    }
}
