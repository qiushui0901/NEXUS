package com.example.requirementrag.retrieval.agentic;

import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.model.RagOutcome;
import com.example.requirementrag.model.RagOutcomeStatus;
import com.example.requirementrag.retrieval.pipeline.RetrievalBundle;
import com.example.requirementrag.retrieval.pipeline.RetrievalPipeline;
import com.example.requirementrag.retrieval.pipeline.RetrievalProfile;
import com.example.requirementrag.retrieval.pipeline.RetrievalRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HybridCompositionStrategyTest {

    @Test
    void delegatesToPipelineAndPreservesBundle() {
        RetrievalPipeline pipeline = mock(RetrievalPipeline.class);
        RetrievalRequest request = new RetrievalRequest("query", RetrievalProfile.REQUIREMENT_REVIEW,
                "game", "requirements", "5.1", 8);
        ChunkRecord hit = new ChunkRecord("id", "requirements", "5.1", "feature.html",
                "p1", "parent", "child", "hash", 1, 1);
        RetrievalBundle bundle = new RetrievalBundle("query", RetrievalProfile.REQUIREMENT_REVIEW,
                "game", "requirements", "5.1", List.of(hit), List.of());
        RagOutcome<RetrievalBundle> outcome = RagOutcome.of(RagOutcomeStatus.SUCCESS, bundle, "qdrant", 5, 1);
        when(pipeline.execute(request)).thenReturn(outcome);

        HybridCompositionStrategy strategy = new HybridCompositionStrategy(pipeline);

        StrategyResult result = strategy.execute(request);
        verify(pipeline).execute(request);
        assertSame(bundle, result.bundle());
        assertEquals("hybrid", result.strategy());
        assertEquals(RagOutcomeStatus.SUCCESS, result.status());
        assertEquals(1, result.requirementHitCount());
    }

    @Test
    void propagatesDegradedOutcomeFromPipeline() {
        RetrievalPipeline pipeline = mock(RetrievalPipeline.class);
        RetrievalRequest request = new RetrievalRequest("query", RetrievalProfile.REQUIREMENT_REVIEW,
                "game", "requirements", "5.1", 8);
        RetrievalBundle bundle = new RetrievalBundle("query", RetrievalProfile.REQUIREMENT_REVIEW,
                "game", "requirements", "5.1", List.of(), List.of());
        when(pipeline.execute(request)).thenReturn(RagOutcome.of(RagOutcomeStatus.DEGRADED, bundle,
                "qdrant.hybrid_search", 3, 0));

        StrategyResult result = new HybridCompositionStrategy(pipeline).execute(request);

        assertEquals(RagOutcomeStatus.DEGRADED, result.status());
        assertEquals(0, result.requirementHitCount());
        assertEquals(0, result.codeHitCount());
    }
}
