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
import com.example.requirementrag.retrieval.SparseVectorizer;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import com.example.requirementrag.service.QueryRouter;
import com.example.requirementrag.service.RagUnavailableException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;

/** Shared orchestration for routing, requirement retrieval, code retrieval and degradation semantics. */
@Service
public class RetrievalPipeline {
    private static final String DOCUMENT_STAGE = "qdrant.hybrid_search";
    private static final String DOCUMENT_CORPUS_STAGE = "qdrant.scroll";
    private static final String CODE_STAGE = "code.hybrid_search";
    private static final double MIN_REPRESENTATIVE_ABSOLUTE_GAIN = 0.01;
    private static final double MIN_REPRESENTATIVE_RELATIVE_GAIN = 0.10;

    private final RagProperties properties;
    private final ProjectRegistry projectRegistry;
    private final QueryRouter queryRouter;
    private final QdrantHybridStore documentStore;
    private final CodeKnowledgeService codeKnowledgeService;
    private final RagObservability observability;
    private final RequirementReranker requirementReranker;
    private final RetrievalResultCache resultCache;
    private final Executor retrievalExecutor;
    private final RetrievalCircuitBreaker circuitBreaker;
    private final SparseVectorizer sparseVectorizer;

    @Autowired
    public RetrievalPipeline(RagProperties properties, ProjectRegistry projectRegistry, QueryRouter queryRouter,
                             QdrantHybridStore documentStore, CodeKnowledgeService codeKnowledgeService,
                             RagObservability observability, RequirementReranker requirementReranker,
                             RetrievalResultCache resultCache,
                             @Qualifier("retrievalExecutor") Executor retrievalExecutor,
                             RetrievalCircuitBreaker circuitBreaker,
                             SparseVectorizer sparseVectorizer) {
        this.properties = properties;
        this.projectRegistry = projectRegistry;
        this.queryRouter = queryRouter;
        this.documentStore = documentStore;
        this.codeKnowledgeService = codeKnowledgeService;
        this.observability = observability;
        this.requirementReranker = requirementReranker;
        this.resultCache = resultCache;
        this.retrievalExecutor = retrievalExecutor;
        this.circuitBreaker = circuitBreaker;
        this.sparseVectorizer = sparseVectorizer;
    }

    /** Compatibility constructor for focused tests that do not use Spring injection. */
    public RetrievalPipeline(RagProperties properties, ProjectRegistry projectRegistry, QueryRouter queryRouter,
                             QdrantHybridStore documentStore, CodeKnowledgeService codeKnowledgeService,
                             RagObservability observability, RequirementReranker requirementReranker,
                             RetrievalResultCache resultCache, Executor retrievalExecutor,
                             RetrievalCircuitBreaker circuitBreaker) {
        this(properties, projectRegistry, queryRouter, documentStore, codeKnowledgeService, observability,
                requirementReranker, resultCache, retrievalExecutor, circuitBreaker, new SparseVectorizer());
    }

    /** Compatibility constructor for focused unit tests and pre-0.8 callers. */
    public RetrievalPipeline(RagProperties properties, ProjectRegistry projectRegistry, QueryRouter queryRouter,
                             QdrantHybridStore documentStore, CodeKnowledgeService codeKnowledgeService,
                             RagObservability observability) {
        this(properties, projectRegistry, queryRouter, documentStore, codeKnowledgeService, observability,
                RequirementReranker.passthrough(),
                new RetrievalResultCache(Duration.ZERO, 0, "disabled"), Runnable::run,
                new RetrievalCircuitBreaker(0, Duration.ZERO));
    }

