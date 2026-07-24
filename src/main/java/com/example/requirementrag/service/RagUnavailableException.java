package com.example.requirementrag.service;

import com.example.requirementrag.model.RagWarning;
import java.util.List;

/** 核心检索阶段失败且没有任何可用证据。 */
public class RagUnavailableException extends RuntimeException {
    private final List<RagWarning> warnings;

    public RagUnavailableException(List<RagWarning> warnings) {
        super("RAG 核心检索暂时不可用，请稍后重试");
        this.warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public List<RagWarning> warnings() {
        return warnings;
    }
}
