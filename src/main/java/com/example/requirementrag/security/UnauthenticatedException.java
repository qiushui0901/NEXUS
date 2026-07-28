package com.example.requirementrag.security;

/** Raised when a request does not provide a configured API key. */
public class UnauthenticatedException extends RuntimeException {

    public UnauthenticatedException() {
        super("Missing or invalid API key");
    }
}
