package com.example.requirementrag.retrieval.agentic;

import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.model.RagOutcome;
import com.example.requirementrag.model.RagOutcomeStatus;
import com.example.requirementrag.retrieval.pipeline.RetrievalBundle;
import com.example.requirementrag.retrieval.pipeline.RetrievalProfile;
import com.example.requirementrag.retrieval.pipeline.RetrievalRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgenticOrchestratorTest {

    private static final RetrievalRequest REQUEST = new RetrievalRequest("query",
            RetrievalProfile.REQUIREMENT_REVIEW, "game", "requirements", "5.1", 8);

    private static ChunkRecord hit(String id) {
        return new ChunkRecord(id, "requirements", "5.1", "feature.html",
                "p-" + id, "parent-" + id, "child-" + id, "hash-" + id, 1, 1);
    }

    private static CodeChunk code(String id) {
        return new CodeChunk(id, "game", "sha", "src/FeatureService.java", "METHOD",
                "run", 10, 20, "void run() {}", "hash");
    }

    private static StrategyResult success(String strategy, int requirementHits, int codeHits) {
        List<ChunkRecord> requirements = requirementHits == 0 ? List.of()
                : List.of(hit("h1"), hit("h2")).subList(0, requirementHits);
        List<CodeChunk> code = codeHits == 0 ? List.of() : List.of(code("c1")).subList(0, codeHits);
        RetrievalBundle bundle = new RetrievalBundle("query", RetrievalProfile.REQUIREMENT_REVIEW,
                "game", "requirements", "5.1", requirements, List.of(), code);
        return new StrategyResult(strategy, bundle, RagOutcomeStatus.SUCCESS, List.of(), List.of());
    }

    private static StrategyResult failed(String strategy) {
        RetrievalBundle bundle = new RetrievalBundle("query", RetrievalProfile.REQUIREMENT_REVIEW,
                "game", "requirements", "5.1", List.of(), List.of(), List.of());
        return new StrategyResult(strategy, bundle, RagOutcomeStatus.FAILED, List.of(), List.of());
    }

    private static StrategyResult insufficientFirstHop() {
        return new StrategyResult("hybrid",
                new RetrievalBundle("query", RetrievalProfile.REQUIREMENT_REVIEW, "game",
                        "requirements", "5.1", List.of(), List.of()),
                RagOutcomeStatus.SUCCESS, List.of(), List.of());
    }

    @Test
    void returnsImmediatelyWhenFirstHopIsConfident() {
        RetrievalStrategy strategy = mock(RetrievalStrategy.class);
        when(strategy.execute(REQUEST)).thenReturn(success("hybrid", 1, 0));
        AgenticOrchestrator orchestrator = new AgenticOrchestrator(List.of(strategy), new EvidenceReflector());

        RagOutcome<RetrievalBundle> outcome = orchestrator.execute(REQUEST);

        verify(strategy, times(1)).execute(REQUEST);
        assertEquals(RagOutcomeStatus.SUCCESS, outcome.status());
        assertEquals(1, outcome.data().requirementEvidence().size());
        assertEquals(0, outcome.warnings().size());
    }

    @Test
    void supplementsOnSecondHopAndMergesEvidenceWithoutDuplicates() {
        RetrievalStrategy strategy = mock(RetrievalStrategy.class);
        when(strategy.execute(REQUEST)).thenReturn(
                insufficientFirstHop(),
                success("hybrid", 1, 0));
        AgenticOrchestrator orchestrator = new AgenticOrchestrator(List.of(strategy), new EvidenceReflector());

        RagOutcome<RetrievalBundle> outcome = orchestrator.execute(REQUEST);

        verify(strategy, times(2)).execute(REQUEST);
        assertEquals(RagOutcomeStatus.SUCCESS, outcome.status());
        assertEquals(1, outcome.data().requirementEvidence().size());
        assertEquals("h1", outcome.data().requirementEvidence().get(0).id());
    }

    @Test
    void mergesDistinctEvidenceFromBothHops() {
        RetrievalStrategy first = mock(RetrievalStrategy.class);
        when(first.execute(REQUEST)).thenReturn(insufficientFirstHop());
        RetrievalStrategy second = mock(RetrievalStrategy.class);
        when(second.execute(REQUEST)).thenReturn(success("graph", 1, 1));
        AgenticOrchestrator orchestrator = new AgenticOrchestrator(
                List.of(first, second), new EvidenceReflector());

        RagOutcome<RetrievalBundle> outcome = orchestrator.execute(REQUEST);

        assertEquals(RagOutcomeStatus.SUCCESS, outcome.status());
        assertEquals(1, outcome.data().requirementEvidence().size());
        assertEquals(1, outcome.data().codeEvidence().size());
        assertEquals(1, outcome.stageDiagnostics().stream()
                .filter(diagnostic -> diagnostic.stage().equals("agentic.orchestrate")).count());
    }

    @Test
    void degradesAfterHopsExhaustedWhenEvidenceNeverSufficient() {
        RetrievalStrategy strategy = mock(RetrievalStrategy.class);
        when(strategy.execute(REQUEST)).thenReturn(insufficientFirstHop());
        AgenticOrchestrator orchestrator = new AgenticOrchestrator(
                List.of(strategy), new EvidenceReflector(3));

        RagOutcome<RetrievalBundle> outcome = orchestrator.execute(REQUEST);

        assertEquals(RagOutcomeStatus.DEGRADED, outcome.status());
        assertEquals(2, outcome.warnings().stream()
                .filter(warning -> warning.code().equals("ORCHESTRATION_INSUFFICIENT_EVIDENCE")).count());
        assertEquals(0, outcome.data().requirementEvidence().size());
    }

    @Test
    void degradesImmediatelyWhenCoreStageFailed() {
        RetrievalStrategy strategy = mock(RetrievalStrategy.class);
        when(strategy.execute(REQUEST)).thenReturn(failed("hybrid"));
        AgenticOrchestrator orchestrator = new AgenticOrchestrator(List.of(strategy), new EvidenceReflector());

        RagOutcome<RetrievalBundle> outcome = orchestrator.execute(REQUEST);

        assertEquals(RagOutcomeStatus.DEGRADED, outcome.status());
        assertEquals(0, outcome.warnings().stream()
                .filter(warning -> warning.code().equals("ORCHESTRATION_INSUFFICIENT_EVIDENCE")).count());
    }


    @Test
    void selectsCodeStrategyOnFirstHopThenFallsBackToHybrid() {
        RetrievalRequest codeRequest = new RetrievalRequest("这个功能怎么实现",
                RetrievalProfile.DEVELOPMENT_PLAN, "game", "requirements", "5.1", 8);
        RetrievalStrategy code = mock(RetrievalStrategy.class);
        when(code.name()).thenReturn("code");
        when(code.execute(codeRequest)).thenReturn(success("code", 0, 0));
        RetrievalStrategy hybrid = mock(RetrievalStrategy.class);
        when(hybrid.name()).thenReturn("hybrid");
        when(hybrid.execute(codeRequest)).thenReturn(success("hybrid", 1, 0));
        AgenticOrchestrator orchestrator = new AgenticOrchestrator(
                List.of(hybrid, code), new EvidenceReflector());

        RagOutcome<RetrievalBundle> outcome = orchestrator.execute(codeRequest);

        verify(code, times(1)).execute(codeRequest);
        verify(hybrid, times(1)).execute(codeRequest);
        assertEquals(RagOutcomeStatus.SUCCESS, outcome.status());
    }

    @Test
    void rejectsEmptyStrategyList() {
        assertThrows(IllegalArgumentException.class,
                () -> new AgenticOrchestrator(List.of(), new EvidenceReflector()));
    }
}
