package com.example.requirementrag.retrieval.agentic;

import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.RagOutcomeStatus;
import com.example.requirementrag.retrieval.agentic.EvidenceReflector.ReflectionVerdict;
import com.example.requirementrag.retrieval.pipeline.RetrievalBundle;
import com.example.requirementrag.retrieval.pipeline.RetrievalProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EvidenceReflectorTest {

    private static final int MIN_HITS = 1;

    private static ChunkRecord hit(String id) {
        return new ChunkRecord(id, "requirements", "5.1", "feature.html",
                "p-" + id, "parent", "child", "hash", 1, 1);
    }

    private static StrategyResult result(RetrievalProfile profile, RagOutcomeStatus status, int requirementHits) {
        List<ChunkRecord> hits = requirementHits == 0 ? List.of()
                : List.of(hit("h1"), hit("h2")).subList(0, requirementHits);
        RetrievalBundle bundle = new RetrievalBundle("query", profile, "game", "requirements", "5.1",
                hits, List.of());
        return new StrategyResult("hybrid", bundle, status, List.of(), List.of());
    }

    @Test
    void confidentWhenRequirementHitsMeetThreshold() {
        EvidenceReflector reflector = new EvidenceReflector(MIN_HITS);
        assertEquals(ReflectionVerdict.CONFIDENT,
                reflector.evaluate(result(RetrievalProfile.REQUIREMENT_REVIEW, RagOutcomeStatus.SUCCESS, 1)).verdict());
    }

    @Test
    void insufficientWhenRequirementHitsBelowThreshold() {
        EvidenceReflector reflector = new EvidenceReflector(3);
        assertEquals(ReflectionVerdict.INSUFFICIENT,
                reflector.evaluate(result(RetrievalProfile.REQUIREMENT_REVIEW, RagOutcomeStatus.SUCCESS, 2)).verdict());
    }

    @Test
    void notRetrievableWhenCoreStageFailed() {
        EvidenceReflector reflector = new EvidenceReflector(MIN_HITS);
        assertEquals(ReflectionVerdict.NOT_RETRIEVABLE,
                reflector.evaluate(result(RetrievalProfile.REQUIREMENT_REVIEW, RagOutcomeStatus.FAILED, 0)).verdict());
    }


    @Test
    void reportsStableReasonCodes() {
        EvidenceReflector reflector = new EvidenceReflector(MIN_HITS);
        assertEquals("BELOW_MIN_HITS", reflector.evaluate(
                result(RetrievalProfile.REQUIREMENT_REVIEW, RagOutcomeStatus.SUCCESS, 0)).reasonCode());
        assertEquals("CORE_STAGE_FAILED", reflector.evaluate(
                result(RetrievalProfile.REQUIREMENT_REVIEW, RagOutcomeStatus.FAILED, 0)).reasonCode());
        assertEquals("HIT_THRESHOLD_MET", reflector.evaluate(
                result(RetrievalProfile.REQUIREMENT_REVIEW, RagOutcomeStatus.SUCCESS, 1)).reasonCode());
    }

    @Test
    void duplicateOnlyEvidenceIsInsufficient() {
        EvidenceReflector reflector = new EvidenceReflector(MIN_HITS);
        StrategyResult duplicate = new StrategyResult("hybrid",
                new RetrievalBundle("query", RetrievalProfile.REQUIREMENT_REVIEW, "game",
                        "requirements", "5.1", List.of(hit("h1"), hit("h1")), List.of()),
                RagOutcomeStatus.SUCCESS, List.of(), List.of());
        EvidenceReflector.ReflectionResult reflection = reflector.evaluate(duplicate);
        assertEquals(ReflectionVerdict.INSUFFICIENT, reflection.verdict());
        assertEquals("DUPLICATE_ONLY", reflection.reasonCode());
    }

    @Test
    void singleSidedEvidenceIsInsufficientForDualProfile() {
        EvidenceReflector reflector = new EvidenceReflector(MIN_HITS);
        StrategyResult singleSided = new StrategyResult("hybrid",
                new RetrievalBundle("query", RetrievalProfile.DEVELOPMENT_PLAN, "game",
                        "requirements", "5.1", List.of(hit("h1")), List.of()),
                RagOutcomeStatus.SUCCESS, List.of(), List.of());
        EvidenceReflector.ReflectionResult reflection = reflector.evaluate(singleSided);
        assertEquals(ReflectionVerdict.INSUFFICIENT, reflection.verdict());
        assertEquals("SINGLE_SIDE_ONLY", reflection.reasonCode());
    }

    @Test
    void notRetrievableWhenNull() {
        EvidenceReflector reflector = new EvidenceReflector(MIN_HITS);
        assertEquals(ReflectionVerdict.NOT_RETRIEVABLE, reflector.evaluate(null).verdict());
    }
}
