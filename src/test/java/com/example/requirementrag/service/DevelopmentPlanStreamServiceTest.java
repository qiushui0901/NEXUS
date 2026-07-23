package com.example.requirementrag.service;

import com.example.requirementrag.model.DevelopmentPlanStreamEvent;
import com.example.requirementrag.model.CodeChunk;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DevelopmentPlanStreamServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DevelopmentPlanStreamService service = new DevelopmentPlanStreamService(
            null, null, null, null, null, null, objectMapper, new PlanSectionEvidenceMatcher(objectMapper));

    @Test
    void keepsValidSegmentsWhenProviderFailsAfterStreamingContent() {
        Flux<String> chunks = Flux.concat(
                Flux.just("{\"type\":\"summary\",\"payload\":{\"text\":\"先定位入口\"}}\n"),
                Flux.error(new RuntimeException("Stream failed")));
        List<DevelopmentPlanStreamEvent> events = new ArrayList<>();

        long emitted = service.consumeModelStream(chunks, events::add);

        assertEquals(1, emitted);
        assertEquals("summary", events.getFirst().type());
    }

    @Test
    void propagatesProviderFailureWhenNothingUsableWasStreamed() {
        Flux<String> chunks = Flux.error(new RuntimeException("Stream failed"));

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> service.consumeModelStream(chunks, ignored -> { }));

        assertEquals("Stream failed", failure.getMessage());
    }

    @Test
    void enrichesSectionEventsWithRealCodeTargetsOnly() {
        ObjectNode payload = objectMapper.createObjectNode()
                .put("title", "配置表设计")
                .put("purpose", "按等级读取奖励档位配置");
        DevelopmentPlanStreamEvent event = new DevelopmentPlanStreamEvent("section", 3, payload, "生成配置方案");
        List<CodeChunk> code = List.of(new CodeChunk(
                "config-id", "game", "sha", "config/ConfigHeroGrowUp.java", "class",
                "ConfigHeroGrowUp", 10, 50, "load config getByLevel reward", "hash"));

        DevelopmentPlanStreamEvent enriched = service.enrichSectionEvent(event, code);

        assertEquals("config-id", enriched.payload().path("inspectTargets").get(0).path("id").asText());
        assertEquals("配置表设计", enriched.payload().path("title").asText());
    }

    @Test
    void leavesNonSectionEventsUnchanged() {
        ObjectNode payload = objectMapper.createObjectNode().put("text", "开发入口");
        DevelopmentPlanStreamEvent event = new DevelopmentPlanStreamEvent("summary", 1, payload, "摘要");

        DevelopmentPlanStreamEvent unchanged = service.enrichSectionEvent(event, List.of());

        assertEquals(event, unchanged);
    }
}
