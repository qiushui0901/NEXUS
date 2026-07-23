package com.example.requirementrag.web;

/** 认证或授权失败时抛出。 */
public class AccessDeniedException extends RuntimeException {

    public AccessDeniedException(String message) {
        super(message);
    }
}
