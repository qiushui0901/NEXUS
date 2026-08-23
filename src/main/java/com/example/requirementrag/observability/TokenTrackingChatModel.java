package com.example.requirementrag.observability;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 包装 Spring AI {@link ChatModel}，在每次调用/流式响应时把真实 {@code TokenUsage}
 * 上报到 {@link ChatTokenUsageTracker}，对业务代码透明。
 *
 * <p>流式调用只在订阅开始时计一次请求数，并仅用最后一个携带 usage 的最终分片记录 token，
 * 避免按 SSE 分片重复累加。
 */
public final class TokenTrackingChatModel implements ChatModel {

    private final ChatModel delegate;
    private final ChatTokenUsageTracker tokenUsageTracker;

    public TokenTrackingChatModel(ChatModel delegate, ChatTokenUsageTracker tokenUsageTracker) {
        this.delegate = delegate;
        this.tokenUsageTracker = tokenUsageTracker;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ChatResponse response = delegate.call(prompt);
        if (response != null) {
            tokenUsageTracker.record("chat", response.getMetadata().getUsage());
        }
        return response;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> {
            AtomicReference<Usage> lastUsage = new AtomicReference<>();
            return delegate.stream(prompt)
                    .doOnSubscribe(ignored -> tokenUsageTracker.recordRequest("chat"))
                    .doOnNext(response -> {
                        if (response != null && response.getMetadata().getUsage() != null) {
                            lastUsage.set(response.getMetadata().getUsage());
                        }
                    })
                    .doOnComplete(() -> tokenUsageTracker.recordUsage("chat", lastUsage.get()))
                    .doOnError(ignored -> {
                        // 异常/取消不按错误分片计 token；请求已在上方订阅时计数一次。
                    });
        });
    }

    @Override
    public ChatOptions getOptions() {
        return delegate.getOptions();
    }

    @Override
    @Deprecated
    public ChatOptions getDefaultOptions() {
        return delegate.getOptions();
    }
}