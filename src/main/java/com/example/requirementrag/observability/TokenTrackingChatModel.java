package com.example.requirementrag.observability;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * 包装 Spring AI {@link ChatModel}，在每次调用/流式响应时把真实 {@code TokenUsage}
 * 上报到 {@link ChatTokenUsageTracker}，对业务代码透明。
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
        return delegate.stream(prompt)
                .doOnNext(response -> {
                    if (response != null) {
                        tokenUsageTracker.record("chat", response.getMetadata().getUsage());
                    }
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