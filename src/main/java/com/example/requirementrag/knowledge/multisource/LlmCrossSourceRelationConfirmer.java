package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.service.GenerationChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * 基于 LLM 的跨来源关系语义确认：对规则抽取出的关系做二次确认。
 *
 * <p>只把 LLM 明确判为「不成立」的关系标记为 false；未解析/超时/非法输出一律 fail-open
 * 返回 true（保留规则基线），并把原因放入返回值便于审计。
 */
@Component
public class LlmCrossSourceRelationConfirmer implements CrossSourceRelationConfirmer {

    private final ChatClient chatClient;
    private final MultiSourceKnowledgeProperties properties;
    private final RagProperties ragProperties;

    public LlmCrossSourceRelationConfirmer(ChatClient chatClient,
                                           MultiSourceKnowledgeProperties properties,
                                           RagProperties ragProperties) {
        this.chatClient = chatClient;
        this.properties = properties;
        this.ragProperties = ragProperties;
    }

    @Override
    public Confirmation confirm(ClaimRef source, String relationType, ClaimRef target, String evidence) {
        try {
            LlmConfirmationResult result = chatClient.prompt()
                    .system("""
                            你是跨来源知识关系审核员。判断“来源声明”与“目标需求声明”之间是否存在给定的关系。
                            只输出 JSON：{"confirmed": true/false, "reason": "简短中文理由"}。
                            只有在关系明显不成立时才返回 confirmed=false；不确定或证据不足时返回 confirmed=true 并在 reason 说明。
                            """)
                    .user("""
                            关系类型：%s
                            来源声明（%s）：%s
                            目标需求声明（%s）：%s
                            来源证据：%s
                            """.formatted(relationType, safe(source.sourceType()), safe(source.summary()),
                            safe(target.sourceType()), safe(target.summary()), safe(evidence)))
                    .options(GenerationChatOptions.forModel(resolveModel()))
                    .call()
                    .entity(LlmConfirmationResult.class);
            if (result == null || result.confirmed() == null) {
                return new Confirmation(true, "LLM 未返回有效判定，保留规则关系");
            }
            return new Confirmation(result.confirmed(), safe(result.reason()));
        }
        catch (RuntimeException exception) {
            return new Confirmation(true, "LLM 确认不可用，保留规则关系");
        }
    }

    private String resolveModel() {
        return properties.intentModel(ragProperties.llm().resolvedRoutingModel());
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    /** LLM 原始应答。 */
    private record LlmConfirmationResult(Boolean confirmed, String reason) {
    }
}