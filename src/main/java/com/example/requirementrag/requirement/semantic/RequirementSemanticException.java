package com.example.requirementrag.requirement.semantic;

/** Public-safe, stable error for requirement semantic annotation operations. */
public class RequirementSemanticException extends IllegalArgumentException {
    private final String code;

    public RequirementSemanticException(String code, String message) {
        super(message);
        this.code = code;
    }

    public RequirementSemanticException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
