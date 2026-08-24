package com.example.requirementrag.requirement.graph.document;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 规则跨窗口验证（默认）：两端证据片段都存在即确认（候选由规则生成，证据可回查）。 */
@Component
@ConditionalOnProperty(name = "app.rag.document-level.llm-enabled", havingValue = "false", matchIfMissing = true)
public class RuleCrossWindowVerifier implements CrossWindowVerifier {

    @Override
    public Verification verify(String source, String target, String relationType,
                               String sourceText, String targetText) {
        boolean confirmed = sourceText != null && !sourceText.isBlank()
                && targetText != null && !targetText.isBlank();
        return new Verification(confirmed, confirmed ? 0.9 : 0.0,
                confirmed ? "两端证据片段均可回查" : "缺少任一端证据片段");
    }
}