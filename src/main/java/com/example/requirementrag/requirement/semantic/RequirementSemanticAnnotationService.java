package com.example.requirementrag.requirement.semantic;

import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticAnnotationInput;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticAnnotationOutcome;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticAnnotationResult;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticErrorCode;
import com.example.requirementrag.service.GenerationChatOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * 需求语义标注服务：调用 LLM 生成结构化语义标注，执行重试与错误分类，
 * 不直接发布正式 Claim；语义结果默认只是检索候选。
 */
@Service
@ConditionalOnProperty(prefix = "app.rag.requirement-semantic", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public class RequirementSemanticAnnotationService {
    private static final Logger log = LoggerFactory.getLogger(RequirementSemanticAnnotationService.class);

    private final ChatClient chatClient;
    private final RagProperties ragProperties;
    private final RequirementSemanticProperties properties;
    private final RequirementSemanticPromptService promptService;
    private final RequirementSemanticAnnotationValidator validator;

    public RequirementSemanticAnnotationService(ChatClient chatClient,
                                                RagProperties ragProperties,
                                                RequirementSemanticProperties properties,
                                                RequirementSemanticPromptService promptService,
                                                RequirementSemanticAnnotationValidator validator) {
        this.chatClient = chatClient;
        this.ragProperties = ragProperties;
        this.properties = properties;
        this.promptService = promptService;
        this.validator = validator;
    }

    /** 返回本次标注实际使用的模型，写入持久化记录用于审计。 */
    public String resolvedModel() {
        if (properties.model() != null && !properties.model().isBlank()) {
            return properties.model();
        }
        return ragProperties.llm() == null ? "configured-default"
                : ragProperties.llm().resolvedDevelopmentPlanModel();
    }

    /** 调用 LLM 并执行服务端校验；失败返回稳定错误码，不抛出模型异常。 */
    public SemanticAnnotationOutcome annotate(SemanticAnnotationInput input) {
        return annotate(input, Integer.MAX_VALUE);
    }

    /** 带剩余模型调用预算的标注：单窗口内的重试也不会突破构建级总预算。 */
    public SemanticAnnotationOutcome annotate(SemanticAnnotationInput input, int remainingModelCalls) {
        if (input == null || input.rawText() == null || input.rawText().isBlank()) {
            return SemanticAnnotationOutcome.failure(SemanticErrorCode.SCHEMA_INVALID, 0, 0, 0, 0);
        }
        String model = resolvedModel();
        if (model == null || model.isBlank()) {
            throw new RequirementSemanticException("SEMANTIC_MODEL_UNAVAILABLE", "需求语义标注未配置模型");
        }
        long startedAt = System.nanoTime();
        int maxAttempts = Math.max(1, Math.min(properties.maxRetries() + 1, remainingModelCalls));
        int modelCalls = 0;
        SemanticErrorCode lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                SemanticAnnotationResult raw = chatClient.prompt()
                        .system(promptService.systemPrompt())
                        .user(promptService.userPrompt(input))
                        .options(GenerationChatOptions.forModel(model))
                        .call()
                        .entity(SemanticAnnotationResult.class);
                modelCalls++;
                SemanticAnnotationResult validated = validator.validate(input, raw);
                long latencyMs = elapsedMs(startedAt);
                int tokenEstimate = estimateTokens(input, validated);
                return new SemanticAnnotationOutcome(validated, null, attempt, modelCalls,
                        latencyMs, tokenEstimate);
            } catch (RequirementSemanticException exception) {
                modelCalls++;
                lastError = errorCode(exception.code());
                log.warn("Requirement semantic annotation rejected project={} chunk={} attempt={} code={}",
                        safe(input.projectId()), safe(input.sourceChunkId()), attempt, exception.code());
            } catch (RuntimeException exception) {
                modelCalls++;
                lastError = classify(exception);
                log.warn("Requirement semantic annotation failed project={} chunk={} attempt={}: {}",
                        safe(input.projectId()), safe(input.sourceChunkId()), attempt,
                        exception.getClass().getSimpleName());
            }
            if (attempt < maxAttempts && retryable(lastError)) {
                backoff(attempt);
            } else {
                break;
            }
        }
        return SemanticAnnotationOutcome.failure(lastError == null ? SemanticErrorCode.MODEL_UNAVAILABLE
                : lastError, maxAttempts, modelCalls, elapsedMs(startedAt),
                // 失败也消耗了输入 token（每次尝试都提交了输入），计入预算避免后续窗口突破上限。
                estimateTokens(input, null) * Math.max(1, modelCalls));
    }

    /** 把异常映射为稳定错误码：JSON 解析 / 超时 / 限流 / 不可用；
     *  NPE 在语义标注上下文中只可能是契约/数据问题，不能误判为模型不可用。 */
    static SemanticErrorCode classify(RuntimeException exception) {
        if (exception instanceof NullPointerException) {
            return SemanticErrorCode.SCHEMA_INVALID;
        }
        Throwable current = exception;
        while (current != null) {
            if (current instanceof com.fasterxml.jackson.core.JsonProcessingException
                    || current.getClass().getName().contains("Json")) {
                return SemanticErrorCode.JSON_PARSE_FAILED;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        String message = exception.getMessage() == null ? "" : exception.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("timeout") || message.contains("timed out") || message.contains("deadline")) {
            return SemanticErrorCode.MODEL_TIMEOUT;
        }
        if (message.contains("rate") || message.contains("429") || message.contains("quota")) {
            return SemanticErrorCode.MODEL_RATE_LIMITED;
        }
        return SemanticErrorCode.MODEL_UNAVAILABLE;
    }

    static SemanticErrorCode errorCode(String code) {
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        if (normalized.startsWith("SEMANTIC_")) {
            normalized = normalized.substring("SEMANTIC_".length());
        }
        try {
            return SemanticErrorCode.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            return SemanticErrorCode.SCHEMA_INVALID;
        }
    }

    private static boolean retryable(SemanticErrorCode code) {
        return code == SemanticErrorCode.MODEL_TIMEOUT
                || code == SemanticErrorCode.MODEL_RATE_LIMITED
                || code == SemanticErrorCode.MODEL_UNAVAILABLE
                || code == SemanticErrorCode.JSON_PARSE_FAILED;
    }

    /** 指数退避：仅对可重试错误生效，避免限流风暴。 */
    private void backoff(int attempt) {
        try {
            Thread.sleep(Math.min(2_000L, 200L * attempt));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /** 粗略 token 估计（中英混排约 2 字符/Token），仅用于预算控制，不作为计费依据。 */
    private int estimateTokens(SemanticAnnotationInput input, SemanticAnnotationResult result) {
        int chars = input.rawText() == null ? 0 : input.rawText().length();
        if (result != null) {
            chars += 200 * (result.entities().size() + result.conditions().size() + result.events().size()
                    + result.numericFacts().size() + result.claims().size() + result.questionExpansions().size());
        }
        return Math.max(1, chars / 2);
    }

    private long elapsedMs(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
