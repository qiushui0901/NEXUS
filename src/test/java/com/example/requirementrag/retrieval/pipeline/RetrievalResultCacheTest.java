package com.example.requirementrag.retrieval.pipeline;

import com.example.requirementrag.model.RagOutcome;
import com.example.requirementrag.model.RagOutcomeStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalResultCacheTest {

    private RetrievalRequest request(String query) {
        return new RetrievalRequest(query, RetrievalProfile.REQUIREMENT_REVIEW,
                "project-a", "doc-a", "5.1", 10);
    }

    private RetrievalResultCache cache() {
        return new RetrievalResultCache(Duration.ofSeconds(60), 100, "fingerprint");
    }

    private RagOutcome<RetrievalBundle> outcome() {
        return RagOutcome.of(RagOutcomeStatus.NO_RESULTS,
                new RetrievalBundle("query", RetrievalProfile.REQUIREMENT_REVIEW,
                        "project-a", "doc-a", "5.1", List.of(), List.of(), List.of()),
                "retrieval.test", 1, 0);
    }

    @Test
    void invalidatesEntriesForDocumentVersion() {
        RetrievalResultCache cache = cache();
        cache.put(request("query"), "project-a", "doc-a", "5.1", 10, outcome());

        cache.invalidate("doc-a", "5.1");

        assertThat(cache.get(request("query"), "project-a", "doc-a", "5.1", 10)).isEmpty();
    }

    @Test
    void invalidateDoesNotTouchOtherVersions() {
        RetrievalResultCache cache = cache();
        cache.put(request("query"), "project-a", "doc-a", "5.1", 10, outcome());
        cache.put(new RetrievalRequest("query", RetrievalProfile.REQUIREMENT_REVIEW,
                "project-a", "doc-a", "6.0", 10), "project-a", "doc-a", "6.0", 10, outcome());

        cache.invalidate("doc-a", "5.1");

        assertThat(cache.get(request("query"), "project-a", "doc-a", "5.1", 10)).isEmpty();
        assertThat(cache.get(new RetrievalRequest("query", RetrievalProfile.REQUIREMENT_REVIEW,
                "project-a", "doc-a", "6.0", 10), "project-a", "doc-a", "6.0", 10)).isPresent();
    }
}
