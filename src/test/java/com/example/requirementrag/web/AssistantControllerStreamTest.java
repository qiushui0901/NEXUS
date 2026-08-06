package com.example.requirementrag.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.requirementrag.service.DevelopmentPlanService;
import com.example.requirementrag.service.DevelopmentPlanStreamService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class AssistantControllerStreamTest {

    @Test
    void exposesPostDevelopmentPlanAsEventStream() throws Exception {
        DevelopmentPlanService planService = mock(DevelopmentPlanService.class);
        DevelopmentPlanStreamService streamService = mock(DevelopmentPlanStreamService.class);
        ProjectAccessGuard accessGuard = mock(ProjectAccessGuard.class);
        when(streamService.stream(any())).thenAnswer(invocation -> {
            SseEmitter emitter = new SseEmitter(5_000L);
            Thread thread = new Thread(() -> {
                try {
                    emitter.send(SseEmitter.event().name("started").data("{\"type\":\"started\"}"));
                    emitter.complete();
                }
                catch (Exception exception) {
                    emitter.completeWithError(exception);
                }
            });
            thread.setDaemon(true);
            thread.start();
            return emitter;
        });

        AssistantController controller = new AssistantController(planService, streamService, accessGuard);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        MvcResult started = mvc.perform(post("/api/assistant/development-plan/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"通用功能怎么开发\",\"limit\":8}"))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));
    }
}