    public RagOutcome<RetrievalBundle> execute(RetrievalRequest request) {
        String documentId = hasText(request.documentId()) ? request.documentId() : properties.knowledge().documentId();
        String version = hasText(request.version()) ? request.version() : properties.knowledge().version();
        int limit = Math.min(Math.max(request.limit() == null ? 8 : request.limit(), 1), 50);

        RagOutcome<QueryRouting> routing = queryRouter.routeWithOutcome(request.query(), request.projectId());
        recordOutcome(routing, documentId, version);
        String projectId = routing.data().projectId();
        var cached = resultCache.get(request, projectId, documentId, version, limit);
        if (cached.isPresent()) {
            observability.event("retrieval_cache_hit");
            return cached.get();
        }
        observability.event("retrieval_cache_miss");

        String requirementCollection = request.profile().usesRequirementEvidence()
                ? projectRegistry.resolveRequirementCollection(projectId) : null;
        long timeoutMs = properties.retrieval() == null
                ? 5_000 : properties.retrieval().resolvedBranchTimeoutMs();
        CompletableFuture<RagOutcome<List<ChunkRecord>>> requirementFuture =
                request.profile().usesRequirementEvidence()
                ? submit(DOCUMENT_STAGE, () -> retrieve(DOCUMENT_STAGE, documentId, version,
                        "DOCUMENT_RETRIEVAL_UNAVAILABLE", "需求文档检索暂时不可用",
                        () -> documentStore.hybridSearch(requirementCollection, request.query(), documentId, version)),
                        timeoutMs)
                : completed(DOCUMENT_STAGE);
        CompletableFuture<RagOutcome<List<ChunkRecord>>> corpusFuture = request.includeVersionCorpus()
                ? submit(DOCUMENT_CORPUS_STAGE, () -> retrieve(DOCUMENT_CORPUS_STAGE, documentId, version,
                        "DOCUMENT_CORPUS_UNAVAILABLE", "需求文档正文暂时不可用",
                        () -> documentStore.scrollVersion(requirementCollection, documentId, version)),
                        timeoutMs)
                : completed(DOCUMENT_CORPUS_STAGE);
        CompletableFuture<RagOutcome<List<CodeChunk>>> codeFuture = request.profile().usesCodeEvidence()
                ? submit(CODE_STAGE, () -> retrieve(CODE_STAGE, documentId, version,
                        "CODE_RETRIEVAL_UNAVAILABLE", "代码检索暂时不可用",
                        () -> codeKnowledgeService.search(request.query(), projectId, limit)),
                        timeoutMs)
                : completed(CODE_STAGE);

        RagOutcome<List<ChunkRecord>> requirementOutcome = await(requirementFuture, DOCUMENT_STAGE,
                "DOCUMENT_RETRIEVAL_TIMEOUT", "需求文档检索超时", documentId, version);
        RagOutcome<List<ChunkRecord>> corpusOutcome = await(corpusFuture, DOCUMENT_CORPUS_STAGE,
                "DOCUMENT_CORPUS_TIMEOUT", "需求文档正文读取超时", documentId, version);
        RagOutcome<List<CodeChunk>> codeOutcome = await(codeFuture, CODE_STAGE,
                "CODE_RETRIEVAL_TIMEOUT", "代码检索超时", documentId, version);

        RagProperties.Retrieval retrieval = properties.retrieval();
        boolean childFirstRerank = retrieval != null && retrieval.resolvedChildFirstRerankEnabled();
        List<ChunkRecord> requirementCandidates = deduplicate(requirementOutcome.data(),
                childFirstRerank ? this::requirementCandidateKey : this::requirementKey);
        List<ChunkRecord> rerankCandidates = optimizeSingleParentRerank(
                request.query(), requirementCandidates, childFirstRerank);
        int rerankLimit = childFirstRerank
                ? Math.max(limit, retrieval.resolvedBgeTopK())
                : limit;
        RagOutcome<List<ChunkRecord>> rerankOutcome = request.profile().usesRequirementEvidence()
                ? requirementReranker.rerank(request.query(), documentId, version, rerankCandidates, rerankLimit)
                : RagOutcome.of(RagOutcomeStatus.NO_RESULTS, List.of(), "retrieval.rerank", 0, 0);
        List<ChunkRecord> requirements = selectParentRepresentatives(
                request.query(), rerankOutcome.data(), childFirstRerank)
                .stream().limit(limit).toList();
        List<ChunkRecord> corpus = deduplicate(corpusOutcome.data(), this::requirementKey);
        List<CodeChunk> code = deduplicate(codeOutcome.data(), this::codeKey).stream().limit(limit).toList();
        List<RagWarning> warnings = new ArrayList<>();
        List<RagStageDiagnostic> diagnostics = new ArrayList<>();
        collect(routing, warnings, diagnostics);
        collect(requirementOutcome, warnings, diagnostics);
        if (request.profile().usesRequirementEvidence()) {
            collect(rerankOutcome, warnings, diagnostics);
            diagnostics.add(new RagStageDiagnostic("retrieval.requirement.candidates",
                    requirementCandidates.isEmpty() ? RagOutcomeStatus.NO_RESULTS : RagOutcomeStatus.SUCCESS,
                    0, requirementCandidates.size()));
            diagnostics.add(new RagStageDiagnostic("retrieval.requirement.rerank_candidates",
                    rerankCandidates.isEmpty() ? RagOutcomeStatus.NO_RESULTS : RagOutcomeStatus.SUCCESS,
                    0, rerankCandidates.size()));
            diagnostics.add(new RagStageDiagnostic("retrieval.requirement.parents",
                    requirements.isEmpty() ? RagOutcomeStatus.NO_RESULTS : RagOutcomeStatus.SUCCESS,
                    0, requirements.size()));
        }
        if (request.includeVersionCorpus()) {
            collect(corpusOutcome, warnings, diagnostics);
        }
        collect(codeOutcome, warnings, diagnostics);

        boolean failedCoreStage = requirementOutcome.status() == RagOutcomeStatus.FAILED
                || request.includeVersionCorpus() && corpusOutcome.status() == RagOutcomeStatus.FAILED
                || codeOutcome.status() == RagOutcomeStatus.FAILED;
        if (requirements.isEmpty() && corpus.isEmpty() && code.isEmpty() && failedCoreStage) {
            throw new RagUnavailableException(warnings);
        }

        RagOutcomeStatus status = !warnings.isEmpty()
                ? RagOutcomeStatus.DEGRADED
                : requirements.isEmpty() && corpus.isEmpty() && code.isEmpty()
                        ? RagOutcomeStatus.NO_RESULTS : RagOutcomeStatus.SUCCESS;
        RetrievalBundle bundle = new RetrievalBundle(request.query(), request.profile(), projectId, documentId,
                version, requirements, corpus, code);
        RagOutcome<RetrievalBundle> outcome = new RagOutcome<>(status, bundle, warnings, diagnostics);
        if (status == RagOutcomeStatus.SUCCESS || status == RagOutcomeStatus.NO_RESULTS) {
            resultCache.put(request, projectId, documentId, version, limit, outcome);
        }
        return outcome;
    }

