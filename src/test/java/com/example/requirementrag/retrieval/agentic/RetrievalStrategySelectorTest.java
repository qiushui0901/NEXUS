package com.example.requirementrag.retrieval.agentic;

import com.example.requirementrag.retrieval.pipeline.RetrievalProfile;
import com.example.requirementrag.retrieval.pipeline.RetrievalRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetrievalStrategySelectorTest {

    private final RetrievalStrategy requirements = mock(RetrievalStrategy.class);
    private final RetrievalStrategy code = mock(RetrievalStrategy.class);
    private final RetrievalStrategy hybrid = mock(RetrievalStrategy.class);
    private final List<RetrievalStrategy> pool = List.of(hybrid, requirements, code);
    private final RetrievalStrategySelector selector =
            new RetrievalStrategySelector.RuleBasedRetrievalStrategySelector();

    @org.junit.jupiter.api.BeforeEach
    void stubNames() {
        when(requirements.name()).thenReturn("requirements");
        when(code.name()).thenReturn("code");
        when(hybrid.name()).thenReturn("hybrid");
    }

    @Test
    void selectsRequirementsForReviewProfile() {
        var selected = selector.select(pool, new RetrievalRequest("封神5.1存疑", RetrievalProfile.REQUIREMENT_REVIEW,
                "game", "requirements", "5.1", 8));
        assertTrue(selected.isPresent());
        assertEquals(requirements, selected.get());
    }

    @Test
    void selectsCodeForCodeIntentQuery() {
        var selected = selector.select(pool, new RetrievalRequest("这个功能怎么实现", RetrievalProfile.DEVELOPMENT_PLAN,
                "game", null, null, 8));
        assertTrue(selected.isPresent());
        assertEquals(code, selected.get());
    }

    @Test
    void returnsEmptyForAmbiguousQuery() {
        var selected = selector.select(pool, new RetrievalRequest("查询需求列表", RetrievalProfile.DEVELOPMENT_PLAN,
                "game", null, null, 8));
        assertTrue(selected.isEmpty());
    }

    @Test
    void fallsBackWhenNamedStrategyMissingFromPool() {
        var selected = selector.select(List.of(hybrid), new RetrievalRequest("封神5.1存疑",
                RetrievalProfile.REQUIREMENT_REVIEW, "game", "requirements", "5.1", 8));
        assertTrue(selected.isEmpty());
    }
}
