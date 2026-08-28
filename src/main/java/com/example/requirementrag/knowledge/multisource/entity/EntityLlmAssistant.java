package com.example.requirementrag.knowledge.multisource.entity;

import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.QuestionExtractionRaw;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.SourceExtractionRaw;
import com.example.requirementrag.service.GenerationChatOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * LLM 实体辅助客户端（Phase 2）。
 *
 * <p>只做三件事：问题实体提取（受限补召回）、来源提取候选、受限选择。
 * 任何失败（模型不可用/JSON 解析/校验拒绝/超预算）都返回空并记录稳定告警码，
 * 由调用方回退到规则结果——规则解析链始终不依赖 LLM。
 */
@Component
public class EntityLlmAssistant {

    private static final Logger log = LoggerFactory.getLogger(EntityLlmAssistant.class);
    private static final Set<String> UNAVAILABLE_MARKERS = Set.of(
            "unavailable", "not configured", "no api", "connection refused", "timed out");

    private final ChatClient chatClient;
    private final RagProperties ragProperties;
    private final EntityExtractionProperties properties;
    private final EntityExtractionValidator validator;

    public EntityLlmAssistant(ChatClient chatClient, RagProperties ragProperties,
                              EntityExtractionProperties properties,
                              EntityExtractionValidator validator) {
        this.chatClient = chatClient;
        this.ragProperties = ragProperties;
        this.properties = properties;
        this.validator = validator;
    }

    /** LLM 辅助是否可用（模型名可解析且开关打开）。 */
    public boolean available() {
        return properties != null && properties.enabled() && properties.allowLlmAssist()
                && chatClient != null && resolvedModel() != null;
    }

    /** 问题实体提取（受限补召回）。失败返回 empty，调用方保留规则结果。 */
    public Optional<QuestionExtractionRaw> analyzeQuestion(String projectId, String query) {
        if (!available()) return Optional.empty();
        try {
            QuestionExtractionRaw raw = chatClient.prompt()
                    .system(EntityExtractionPromptService.questionSystemPrompt())
                    .user(EntityExtractionPromptService.questionUserPrompt(projectId, query, List.of()))
                    .options(GenerationChatOptions.forModel(resolvedModel()))
                    .call()
                    .entity(QuestionExtractionRaw.class);
            QuestionExtractionRaw validated = validator.validateQuestion(raw);
            log.info("Entity LLM question extraction ok project={} entities={}", projectId,
                    validated.entities().size());
            return Optional.of(validated);
        } catch (EntityExtractionException exception) {
            log.warn("Entity LLM question extraction rejected project={} code={}",
                    projectId, exception.code());
            return Optional.empty();
        } catch (RuntimeException exception) {
            log.warn("Entity LLM question extraction failed project={}: {}", projectId,
                    classify(exception));
            return Optional.empty();
        }
    }

    /** 来源提取候选（entity/fact/relation 均为 PROPOSED 语义）。失败返回 empty。 */
    public Optional<SourceExtractionRaw> extractFromSource(String projectId, String businessVersion,
                                                           List<String> claimLines,
                                                           Set<String> inputClaimIds) {
        if (!available()) return Optional.empty();
        try {
            SourceExtractionRaw raw = chatClient.prompt()
                    .system(EntityExtractionPromptService.sourceSystemPrompt())
                    .user(EntityExtractionPromptService.sourceUserPrompt(projectId, businessVersion, claimLines))
                    .options(GenerationChatOptions.forModel(resolvedModel()))
                    .call()
                    .entity(SourceExtractionRaw.class);
            SourceExtractionRaw validated = validator.validateSource(raw, inputClaimIds);
            log.info("Entity LLM source extraction ok project={} version={} entities={} facts={} relations={}",
                    projectId, businessVersion, validated.entities().size(),
                    validated.facts().size(), validated.relations().size());
            return Optional.of(validated);
        } catch (EntityExtractionException exception) {
            log.warn("Entity LLM source extraction rejected project={} code={}",
                    projectId, exception.code());
            return Optional.empty();
        } catch (RuntimeException exception) {
            log.warn("Entity LLM source extraction failed project={}: {}", projectId,
                    classify(exception));
            return Optional.empty();
        }
    }

    /** 受限选择：从候选 entityId 中选一个。返回空表示不可用/失败/模型选择 null。 */
    public Optional<String> selectEntity(String target, List<String> candidates,
                                         Set<String> allowedIds) {
        if (!available() || candidates == null || candidates.isEmpty()) {
            return Optional.empty();
        }
        try {
            ConversationRecord selected = chatClient.prompt()
                    .system(EntityExtractionPromptService.selectionSystemPrompt())
                    .user(EntityExtractionPromptService.selectionUserPrompt(target, candidates))
                    .options(GenerationChatOptions.forModel(resolvedModel()))
                    .call()
                    .entity(ConversationRecord.class);
            if (selected == null) return Optional.empty();
            validator.validateSelection(selected.entityId(), allowedIds);
            return Optional.ofNullable(selected.entityId());
        } catch (EntityExtractionException exception) {
            log.warn("Entity LLM selection rejected target={} code={}", target, exception.code());
            return Optional.empty();
        } catch (RuntimeException exception) {
            log.warn("Entity LLM selection failed target={}: {}", target, classify(exception));
            return Optional.empty();
        }
    }

    /** 解析模型名；不可用返回 null（调用方回退规则路径）。 */
    public String resolvedModel() {
        if (properties != null && properties.model() != null && !properties.model().isBlank()) {
            return properties.model();
        }
        if (ragProperties == null || ragProperties.llm() == null) {
            return null;
        }
        return ragProperties.llm().resolvedDevelopmentPlanModel();
    }

    /** 稳定分类：JSON 解析失败/超时/限流/不可用。 */
    static String classify(RuntimeException exception) {
        if (exception instanceof EntityExtractionException) {
            return ((EntityExtractionException) exception).code();
        }
        Throwable current = exception;
        while (current != null) {
            if (current instanceof com.fasterxml.jackson.core.JsonProcessingException
                    || current.getClass().getName().contains("Json")) {
                return "ENTITY_JSON_PARSE_FAILED";
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        String message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("timeout") || message.contains("deadline")) return "ENTITY_MODEL_TIMEOUT";
        if (message.contains("429") || message.contains("rate") || message.contains("quota")) {
            return "ENTITY_MODEL_RATE_LIMITED";
        }
        for (String marker : UNAVAILABLE_MARKERS) {
            if (message.contains(marker)) return "ENTITY_MODEL_UNAVAILABLE";
        }
        return "ENTITY_MODEL_UNAVAILABLE";
    }

    /** 受限选择输出 record。 */
    public record ConversationRecord(String entityId, Double confidence) {
    }
}