    /**
     * Scoring several children cannot change the final parent order when every candidate belongs to the same parent.
     * Select the query-relevant representative in that special case before skipping redundant BGE scoring.
     * Multi-parent candidate sets still reach BGE child-first without an early parent collapse.
     */
    private List<ChunkRecord> optimizeSingleParentRerank(
            String query, List<ChunkRecord> candidates, boolean childFirstRerank) {
        if (!childFirstRerank || candidates.size() <= 1) {
            return candidates;
        }
        String firstParent = requirementKey(candidates.getFirst());
        boolean singleParent = candidates.stream().allMatch(candidate -> requirementKey(candidate).equals(firstParent));
        return singleParent ? selectParentRepresentatives(query, candidates, true) : candidates;
    }

    /**
     * Keep BGE's first occurrence as the parent rank, then choose the most query-relevant child inside
     * that parent. Enriched BGE passages contain shared parent context, so sibling scores can be dominated
     * by the same parent text; the child-only sparse score selects a more precise evidence representative
     * without changing parent ordering or making another external rerank call.
     */
    private List<ChunkRecord> selectParentRepresentatives(
            String query, List<ChunkRecord> rankedChildren, boolean childFirstRerank) {
        if (!childFirstRerank) {
            return deduplicate(rankedChildren, this::requirementKey);
        }
        Map<String, List<ChunkRecord>> childrenByParent = new LinkedHashMap<>();
        for (ChunkRecord child : rankedChildren == null ? List.<ChunkRecord>of() : rankedChildren) {
            if (child != null) {
                childrenByParent.computeIfAbsent(requirementKey(child), ignored -> new ArrayList<>()).add(child);
            }
        }
        List<ChunkRecord> representatives = new ArrayList<>(childrenByParent.size());
        for (List<ChunkRecord> siblings : childrenByParent.values()) {
            ChunkRecord representative = siblings.getFirst();
            double bestScore = sparseVectorizer.similarity(query, representative.childText());
            for (int index = 1; index < siblings.size(); index++) {
                ChunkRecord candidate = siblings.get(index);
                double score = sparseVectorizer.similarity(query, candidate.childText());
                double absoluteGain = score - bestScore;
                double relativeThreshold = bestScore * (1 + MIN_REPRESENTATIVE_RELATIVE_GAIN);
                if (absoluteGain >= MIN_REPRESENTATIVE_ABSOLUTE_GAIN && score > relativeThreshold) {
                    representative = candidate;
                    bestScore = score;
                }
            }
            representatives.add(representative);
        }
        return List.copyOf(representatives);
    }

