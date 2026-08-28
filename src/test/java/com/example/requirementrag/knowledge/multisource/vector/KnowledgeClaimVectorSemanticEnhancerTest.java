package com.example.requirementrag.knowledge.multisource.vector;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeClaimVectorSemanticEnhancerTest {

    @Test
    void usesConfiguredGptLunaModelAndReturnsRecallText() {
        KnowledgeClaimVectorProperties properties = properties(true, "gpt-5.6-luna");
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec response = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(request);
        when(request.system(anyString())).thenReturn(request);
        when(request.user(anyString())).thenReturn(request);
        AtomicReference<ChatOptions.Builder<?>> options = new AtomicReference<>();
        when(request.options(any(ChatOptions.Builder.class))).thenAnswer(invocation -> {
            options.set(invocation.getArgument(0));
            return request;
        });
        when(request.call()).thenReturn(response);
        when(response.content()).thenReturn("累计签到 8 天奖励；用户可能询问签到奖励条件");

        KnowledgeClaimVectorSemanticEnhancer enhancer =
                new KnowledgeClaimVectorSemanticEnhancer(chatClient, properties);

        assertThat(enhancer.enhance("immortal", "5.1", "REQUIREMENT", "签到",
                "Subject: 玩家\nCondition or action: 累计签到达到 8 天\nResult or value: 碎片 10\nUnit: 个"))
                .hasValueSatisfying(value -> assertThat(value).contains("累计签到 8 天奖励"));
        verify(request).options(any(ChatOptions.Builder.class));
        assertThat(options.get()).isInstanceOf(OpenAiChatOptions.Builder.class);
        assertThat(((OpenAiChatOptions.Builder) options.get()).build().getModel())
                .isEqualTo("gpt-5.6-luna");
    }

    @Test
    void disabledEnhancementDoesNotCallChatClient() {
        ChatClient chatClient = mock(ChatClient.class);
        KnowledgeClaimVectorSemanticEnhancer enhancer =
                new KnowledgeClaimVectorSemanticEnhancer(chatClient, properties(false, "gpt-5.6-luna"));

        assertThat(enhancer.enhance("immortal", "5.1", "REQUIREMENT", "签到", "事实文本"))
                .isEmpty();
    }

    @Test
    void failedEnhancementReturnsEmptyForDeterministicFallback() {
        KnowledgeClaimVectorProperties properties = properties(true, "gpt-5.6-luna");
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        when(chatClient.prompt()).thenReturn(request);
        when(request.system(anyString())).thenReturn(request);
        when(request.user(anyString())).thenReturn(request);
        when(request.options(any())).thenReturn(request);
        when(request.call()).thenThrow(new RuntimeException("gateway unavailable"));

        KnowledgeClaimVectorSemanticEnhancer enhancer =
                new KnowledgeClaimVectorSemanticEnhancer(chatClient, properties);

        assertThat(enhancer.enhance("immortal", "5.1", "PARAMETER_TABLE", "签到", "确定性事实文本"))
                .isEmpty();
    }

    private KnowledgeClaimVectorProperties properties(boolean enabled, String model) {
        return new KnowledgeClaimVectorProperties(
                enabled, true, true, false,
                "knowledge_claims_live", "knowledge-claim-vector-v2", "knowledge-claim-text-v2",
                20, 3, 8, 3, 2, "target/semantic-enhancer-test.db", "ACTIVE_DOC",
                24_000, enabled, model);
    }
}
