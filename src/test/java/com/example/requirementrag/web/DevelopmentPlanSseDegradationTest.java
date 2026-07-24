package com.example.requirementrag.web;

import com.example.requirementrag.code.CodeKnowledgeService;
import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.QueryRouting;
import com.example.requirementrag.model.RagOutcome;
import com.example.requirementrag.observability.RagObservability;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import com.example.requirementrag.service.DevelopmentPlanService;
import com.example.requirementrag.service.DevelopmentPlanStreamService;
import com.example.requirementrag.service.PlanSectionEvidenceMatcher;
import com.example.requirementrag.service.QueryRouter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DevelopmentPlanSseDegradationTest {

    @Test
    void preservesExistingEventsAndAddsWarningForRoutingFallback() throws Exception {
        RagProperties properties = mock(RagProperties.class);
        when(properties.knowledge()).thenReturn(new RagProperties.Knowledge(
                false, null, null, "requirements", "5.1", null, null, 0));
        when(properties.llm()).thenReturn(new RagProperties.Llm("generation-model", "reranker", "router"));
        ProjectRegistry projectRegistry = mock(ProjectRegistry.class);
        when(projectRegistry.resolveRequirementCollection("game")).thenReturn("requirements_game");
        QueryRouter queryRouter = mock(QueryRouter.class);
        when(queryRouter.routeWithOutcome(anyString(), any())).thenReturn(RagOutcome.degraded(
                new QueryRouting("game", "server", 0.0, "fallback"), "query.route",
                "ROUTING_LLM_UNAVAILABLE", "自动项目路由不可用，已使用默认项目", 1, 1));
        QdrantHybridStore documentStore = mock(QdrantHybridStore.class);
        when(documentStore.hybridSearch("requirements_game", "query", "requirements", "5.1"))
                .thenReturn(List.of());
        CodeKnowledgeService codeKnowledgeService = mock(CodeKnowledgeService.class);
        when(codeKnowledgeService.search("query", "game", 8)).thenReturn(List.of());
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().system(anyString()).user(anyString()).options(any()).stream().content())
                .thenReturn(Flux.just("{\"type\":\"summary\",\"payload\":{\"text\":\"方案摘要\"}}\n"));
        ObjectMapper objectMapper = new ObjectMapper();
        DevelopmentPlanStreamService streamService = new DevelopmentPlanStreamService(
                properties, projectRegistry, queryRouter, documentStore, codeKnowledgeService, chatClient,
                objectMapper, new PlanSectionEvidenceMatcher(objectMapper), mock(RagObservability.class));
        AssistantController controller = new AssistantController(mock(DevelopmentPlanService.class), streamService,
                mock(ProjectAccessGuard.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        MvcResult started = mvc.perform(post("/api/assistant/development-plan/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"query\",\"limit\":8}"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event:warning")))
                .andExpect(content().string(containsString("event:retrieval")))
                .andExpect(content().string(containsString("event:references")))
                .andExpect(content().string(containsString("event:completed")))
                .andExpect(content().string(containsString("ROUTING_LLM_UNAVAILABLE")));
    }
}