    private <T> CompletableFuture<RagOutcome<List<T>>> submit(String stage,
                                                               Supplier<RagOutcome<List<T>>> action,
                                                               long timeoutMs) {
        if (!circuitBreaker.allow(stage)) {
            return CompletableFuture.completedFuture(RagOutcome.failed(List.of(), stage,
                    "RETRIEVAL_CIRCUIT_OPEN", "检索依赖暂时熔断", 0));
        }
        return CompletableFuture.supplyAsync(action, retrievalExecutor)
                .orTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private <T> CompletableFuture<RagOutcome<List<T>>> completed(String stage) {
        return CompletableFuture.completedFuture(
                RagOutcome.of(RagOutcomeStatus.NO_RESULTS, List.of(), stage, 0, 0));
    }

    private <T> RagOutcome<List<T>> await(CompletableFuture<RagOutcome<List<T>>> future, String stage,
                                          String warningCode, String warningMessage,
                                          String documentId, String version) {
        long started = System.nanoTime();
        try {
            RagOutcome<List<T>> outcome = future.get();
            if (outcome.status() == RagOutcomeStatus.FAILED) circuitBreaker.failure(stage);
            else circuitBreaker.success(stage);
            return outcome;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            long duration = elapsedMillis(started);
            return RagOutcome.failed(List.of(), stage, warningCode, warningMessage, duration);
        } catch (ExecutionException exception) {
            long duration = elapsedMillis(started);
            RuntimeException failure = exception.getCause() instanceof TimeoutException
                    ? new IllegalStateException("retrieval branch timed out", exception.getCause())
                    : new IllegalStateException("retrieval branch failed", exception.getCause());
            circuitBreaker.failure(stage);
            observability.outcome(stage, documentId, version, RagOutcomeStatus.FAILED,
                    duration, warningCode, failure);
            return RagOutcome.failed(List.of(), stage, warningCode, warningMessage, duration);
        }
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

    private String requirementCandidateKey(ChunkRecord chunk) {
        return hasText(chunk.id()) ? chunk.id()
                : requirementKey(chunk) + ':' + chunk.childOrder() + ':' + chunk.contentHash();
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
