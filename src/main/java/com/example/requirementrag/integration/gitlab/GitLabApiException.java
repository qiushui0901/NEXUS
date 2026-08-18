package com.example.requirementrag.integration.gitlab;

/** GitLab REST API 的稳定、安全错误。 */
public class GitLabApiException extends IllegalStateException {
    private final String code;

    public GitLabApiException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
