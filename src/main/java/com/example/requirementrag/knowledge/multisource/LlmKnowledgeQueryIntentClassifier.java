package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeQueryIntent;
import com.example.requirementrag.service.GenerationChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * 基于 LLM 的查询意图回退实现：规则分类器返回 GENERAL 时，把问题交给 LLM 归类。
 *
 * <p>任何异常/非法输出都降级为 {@link Optional#empty()}，保证不破坏规则链路。
 * 意图枚举与模型名见 {@link MultiSourceKnowledgeModels.KnowledgeQueryIntent} 与
 * {@code app.rag.multi-source.intent-model}。
 */
@Component
public class LlmKnowledgeQueryIntentClassifier implements KnowledgeQueryIntentLlmFallback {

    private final ChatClient chatClient;
    private final MultiSourceKnowledgeProperties properties;
    private final RagProperties ragProperties;

    public LlmKnowledgeQueryIntentClassifier(ChatClient chatClient,
                                             MultiSourceKnowledgeProperties properties,
                                             RagProperties ragProperties) {
        this.chatClient = chatClient;
        this.properties = properties;
        this.ragProperties = ragProperties;
    }

    @Override
    public Optional<KnowledgeQueryIntent> tryClassify(String query) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        try {
            LlmIntentResult result = chatClient.prompt()
                    .system("""
                            你是知识查询意图分类器。根据用户问题从以下枚举中选择唯一意图，只输出意图名称，不要解释：
                            NORMATIVE（需求规范：应该、必须、需求规定、规则）
                            VALIDATION（测试/验证：测试、覆盖、验证、是否通过、通过率）
                            PARAMETER（参数：多少、上限、下限、阈值、单位、范围）
                            DOUBT（存疑/风险：存疑、未确认、风险、待讨论、疑问）
                            CONSISTENCY（一致性对比：需求与测试是否一致、对比、差异）
                            IMPACT（影响分析：影响哪些、修改后会怎样）
                            GENERAL（无法归类的普通查询）
                            """)
                    .user("用户问题：" + query)
                    .options(GenerationChatOptions.forModel(resolveModel()))
                    .call()
                    .entity(LlmIntentResult.class);
            return parse(result);
        }
        catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private Optional<KnowledgeQueryIntent> parse(LlmIntentResult result) {
        if (result == null || result.intent() == null || result.intent().isBlank()) {
            return Optional.empty();
        }
        String normalized = result.intent().trim().toUpperCase(Locale.ROOT);
        try {
            return Optional.of(KnowledgeQueryIntent.valueOf(normalized));
        }
        catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    private String resolveModel() {
        return properties.intentModel(ragProperties.llm().resolvedRoutingModel());
    }

    /** LLM 原始应答：只关心意图字段。 */
    private record LlmIntentResult(String intent) {
    }
}