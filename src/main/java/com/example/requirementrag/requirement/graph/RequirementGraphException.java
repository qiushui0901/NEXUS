package com.example.requirementrag.requirement.graph;

/** Public-safe, stable error for requirement graph operations. */
public class RequirementGraphException extends IllegalArgumentException {
    private final String code;

    public RequirementGraphException(String code, String message) {
        super(message);
        this.code = code;
    }

    public RequirementGraphException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
