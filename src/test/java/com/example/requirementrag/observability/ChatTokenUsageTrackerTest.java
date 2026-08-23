package com.example.requirementrag.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatTokenUsageTrackerTest {

    @Test
    void recordsRealPromptCompletionAndTotalTokens() {
        MeterRegistry registry = new SimpleMeterRegistry();
        ChatTokenUsageTracker tracker = new ChatTokenUsageTracker(registry);

        tracker.record("chat", usage(10, 5));

        assertThat(registry.get("rag.tokens.requests").tag("stage", "chat").counter().count()).isEqualTo(1);
        assertThat(registry.get("rag.tokens.prompt").tag("stage", "chat").counter().count()).isEqualTo(10);
        assertThat(registry.get("rag.tokens.completion").tag("stage", "chat").counter().count()).isEqualTo(5);
        assertThat(registry.get("rag.tokens.total").tag("stage", "chat").counter().count()).isEqualTo(15);
    }

    @Test
    void recordsRequestEvenWhenUsageIsNull() {
        MeterRegistry registry = new SimpleMeterRegistry();
        ChatTokenUsageTracker tracker = new ChatTokenUsageTracker(registry);

        tracker.record("intent-fallback", null);

        assertThat(registry.get("rag.tokens.requests").tag("stage", "intent-fallback").counter().count()).isEqualTo(1);
        assertThat(registry.find("rag.tokens.prompt").tag("stage", "intent-fallback").counters()).isEmpty();
    }

    @Test
    void decoratorForwardsCallAndCapturesRealUsageFromResponse() {
        MeterRegistry registry = new SimpleMeterRegistry();
        ChatTokenUsageTracker tracker = new ChatTokenUsageTracker(registry);
        ChatModel delegate = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return new ChatResponse(List.of(),
                        ChatResponseMetadata.builder().usage(usage(7, 3)).build());
            }

            @Override
            public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) {
                return reactor.core.publisher.Flux.empty();
            }
        };
        TokenTrackingChatModel model = new TokenTrackingChatModel(delegate, tracker);

        model.call(new Prompt("你好"));

        assertThat(registry.get("rag.tokens.prompt").counter().count()).isEqualTo(7);
        assertThat(registry.get("rag.tokens.completion").counter().count()).isEqualTo(3);
        assertThat(registry.get("rag.tokens.total").counter().count()).isEqualTo(10);
    }

    @Test
    void streamingCountsOneRequestAndOnlyFinalChunkUsage() {
        MeterRegistry registry = new SimpleMeterRegistry();
        ChatTokenUsageTracker tracker = new ChatTokenUsageTracker(registry);
        TokenTrackingChatModel model = new TokenTrackingChatModel(streamingModel(Flux.just(
                response(null), response(null), response(usage(7, 3)))), tracker);

        model.stream(new Prompt("你好")).collectList().block();

        assertThat(registry.get("rag.tokens.requests").counter().count()).isEqualTo(1);
        assertThat(registry.get("rag.tokens.prompt").counter().count()).isEqualTo(7);
        assertThat(registry.get("rag.tokens.completion").counter().count()).isEqualTo(3);
        assertThat(registry.get("rag.tokens.total").counter().count()).isEqualTo(10);
    }

    @Test
    void streamingDoesNotDoubleCountCumulativeUsageAcrossChunks() {
        MeterRegistry registry = new SimpleMeterRegistry();
        ChatTokenUsageTracker tracker = new ChatTokenUsageTracker(registry);
        TokenTrackingChatModel model = new TokenTrackingChatModel(streamingModel(Flux.just(
                response(usage(3, 1)), response(usage(5, 2)), response(usage(7, 3)))), tracker);

        model.stream(new Prompt("你好")).collectList().block();

        // 只消费最终分片 usage，避免把累计式 usage 逐片重复累加。
        assertThat(registry.get("rag.tokens.requests").counter().count()).isEqualTo(1);
        assertThat(registry.get("rag.tokens.prompt").counter().count()).isEqualTo(7);
        assertThat(registry.get("rag.tokens.completion").counter().count()).isEqualTo(3);
        assertThat(registry.get("rag.tokens.total").counter().count()).isEqualTo(10);
    }

    @Test
    void streamingErrorCountsOneRequestAndNoTokenUsage() {
        MeterRegistry registry = new SimpleMeterRegistry();
        ChatTokenUsageTracker tracker = new ChatTokenUsageTracker(registry);
        TokenTrackingChatModel model = new TokenTrackingChatModel(streamingModel(Flux.error(new RuntimeException("boom"))), tracker);

        assertThatThrownBy(() -> model.stream(new Prompt("你好")).collectList().block())
                .isInstanceOf(RuntimeException.class);

        assertThat(registry.get("rag.tokens.requests").counter().count()).isEqualTo(1);
        assertThat(registry.find("rag.tokens.prompt").counters()).isEmpty();
        assertThat(registry.find("rag.tokens.total").counters()).isEmpty();
    }

    @Test
    void streamingCancellationCountsOneRequestAndNoTokenUsage() {
        MeterRegistry registry = new SimpleMeterRegistry();
        ChatTokenUsageTracker tracker = new ChatTokenUsageTracker(registry);
        TokenTrackingChatModel model = new TokenTrackingChatModel(streamingModel(Flux.just(
                response(null), response(usage(9, 9)))), tracker);

        model.stream(new Prompt("你好")).take(1).collectList().block();

        assertThat(registry.get("rag.tokens.requests").counter().count()).isEqualTo(1);
        assertThat(registry.find("rag.tokens.prompt").counters()).isEmpty();
    }

    private ChatModel streamingModel(Flux<ChatResponse> stream) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return stream;
            }
        };
    }

    private ChatResponse response(Usage usage) {
        return new ChatResponse(List.of(),
                usage == null ? ChatResponseMetadata.builder().build()
                        : ChatResponseMetadata.builder().usage(usage).build());
    }

    private Usage usage(int prompt, int completion) {
        return new Usage() {
            @Override
            public Integer getPromptTokens() {
                return prompt;
            }

            @Override
            public Integer getCompletionTokens() {
                return completion;
            }

            @Override
            public Object getNativeUsage() {
                return null;
            }
        };
    }
}