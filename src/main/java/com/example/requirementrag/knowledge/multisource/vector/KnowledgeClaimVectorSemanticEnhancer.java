package com.example.requirementrag.knowledge.multisource.vector;

import com.example.requirementrag.service.GenerationChatOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 为语义块生成召回辅助文本。LLM 输出只增强召回，不作为事实来源；原始结构化 Claim
 * 始终跟随块写入并在命中后回 SQLite 水化。
 */
@Component
public class KnowledgeClaimVectorSemanticEnhancer {

    private static final Logger LOGGER = LoggerFactory.getLogger(KnowledgeClaimVectorSemanticEnhancer.class);
    private static final int MAX_INPUT_CHARS = 16_000;
    private static final int MAX_OUTPUT_CHARS = 4_000;

    private final ChatClient chatClient;
    private final KnowledgeClaimVectorProperties properties;

    public KnowledgeClaimVectorSemanticEnhancer(ChatClient chatClient,
                                                KnowledgeClaimVectorProperties properties) {
        this.chatClient = chatClient;
        this.properties = properties;
    }

    /**
     * 返回面向语义检索的摘要/同义表达。不可用或失败时返回空，由调用方使用确定性块文本。
     */
    public Optional<String> enhance(String projectId, String businessVersion,
                                    String sourceType, String groupName, String deterministicText) {
        if (!properties.semanticEnhancementEnabled() || chatClient == null
                || properties.semanticEnhancementModel().isBlank()
                || deterministicText == null || deterministicText.isBlank()) {
            return Optional.empty();
        }
        String input = deterministicText.length() <= MAX_INPUT_CHARS
                ? deterministicText : deterministicText.substring(0, MAX_INPUT_CHARS);
        try {
            String result = chatClient.prompt()
                    .system("""
                            你是知识检索索引增强器。请为给定的需求、QA、测试或数值表语义块生成简洁的中文检索摘要，
                            可补充中英文同义词、业务术语和用户可能的问法。只能改写和归纳输入内容，不能新增输入中不存在的
                            数值、版本、状态、代码行为或结论。只返回纯文本，不要 JSON、Markdown 标题或免责声明。
                            该文本仅用于向量召回，事实必须回到原始 Claim 和 Evidence 校验。
                            """)
                    .user("项目: " + safe(projectId) + "\n业务版本: " + safe(businessVersion)
                            + "\n来源类型: " + safe(sourceType) + "\n模块或表: " + safe(groupName)
                            + "\n语义块:\n" + input)
                    .options(GenerationChatOptions.forModel(properties.semanticEnhancementModel()))
                    .call()
                    .content();
            if (result == null || result.isBlank()) return Optional.empty();
            String normalized = result.trim();
            return Optional.of(normalized.length() <= MAX_OUTPUT_CHARS
                    ? normalized : normalized.substring(0, MAX_OUTPUT_CHARS));
        } catch (RuntimeException exception) {
            LOGGER.warn("Claim vector semantic enhancement unavailable project={} version={} sourceType={} error={}",
                    safe(projectId), safe(businessVersion), safe(sourceType), exception.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
