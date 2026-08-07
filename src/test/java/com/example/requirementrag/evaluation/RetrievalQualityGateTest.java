package com.example.requirementrag.evaluation;

import com.example.requirementrag.code.CodeKnowledgeService;
import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.QueryRouting;
import com.example.requirementrag.model.RagOutcome;
import com.example.requirementrag.model.RagOutcomeStatus;
import com.example.requirementrag.observability.RagObservability;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import com.example.requirementrag.retrieval.pipeline.RetrievalBundle;
import com.example.requirementrag.retrieval.pipeline.RetrievalPipeline;
import com.example.requirementrag.retrieval.pipeline.RetrievalProfile;
import com.example.requirementrag.retrieval.pipeline.RetrievalRequest;
import com.example.requirementrag.service.QueryRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 确定性检索质量门禁：用冻结评测集的需求评审用例（HIT 类）驱动 mocked 检索链路，
 * 断言黄金命中不被去重/代表选择/截断等确定性逻辑误杀。
 * 不依赖 Qdrant、Embedding 或 BGE 服务，随 CI 默认执行。
 * 污染过滤由 Qdrant filter 负责，由真实依赖的 RetrievalEvaluationIT 覆盖。
 */
class RetrievalQualityGateTest {

    private static final String DATASET = "evaluation/retrieval-eval-shiguang-v1.jsonl";
    private static final int TOP_K = 10;

    private RagProperties properties;
    private ProjectRegistry projectRegistry;
    private QueryRouter queryRouter;
    private QdrantHybridStore documentStore;
    private CodeKnowledgeService codeKnowledgeService;
    private RetrievalPipeline pipeline;

    @BeforeEach
    void setUp() {
        properties = mock(RagProperties.class);
        projectRegistry = mock(ProjectRegistry.class);
        queryRouter = mock(QueryRouter.class);
        documentStore = mock(QdrantHybridStore.class);
        codeKnowledgeService = mock(CodeKnowledgeService.class);
        when(properties.knowledge()).thenReturn(new RagProperties.Knowledge(
                false, null, null, "requirements", "5.1", null, null, 0));
        when(properties.retrieval()).thenReturn(new RagProperties.Retrieval(
                50, 50, 40, 20, 10, false, 1_000, 3, 3, 30_000,
                -1, -1, -1, -1, null, null, null, null, null));
        pipeline = new RetrievalPipeline(properties, projectRegistry, queryRouter, documentStore,
                codeKnowledgeService, mock(RagObservability.class));
    }

    @Test
    void everyGoldenRequirementHitSurvivesTheDeterministicPipeline() throws Exception {
        List<RetrievalEvaluationCase> cases = RetrievalEvaluationDataset.loadResource(DATASET).stream()
                .filter(c -> c.profile() == RetrievalEvaluationCase.RetrievalProfile.REQUIREMENT_REVIEW)
                .filter(c -> c.expectedOutcome() == RetrievalEvaluationCase.ExpectedOutcome.HIT)
                .toList();
        assertThat(cases).isNotEmpty();

        List<String> failures = new ArrayList<>();
        for (RetrievalEvaluationCase testCase : cases) {
            failures.addAll(assertCase(testCase));
        }
        assertThat(failures).as("golden hits lost by deterministic pipeline").isEmpty();
    }

    private List<String> assertCase(RetrievalEvaluationCase testCase) throws Exception {
        List<String> failures = new ArrayList<>();
        when(queryRouter.routeWithOutcome(eq(testCase.query()), any()))
                .thenReturn(RagOutcome.of(RagOutcomeStatus.SUCCESS,
                        new QueryRouting(testCase.projectId(), "server", 1.0, "explicit"),
                        "query.route", 1, 1));
        when(projectRegistry.resolveRequirementCollection(testCase.projectId()))
                .thenReturn("requirements_" + testCase.projectId());

        List<ChunkRecord> candidates = goldChunks(testCase);
        candidates.addAll(noiseChunks(testCase));
        when(documentStore.hybridSearch(any(), eq(testCase.query()), eq(testCase.documentId()), eq(testCase.version())))
                .thenReturn(candidates);

        RagOutcome<RetrievalBundle> outcome = pipeline.execute(new RetrievalRequest(testCase.query(),
                RetrievalProfile.REQUIREMENT_REVIEW, testCase.projectId(),
                testCase.documentId(), testCase.version(), TOP_K));

        for (RetrievalEvaluationCase.GoldDocument gold : testCase.goldDocuments()) {
            boolean hit = outcome.data().requirementEvidence().stream()
                    .anyMatch(chunk -> gold.filename().equals(chunk.filename())
                            && (gold.parentOrder() == null || gold.parentOrder().equals(chunk.parentOrder())));
            if (!hit) {
                failures.add(testCase.id() + " lost gold " + gold.filename() + "#" + gold.parentOrder());
            }
        }
        return failures;
    }

    private List<ChunkRecord> goldChunks(RetrievalEvaluationCase testCase) {
        List<ChunkRecord> chunks = new ArrayList<>();
        for (RetrievalEvaluationCase.GoldDocument gold : testCase.goldDocuments()) {
            chunks.add(chunk(testCase, gold.filename(), gold.parentOrder() == null ? 1 : gold.parentOrder(), "gold"));
        }
        return chunks;
    }

    private List<ChunkRecord> noiseChunks(RetrievalEvaluationCase testCase) {
        List<ChunkRecord> chunks = new ArrayList<>();
        chunks.add(chunk(testCase, "unrelated.html", 1, "noise-a"));
        chunks.add(chunk(testCase, "unrelated.html", 2, "noise-b"));
        chunks.add(chunk(testCase, testCase.documentId() + "-other.html", 3, "noise-c"));
        return chunks;
    }

    private ChunkRecord chunk(RetrievalEvaluationCase testCase, String filename, int parentOrder, String marker) {
        String text = marker + "-" + filename + "-" + parentOrder;
        return new ChunkRecord("id-" + text, testCase.documentId(), testCase.version(), filename,
                "parent-" + text, text, text, "hash-" + text, parentOrder, 1);
    }
}
