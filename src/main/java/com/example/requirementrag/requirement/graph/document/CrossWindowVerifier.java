package com.example.requirementrag.requirement.graph.document;

/** 跨窗口候选二次验证 SPI：只发送两端证据片段与必要摘要，禁止全文拼接。 */
public interface CrossWindowVerifier {
    Verification verify(String source, String target, String relationType,
                        String sourceText, String targetText);

    record Verification(boolean confirmed, double confidence, String reason) {
        public Verification {
            if (confidence < 0 || confidence > 1) throw new IllegalArgumentException("confidence 超出 [0,1]");
        }
    }
}