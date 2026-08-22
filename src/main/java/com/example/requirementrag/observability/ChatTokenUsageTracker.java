package com.example.requirementrag.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.stereotype.Component;

/**
 * 真实模型 Token 用量统计：从 Spring AI {@link ChatResponse} 元数据的 {@link Usage} 记录
 * prompt/completion/total token 计数，并计入 Micrometer 指标（name 含 "token"，可被监控页聚合）。
 *
 * <p>相比历史静态/空 tokenUsage，这里的数据来自模型 API 返回的真实 usage。
 */
@Component
public class ChatTokenUsageTracker {
    private static final Logger log = LoggerFactory.getLogger(ChatTokenUsageTracker.class);
    private final MeterRegistry meterRegistry;

    public ChatTokenUsageTracker(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * 记录一次模型调用的真实 token 用量。
     *
     * @param stage 调用阶段（如 chat / intent-fallback），作为指标标签
     * @param usage 模型响应中的真实用量；null 或全空时只记录一次请求计数
     */
    public void record(String stage, Usage usage) {
        String safeStage = stage == null || stage.isBlank() ? "chat" : stage;
        Counter.builder("rag.tokens.requests").tag("stage", safeStage)
                .register(meterRegistry).increment();
        if (usage == null) {
            return;
        }
        long prompt = nonNull(usage.getPromptTokens());
        long completion = nonNull(usage.getCompletionTokens());
        long total = usage.getTotalTokens() != null ? usage.getTotalTokens() : (prompt + completion);
        if (prompt > 0) {
            Counter.builder("rag.tokens.prompt").tag("stage", safeStage).register(meterRegistry).increment(prompt);
        }
        if (completion > 0) {
            Counter.builder("rag.tokens.completion").tag("stage", safeStage).register(meterRegistry).increment(completion);
        }
        if (total > 0) {
            Counter.builder("rag.tokens.total").tag("stage", safeStage).register(meterRegistry).increment(total);
        }
        log.atDebug().addKeyValue("stage", safeStage).addKeyValue("promptTokens", prompt)
                .addKeyValue("completionTokens", completion).addKeyValue("totalTokens", total)
                .log("recorded real model token usage");
    }

    private long nonNull(Integer value) {
        return value == null ? 0L : value;
    }
}