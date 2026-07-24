package com.example.requirementrag.service;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.RagOutcomeStatus;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueryRouterTest {

    @Test
    void reportsDegradedWhenLlmRoutingFailsAndUsesDefaultProject() {
        ProjectRegistry registry = mock(ProjectRegistry.class);
        ChatClient chatClient = mock(ChatClient.class);
        RagProperties properties = mock(RagProperties.class);
        RagProperties.ProjectConfig project = mock(RagProperties.ProjectConfig.class);
        when(project.id()).thenReturn("default-project");
        when(project.side()).thenReturn("server");
        when(registry.defaultProject()).thenReturn(project);
        when(chatClient.prompt()).thenThrow(new RuntimeException("secret provider url"));

        var outcome = new QueryRouter(registry, chatClient, properties)
                .routeWithOutcome("成长基金怎么开发", null);

        assertEquals(RagOutcomeStatus.DEGRADED, outcome.status());
        assertEquals("default-project", outcome.data().projectId());
        assertEquals("fallback", outcome.data().routingMethod());
        assertEquals("ROUTING_LLM_UNAVAILABLE", outcome.warnings().getFirst().code());
    }
}
