package com.example.requirementrag.service;

import com.example.requirementrag.evidence.EvidenceCitationService;
import com.example.requirementrag.evidence.EvidenceRegistry;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.DevelopmentPlanStreamEvent;
import com.example.requirementrag.model.RagWarning;
import com.example.requirementrag.retrieval.pipeline.RetrievalBundle;
import com.example.requirementrag.retrieval.pipeline.RetrievalProfile;
import com.example.requirementrag.model.CodeChunk;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.assertj.core.api.Assertions.assertThat;

class DevelopmentPlanStreamServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DevelopmentPlanStreamService service = new DevelopmentPlanStreamService(
            null, null, null, null, null, null, objectMapper, new PlanSectionEvidenceMatcher(objectMapper), null);

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
    void marksPartialProviderFailureAsDegraded() {
        Flux<String> chunks = Flux.concat(
                Flux.just("{\"type\":\"summary\",\"payload\":{\"text\":\"先定位入口\"}}\n"),
                Flux.error(new RuntimeException("internal provider url")));

        var outcome = service.consumeModelStreamOutcome(chunks, ignored -> { }, System.nanoTime());

        assertEquals(com.example.requirementrag.model.RagOutcomeStatus.DEGRADED, outcome.status());
        assertEquals("STREAM_PARTIAL_RESULT", outcome.warnings().getFirst().code());
        assertEquals(1L, outcome.data());
    }

    @Test
    void rejectsCompletedStreamWithoutValidEvents() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.consumeModelStream(Flux.empty(), ignored -> { }));

        assertEquals("Model stream produced no valid events", failure.getMessage());
    }

    @Test
    void propagatesProviderFailureWhenNothingUsableWasStreamed() {
        Flux<String> chunks = Flux.error(new RuntimeException("Stream failed"));

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> service.consumeModelStream(chunks, ignored -> { }));

        assertEquals("Stream failed", failure.getMessage());
    }

    @Test
    void rejectsStreamsThatOnlyContainUnsupportedEventTypes() {
        EvidenceRegistry registry = EvidenceRegistry.from(new RetrievalBundle("query", RetrievalProfile.DEVELOPMENT_PLAN,
                "project-a", null, null, List.of(), List.of()));
        List<RagWarning> warnings = new ArrayList<>();
        var session = new EvidenceCitationService().open(registry);
        Flux<String> chunks = Flux.just(
                "{\"type\":\"internal-debug\",\"payload\":{\"text\":\"debug\"}}\n");

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.consumeValidatedModelStreamOutcome(chunks, event ->
                        service.validateCitationEvent(event, session, warnings) != null, System.nanoTime()));

        assertEquals("Model stream produced no valid events", failure.getMessage());
        assertThat(warnings).extracting(RagWarning::code).containsExactly("UNKNOWN_PLAN_EVENT_TYPE");
    }

    @Test
    void enrichesSectionEventsWithRealCodeTargetsOnly() {
        ObjectNode payload = objectMapper.createObjectNode()
                .put("title", "配置规则设计")
                .put("purpose", "按条件读取功能配置");
        DevelopmentPlanStreamEvent event = new DevelopmentPlanStreamEvent("section", 3, payload, "生成配置方案");
        List<CodeChunk> code = List.of(new CodeChunk(
                "config-id", "game", "sha", "config/FeatureRuleConfig.java", "class",
                "FeatureRuleConfig", 10, 50, "load config getByCondition", "hash"));

        DevelopmentPlanStreamEvent enriched = service.enrichSectionEvent(event, code);

        assertEquals("config-id", enriched.payload().path("inspectTargets").get(0).path("id").asText());
        assertEquals("配置规则设计", enriched.payload().path("title").asText());
    }

    @Test
    void leavesNonSectionEventsUnchanged() {
        ObjectNode payload = objectMapper.createObjectNode().put("text", "开发入口");
        DevelopmentPlanStreamEvent event = new DevelopmentPlanStreamEvent("summary", 1, payload, "摘要");

        DevelopmentPlanStreamEvent unchanged = service.enrichSectionEvent(event, List.of());

        assertEquals(event, unchanged);
    }
    @Test
    void validatesStreamCitationsAgainstTheCurrentRetrievalWhitelist() {
        ChunkRecord chunk = new ChunkRecord("req-1", "doc-a", "1.0", "docs/spec.md", "section-a",
                "业务规则", "业务规则", "hash-a", 1, 1);
        EvidenceRegistry registry = EvidenceRegistry.from(new RetrievalBundle("query", RetrievalProfile.DEVELOPMENT_PLAN,
                "project-a", "doc-a", "1.0", List.of(chunk), List.of()));
        var session = new EvidenceCitationService().open(registry);
        List<RagWarning> warnings = new ArrayList<>();
        ObjectNode payload = objectMapper.createObjectNode().put("text", "结论");
        payload.putArray("evidenceIds").add("requirement:req-1").add("requirement:unknown");

        DevelopmentPlanStreamEvent validated = service.validateCitationEvent(
                new DevelopmentPlanStreamEvent("summary", 1, payload, "生成摘要"), session, warnings);

        assertThat(validated.payload().path("evidenceIds").valueStream().map(node -> node.asText()).toList())
                .containsExactly("requirement:req-1");
        assertEquals("PARTIAL", validated.payload().path("supportStatus").asText());
        assertThat(session.warnings()).extracting(RagWarning::code).containsExactly("INVALID_EVIDENCE_REFERENCE");
    }

    @Test
    void ignoresUnknownStreamEventTypesAndAddsAStableWarning() {
        EvidenceRegistry registry = EvidenceRegistry.from(new RetrievalBundle("query", RetrievalProfile.DEVELOPMENT_PLAN,
                "project-a", null, null, List.of(), List.of()));
        List<RagWarning> warnings = new ArrayList<>();

        DevelopmentPlanStreamEvent validated = service.validateCitationEvent(
                new DevelopmentPlanStreamEvent("internal-debug", 1, objectMapper.createObjectNode(), "debug"),
                new EvidenceCitationService().open(registry), warnings);

        assertThat(validated).isNull();
        assertThat(warnings).extracting(RagWarning::code).containsExactly("UNKNOWN_PLAN_EVENT_TYPE");
    }

}
