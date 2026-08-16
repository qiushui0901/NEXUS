package com.example.requirementrag.evolution.evaluation;

import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.evolution.mining.EvaluationCandidate;
import com.example.requirementrag.evolution.mining.EvaluationCandidateStore;
import com.example.requirementrag.evolution.mining.FailureType;
import com.example.requirementrag.evolution.mining.ReviewStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvaluationCaseReviewServiceTest {

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

    @Test
    void publishesApprovedCandidatesAsImmutableDataset() {
        EvaluationCandidateStore candidateStore = new EvaluationCandidateStore(new ObjectMapper().findAndRegisterModules(), properties());
        EvaluationDatasetRegistry datasetRegistry = new EvaluationDatasetRegistry(new ObjectMapper().findAndRegisterModules(), properties());
        EvaluationCaseReviewService service = new EvaluationCaseReviewService(candidateStore, datasetRegistry);

        candidateStore.save(new EvaluationCandidate("c1", "e1", "qhash", "query", FailureType.NO_HIT,
                "no hit", "idx", List.of("r1"), 1.0, ReviewStatus.APPROVED, "reviewer", null));

        EvaluationDataset dataset = service.publishApproved("ds-1");

        assertThat(dataset.version()).isEqualTo("ds-1");
        assertThat(dataset.cases()).hasSize(1);
        assertThat(datasetRegistry.active().version()).isEqualTo("ds-1");
        assertThat(candidateStore.findById("c1").reviewStatus()).isEqualTo(ReviewStatus.PUBLISHED);
    }

    @Test
    void rejectsIllegalTransition() {
        EvaluationCandidateStore candidateStore = new EvaluationCandidateStore(new ObjectMapper().findAndRegisterModules(), properties());
        EvaluationDatasetRegistry datasetRegistry = new EvaluationDatasetRegistry(new ObjectMapper().findAndRegisterModules(), properties());
        EvaluationCaseReviewService service = new EvaluationCaseReviewService(candidateStore, datasetRegistry);

        candidateStore.save(new EvaluationCandidate("c1", "e1", "qhash", "query", FailureType.NO_HIT,
                "no hit", "idx", List.of("r1"), 1.0, ReviewStatus.DRAFT, null, null));

        assertThatThrownBy(() -> service.transition("c1", ReviewStatus.PUBLISHED, "reviewer"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void approvalRequiresHumanConfirmedRelevantIds() {
        EvaluationCandidateStore candidateStore = new EvaluationCandidateStore(new ObjectMapper().findAndRegisterModules(), properties());
        EvaluationDatasetRegistry datasetRegistry = new EvaluationDatasetRegistry(new ObjectMapper().findAndRegisterModules(), properties());
        EvaluationCaseReviewService service = new EvaluationCaseReviewService(candidateStore, datasetRegistry);

        candidateStore.save(new EvaluationCandidate("c1", "e1", "qhash", "query", FailureType.NO_HIT,
                "no hit", "idx", List.of(), 1.0, ReviewStatus.IN_REVIEW, "reviewer", null));

        assertThatThrownBy(() -> service.updateAndTransition("c1", ReviewStatus.APPROVED,
                List.of(), null, "reviewer"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("relevant IDs");
    }

    @Test
    void reviewerCanSetRelevantIdsBeforePublish() {
        EvaluationCandidateStore candidateStore = new EvaluationCandidateStore(new ObjectMapper().findAndRegisterModules(), properties());
        EvaluationDatasetRegistry datasetRegistry = new EvaluationDatasetRegistry(new ObjectMapper().findAndRegisterModules(), properties());
        EvaluationCaseReviewService service = new EvaluationCaseReviewService(candidateStore, datasetRegistry);

        candidateStore.save(new EvaluationCandidate("c1", "e1", "qhash", "query", FailureType.NO_HIT,
                "no hit", "idx", List.of(), 1.0, ReviewStatus.IN_REVIEW, "reviewer", null));

        service.updateAndTransition("c1", ReviewStatus.APPROVED, List.of("gold-1"), null, "reviewer");
        EvaluationDataset dataset = service.publishApproved("ds-1");

        assertThat(dataset.cases()).hasSize(1);
        assertThat(dataset.cases().get(0).relevantIds()).containsExactly("gold-1");
        assertThat(dataset.cases().get(0).query()).isEqualTo("query");
    }
}
