package com.example.requirementrag.retrieval.pipeline;

import com.example.requirementrag.code.CodeKnowledgeService;
import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.model.QueryRouting;
import com.example.requirementrag.model.RagOutcome;
import com.example.requirementrag.model.RagOutcomeStatus;
import com.example.requirementrag.model.RagStageDiagnostic;
import com.example.requirementrag.model.RagWarning;
import com.example.requirementrag.observability.RagObservability;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import com.example.requirementrag.service.QueryRouter;
import com.example.requirementrag.service.RagUnavailableException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/** Shared orchestration for routing, requirement retrieval, code retrieval and degradation semantics. */
@Service
public class RetrievalPipeline {
    private static final String DOCUMENT_STAGE = "qdrant.hybrid_search";
    private static final String CODE_STAGE = "code.hybrid_search";

    private final RagProperties properties;
    private final ProjectRegistry projectRegistry;
    private final QueryRouter queryRouter;
    private final QdrantHybridStore documentStore;
    private final CodeKnowledgeService codeKnowledgeService;
    private final RagObservability observability;

    public RetrievalPipeline(RagProperties properties, ProjectRegistry projectRegistry, QueryRouter queryRouter,
                             QdrantHybridStore documentStore, CodeKnowledgeService codeKnowledgeService,
                             RagObservability observability) {
        this.properties = properties;
        this.projectRegistry = projectRegistry;
        this.queryRouter = queryRouter;
        this.documentStore = documentStore;
        this.codeKnowledgeService = codeKnowledgeService;
        this.observability = observability;
    }

    public RagOutcome<RetrievalBundle> execute(RetrievalRequest request) {
        String documentId = hasText(request.documentId()) ? request.documentId() : properties.knowledge().documentId();
        String version = hasText(request.version()) ? request.version() : properties.knowledge().version();
        int limit = Math.min(Math.max(request.limit() == null ? 8 : request.limit(), 1), 50);

        RagOutcome<QueryRouting> routing = queryRouter.routeWithOutcome(request.query(), request.projectId());
        recordOutcome(routing, documentId, version);
        String projectId = routing.data().projectId();

        RagOutcome<List<ChunkRecord>> requirementOutcome = request.profile().usesRequirementEvidence()
                ? retrieve(DOCUMENT_STAGE, documentId, version,
                        "DOCUMENT_RETRIEVAL_UNAVAILABLE", "需求文档检索暂时不可用",
                        () -> documentStore.hybridSearch(projectRegistry.resolveRequirementCollection(projectId),
                                request.query(), documentId, version))
                : RagOutcome.of(RagOutcomeStatus.NO_RESULTS, List.of(), DOCUMENT_STAGE, 0, 0);
        RagOutcome<List<CodeChunk>> codeOutcome = request.profile().usesCodeEvidence()
                ? retrieve(CODE_STAGE, documentId, version,
                        "CODE_RETRIEVAL_UNAVAILABLE", "代码检索暂时不可用",
                        () -> codeKnowledgeService.search(request.query(), projectId, limit))
                : RagOutcome.of(RagOutcomeStatus.NO_RESULTS, List.of(), CODE_STAGE, 0, 0);

        List<ChunkRecord> requirements = deduplicate(requirementOutcome.data(), this::requirementKey).stream()
                .limit(limit).toList();
        List<CodeChunk> code = deduplicate(codeOutcome.data(), this::codeKey).stream().limit(limit).toList();
        List<RagWarning> warnings = new ArrayList<>();
        List<RagStageDiagnostic> diagnostics = new ArrayList<>();
        collect(routing, warnings, diagnostics);
        collect(requirementOutcome, warnings, diagnostics);
        collect(codeOutcome, warnings, diagnostics);

        boolean failedCoreStage = requirementOutcome.status() == RagOutcomeStatus.FAILED
                || codeOutcome.status() == RagOutcomeStatus.FAILED;
        if (requirements.isEmpty() && code.isEmpty() && failedCoreStage) {
            throw new RagUnavailableException(warnings);
        }

        RagOutcomeStatus status = !warnings.isEmpty()
                ? RagOutcomeStatus.DEGRADED
                : requirements.isEmpty() && code.isEmpty() ? RagOutcomeStatus.NO_RESULTS : RagOutcomeStatus.SUCCESS;
        RetrievalBundle bundle = new RetrievalBundle(request.query(), request.profile(), projectId, documentId,
                version, requirements, code);
        return new RagOutcome<>(status, bundle, warnings, diagnostics);
    }

    private <T> RagOutcome<List<T>> retrieve(String stage, String documentId, String version,
                                              String warningCode, String warningMessage,
                                              Supplier<List<T>> action) {
        long started = System.nanoTime();
        try {
            List<T> result = action.get();
            List<T> data = result == null ? List.of() : List.copyOf(result);
            RagOutcomeStatus status = data.isEmpty() ? RagOutcomeStatus.NO_RESULTS : RagOutcomeStatus.SUCCESS;
            RagOutcome<List<T>> outcome = RagOutcome.of(status, data, stage, elapsedMillis(started), data.size());
            recordOutcome(outcome, documentId, version);
            return outcome;
        } catch (RuntimeException exception) {
            long durationMs = elapsedMillis(started);
            RagOutcome<List<T>> outcome = RagOutcome.failed(List.of(), stage, warningCode, warningMessage, durationMs);
            observability.outcome(stage, documentId, version, RagOutcomeStatus.FAILED,
                    durationMs, warningCode, exception);
            return outcome;
        }
    }

    private <T> List<T> deduplicate(List<T> values, Function<T, String> keyFunction) {
        Map<String, T> unique = new LinkedHashMap<>();
        for (T value : values == null ? List.<T>of() : values) {
            if (value != null) {
                unique.putIfAbsent(keyFunction.apply(value), value);
            }
        }
        return List.copyOf(unique.values());
    }

    private String requirementKey(ChunkRecord chunk) {
        return hasText(chunk.parentId()) ? chunk.parentId() : chunk.filename() + ':' + chunk.parentOrder();
    }

    private String codeKey(CodeChunk chunk) {
        return hasText(chunk.id()) ? chunk.id()
                : chunk.filePath() + ':' + chunk.symbolName() + ':' + chunk.startLine();
    }

    private void collect(RagOutcome<?> outcome, List<RagWarning> warnings,
                         List<RagStageDiagnostic> diagnostics) {
        warnings.addAll(outcome.warnings());
        diagnostics.addAll(outcome.stageDiagnostics());
    }

    private void recordOutcome(RagOutcome<?> outcome, String documentId, String version) {
        for (RagStageDiagnostic diagnostic : outcome.stageDiagnostics()) {
            String warningCode = outcome.warnings().isEmpty() ? null : outcome.warnings().getFirst().code();
            observability.outcome(diagnostic.stage(), documentId, version, diagnostic.status(),
                    diagnostic.durationMs(), warningCode, null);
        }
    }

    private long elapsedMillis(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
