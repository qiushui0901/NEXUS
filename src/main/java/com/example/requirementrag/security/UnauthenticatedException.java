package com.example.requirementrag.security;

/** 请求未提供已配置的 API 密钥时抛出的异常。 */
public class UnauthenticatedException extends RuntimeException {

    public UnauthenticatedException() {
        super("Missing or invalid API key");
    }
}
