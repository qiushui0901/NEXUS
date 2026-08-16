package com.example.requirementrag.evolution.mining;

import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.evolution.experience.RetrievalExperience;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalFailureMinerTest {

    @TempDir
    Path tempDir;

    private RagProperties properties() {
        return new RagProperties(
                new RagProperties.Qdrant("http://localhost:6333", "requirement_chunks"),
                new RagProperties.Bge("http://localhost:8081", "/rerank", null),
                new RagProperties.Llm("claude-sonnet-5", "claude-sonnet-4.6", null, null, null),
                new RagProperties.Retrieval(50, 50, 40, 20, 10, true, 5_000, 6, 3, 30_000,
                        120, 1000, 900, 10000, null, null, null, null, null, 0.0),
                new RagProperties.Knowledge(false, null, null, "requirements", "5.1", null, null, 800),
                new RagProperties.Review(12, 15, 12, 3),
                new RagProperties.Code("demo", "/tmp", "code_demo", List.of(), List.of(), 1_000_000),
                List.of(),
                new RagProperties.Evolution(true, true, 1.0, 1.0, 10, true, 30,
                        tempDir.resolve("experiences").toString(), tempDir.resolve("candidates").toString(),
                        tempDir.resolve("datasets").toString(), tempDir.resolve("policies").toString()));
    }

    private RetrievalExperience degraded(String id, String queryHash) {
        return new RetrievalExperience(
                RetrievalExperience.SCHEMA_VERSION, id, Instant.now(), "demo", "requirements", "5.1",
                queryHash, "query", "DEVELOPMENT_PLAN", "hybrid", List.of("hybrid"), 1,
                List.of(new RetrievalExperience.HopSnapshot(0, "hybrid", "INSUFFICIENT", "BELOW_MIN_HITS", 1)),
                List.of(), List.of(), List.of(), "INSUFFICIENT", "BELOW_MIN_HITS",
                "DEGRADED", List.of("ORCHESTRATION_INSUFFICIENT_EVIDENCE"), List.of(), 100, null,
                List.of(), null, "baseline-v1", "cfg", "idx", null);
    }

    @Test
    void createsCandidatesForDegradedExperiences() {
        EvaluationCandidateStore store = new EvaluationCandidateStore(new ObjectMapper().findAndRegisterModules(), properties());
        RetrievalFailureMiner miner = new RetrievalFailureMiner(store);

        List<EvaluationCandidate> candidates = miner.mine(List.of(
                degraded("e1", "q1"), degraded("e2", "q1")));

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).failureType()).isEqualTo(FailureType.DEGRADED_RESULT);
        assertThat(candidates.get(0).reviewStatus()).isEqualTo(ReviewStatus.DRAFT);
        assertThat(store.findAll()).hasSize(1);
    }
}
