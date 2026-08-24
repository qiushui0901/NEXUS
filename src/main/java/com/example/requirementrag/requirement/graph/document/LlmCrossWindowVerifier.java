package com.example.requirementrag.requirement.graph.document;

import com.example.requirementrag.requirement.graph.RequirementGraphProperties;
import com.example.requirementrag.service.GenerationChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * LLM 跨窗口候选二次验证（Phase 3 验证接入模型）。
 *
 * <p>只发送两端证据片段、关系类型与必要摘要；LLM 只能确认/拒绝候选，
 * 不能创建新实体或伪造证据。失败/非法输出按未确认处理（不发布）。
 */
@Component
@ConditionalOnProperty(name = "app.rag.document-level.llm-enabled", havingValue = "true")
public class LlmCrossWindowVerifier implements CrossWindowVerifier {

    private final ChatClient chatClient;
    private final RequirementGraphProperties graphProperties;

    public LlmCrossWindowVerifier(ChatClient chatClient, RequirementGraphProperties graphProperties) {
        this.chatClient = chatClient;
        this.graphProperties = graphProperties;
    }

    @Override
    public Verification verify(String source, String target, String relationType,
                               String sourceText, String targetText) {
        if (sourceText == null || sourceText.isBlank() || targetText == null || targetText.isBlank()) {
            return new Verification(false, 0.0, "缺少任一端证据片段");
        }
        try {
            VerdictResult result = chatClient.prompt()
                    .system("""
                            你是跨窗口关系验证器。根据给定的两端证据片段，判断候选关系是否成立。
                            只能回答 confirmed=true/false，不要修改实体名，不要引用不存在的证据。
                            输出 JSON：{"confirmed":true,"confidence":0.8,"reason":"简短理由"}。
                            """)
                    .user("候选关系：" + source + " --" + relationType + "--> " + target
                            + "\n源证据：\n" + sourceText
                            + "\n目标证据：\n" + targetText)
                    .options(GenerationChatOptions.forModel(resolveModel()))
                    .call()
                    .entity(VerdictResult.class);
            if (result == null || result.confirmed() == null) {
                return new Verification(false, 0.0, "LLM 未返回有效判定");
            }
            double confidence = result.confidence() == null ? 0.5 : result.confidence();
            return new Verification(result.confirmed(), confidence, result.reason() == null ? "" : result.reason());
        } catch (RuntimeException exception) {
            return new Verification(false, 0.0, "LLM 验证失败，按未确认处理");
        }
    }

    private String resolveModel() {
        return graphProperties.extractionModel() == null || graphProperties.extractionModel().isBlank()
                ? "deepseek-v4-flash" : graphProperties.extractionModel();
    }

    private record VerdictResult(Boolean confirmed, Double confidence, String reason) {
    }
}