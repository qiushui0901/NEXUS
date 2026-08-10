package com.example.requirementrag.security;

/** 请求无法建立可信身份时抛出的异常（缺身份头、默认管理员被禁等）。 */
public class UnauthenticatedException extends RuntimeException {

    public UnauthenticatedException() {
        super("Missing or invalid API key");
    }

    public UnauthenticatedException(String message) {
        super(message);
    }
}
