package com.example.requirementrag.evaluation;

import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.model.RagOutcomeStatus;
import com.example.requirementrag.model.RagStageDiagnostic;
import com.example.requirementrag.model.RagWarning;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Pure stable-label matching and per-case scoring functions. */
public final class RetrievalEvaluationMatcher {
    public static final int DEFAULT_CUTOFF = 10;

    private RetrievalEvaluationMatcher() {
    }

    public static Integer firstFileRank(List<RetrievalEvaluationCase.GoldDocument> goldDocuments,
                                        List<ChunkRecord> candidates, int cutoff) {
        return firstMatchingDocumentRank(goldDocuments, candidates, cutoff,
                (gold, candidate) -> gold.filename().equals(candidate.filename()));
    }

    public static Integer firstSectionRank(List<RetrievalEvaluationCase.GoldDocument> goldDocuments,
                                           List<ChunkRecord> candidates, int cutoff) {
        return firstMatchingDocumentRank(goldDocuments.stream()
                        .filter(gold -> gold.parentOrder() != null)
                        .toList(),
                candidates, cutoff,
                (gold, candidate) -> gold.filename().equals(candidate.filename())
                        && gold.parentOrder() == candidate.parentOrder());
    }

    public static Integer firstChildRank(List<RetrievalEvaluationCase.GoldDocument> goldDocuments,
                                         List<ChunkRecord> candidates, int cutoff) {
        return firstMatchingDocumentRank(goldDocuments.stream()
                        .filter(gold -> gold.parentOrder() != null && gold.childOrder() != null)
                        .toList(),
                candidates, cutoff,
                (gold, candidate) -> gold.filename().equals(candidate.filename())
                        && gold.parentOrder() == candidate.parentOrder()
                        && gold.childOrder() == candidate.childOrder()
                        && containsAll(candidate.childText(), gold.mustContain()));
    }

    public static Integer firstDocumentRank(List<RetrievalEvaluationCase.GoldDocument> goldDocuments,
                                            List<ChunkRecord> candidates, int cutoff) {
        if (goldDocuments.stream().anyMatch(gold -> gold.childOrder() != null)) {
            return firstChildRank(goldDocuments, candidates, cutoff);
        }
        if (goldDocuments.stream().anyMatch(gold -> gold.parentOrder() != null)) {
            return firstSectionRank(goldDocuments, candidates, cutoff);
        }
        return firstFileRank(goldDocuments, candidates, cutoff);
    }

    private static Integer firstMatchingDocumentRank(
            List<RetrievalEvaluationCase.GoldDocument> goldDocuments,
            List<ChunkRecord> candidates,
            int cutoff,
            DocumentMatch match) {
        for (int index = 0; index < Math.min(cutoff, candidates.size()); index++) {
            ChunkRecord candidate = candidates.get(index);
            for (RetrievalEvaluationCase.GoldDocument gold : goldDocuments) {
                if (match.test(gold, candidate)) {
                    return index + 1;
                }
            }
        }
        return null;
    }

    public static Integer firstCodeRank(List<RetrievalEvaluationCase.GoldCode> goldCode,
                                        List<CodeChunk> candidates, int cutoff) {
        for (int i = 0; i < Math.min(cutoff, candidates.size()); i++) {
            CodeChunk candidate = candidates.get(i);
            for (RetrievalEvaluationCase.GoldCode gold : goldCode) {
                if (gold.projectId().equals(candidate.projectId())
                        && RetrievalEvaluationDataset.normalizePath(gold.filePath()).equals(
                        RetrievalEvaluationDataset.normalizePath(candidate.filePath()))
                        && gold.symbolName().equals(candidate.symbolName())) {
                    return i + 1;
                }
            }
        }
        return null;
    }

    public static CaseResult evaluate(RetrievalEvaluationCase c, List<ChunkRecord> documents, List<CodeChunk> code,
                                      long documentLatencyMs, long codeLatencyMs, long totalLatencyMs) {
        return evaluate(c, documents, code, documentLatencyMs, codeLatencyMs, totalLatencyMs,
                null, null, 1, List.of(), List.of(), EvaluationTrace.empty());
    }

    public static CaseResult evaluate(RetrievalEvaluationCase c, List<ChunkRecord> documents, List<CodeChunk> code,
                                      long documentLatencyMs, long codeLatencyMs, long totalLatencyMs,
                                      String documentError, String codeError) {
        return evaluate(c, documents, code, documentLatencyMs, codeLatencyMs, totalLatencyMs,
                documentError, codeError, 1, List.of(), List.of(), EvaluationTrace.empty());
    }

    public static CaseResult evaluate(RetrievalEvaluationCase c, List<ChunkRecord> documents, List<CodeChunk> code,
                                      long documentLatencyMs, long codeLatencyMs, long totalLatencyMs,
                                      String documentError, String codeError, int repetition,
                                      List<RagWarning> warnings, List<RagStageDiagnostic> diagnostics) {
        return evaluate(c, documents, code, documentLatencyMs, codeLatencyMs, totalLatencyMs,
                documentError, codeError, repetition, warnings, diagnostics, EvaluationTrace.empty());
    }

    public static CaseResult evaluate(RetrievalEvaluationCase c, List<ChunkRecord> documents, List<CodeChunk> code,
                                      long documentLatencyMs, long codeLatencyMs, long totalLatencyMs,
                                      String documentError, String codeError, int repetition,
                                      List<RagWarning> warnings, List<RagStageDiagnostic> diagnostics,
                                      EvaluationTrace trace) {
        List<ChunkRecord> finalDocuments = safeList(documents);
        List<CodeChunk> finalCode = safeList(code);
        List<RagWarning> safeWarnings = safeList(warnings);
        List<RagStageDiagnostic> safeDiagnostics = safeList(diagnostics);
        EvaluationTrace safeTrace = trace == null ? EvaluationTrace.empty() : trace;

        Integer documentFileRank = firstFileRank(c.goldDocuments(), finalDocuments, DEFAULT_CUTOFF);
        Integer documentSectionRank = firstSectionRank(c.goldDocuments(), finalDocuments, DEFAULT_CUTOFF);
        Integer documentChildRank = firstChildRank(c.goldDocuments(), finalDocuments, DEFAULT_CUTOFF);
        Integer documentRank = strictestDocumentRank(
                c.goldDocuments(), documentFileRank, documentSectionRank, documentChildRank);
        Integer codeRank = firstCodeRank(c.goldCode(), finalCode, DEFAULT_CUTOFF);
        Integer documentRawRank = stageDocumentRank(c, safeTrace.documentRawCandidates());
        Integer documentRerankInputRank = stageDocumentRank(c, safeTrace.documentRerankCandidates());
        Integer documentRerankedRank = stageDocumentRank(c, safeTrace.documentRerankedCandidates());
        Integer codeRawRank = stageCodeRank(c, safeTrace.codeRawCandidates());
        Integer codeRankedRank = stageCodeRank(c, safeTrace.codeRankedCandidates());

        boolean expectsDocuments = !c.goldDocuments().isEmpty();
        boolean expectsDocumentSections = c.goldDocuments().stream()
                .anyMatch(gold -> gold.parentOrder() != null);
        boolean expectsDocumentChildren = c.goldDocuments().stream()
                .anyMatch(gold -> gold.childOrder() != null);
        boolean expectsCode = !c.goldCode().isEmpty();
        boolean failed = documentError != null || codeError != null;
        boolean success = !failed && (c.expectedOutcome() == RetrievalEvaluationCase.ExpectedOutcome.NO_RESULTS
                ? finalDocuments.isEmpty() && finalCode.isEmpty()
                : (!expectsDocuments || documentRank != null) && (!expectsCode || codeRank != null));

        int bgeNoCandidateSkips = (int) safeDiagnostics.stream()
                .filter(RetrievalEvaluationMatcher::isBgeNoCandidateSkip).count();
        int bgeSingletonSkips = (int) safeDiagnostics.stream()
                .filter(RetrievalEvaluationMatcher::isBgeSingletonSkip).count();
        int bgeCalls = (int) safeDiagnostics.stream().filter(d -> "bge.rerank".equals(d.stage()))
                .filter(d -> !isBgeNoCandidateSkip(d)).count();
        int bgeSuccesses = (int) safeDiagnostics.stream().filter(d -> "bge.rerank".equals(d.stage())
                && d.status() == RagOutcomeStatus.SUCCESS).count();
        int bgeDegradations = (int) safeWarnings.stream()
                .filter(w -> "BGE_RERANK_UNAVAILABLE".equals(w.code())).count();

        List<String> failureAttributions = failureAttributions(c, success, documentError, codeError,
                safeWarnings, safeTrace, documentRank, codeRank, documentRawRank,
                documentRerankInputRank, documentRerankedRank, codeRawRank, codeRankedRank,
                finalDocuments, finalCode);

        return new CaseResult(
                c.id(), c.query(), c.profile(), c.expectedOutcome(), repetition,
                expectsDocuments, expectsDocumentSections, expectsDocumentChildren, expectsCode,
                documentFileRank, documentSectionRank, documentChildRank,
                documentRawRank, documentRerankInputRank, documentRerankedRank, documentRank,
                rankMovement(documentRawRank, documentRank),
                documentOrderChanged(safeTrace.documentRawCandidates(), finalDocuments),
                codeRawRank, codeRankedRank, codeRank, rankMovement(codeRawRank, codeRank),
                codeOrderChanged(safeTrace.codeRawCandidates(), finalCode),
                success,
                safeTrace.documentTraceAvailable(), safeTrace.codeTraceAvailable(),
                safeTrace.documentRawCandidates().size(), safeTrace.documentRerankCandidates().size(),
                safeTrace.documentRerankedCandidates().size(), finalDocuments.size(),
                safeTrace.codeRawCandidates().size(), safeTrace.codeRankedCandidates().size(), finalCode.size(),
                documentLatencyMs, codeLatencyMs, totalLatencyMs, documentError, codeError,
                summarizeDocuments(safeTrace.documentRawCandidates()),
                summarizeDocuments(safeTrace.documentRerankCandidates()),
                summarizeDocuments(safeTrace.documentRerankedCandidates()),
                summarizeDocuments(finalDocuments),
                summarizeCode(safeTrace.codeRawCandidates()),
                summarizeCode(safeTrace.codeRankedCandidates()),
                summarizeCode(finalCode),
                failureAttributions, c.tags(), c.notes(), safeWarnings, safeDiagnostics,
                bgeCalls, bgeSuccesses, bgeDegradations, bgeNoCandidateSkips, bgeSingletonSkips);
    }

    private static Integer stageDocumentRank(RetrievalEvaluationCase c, List<ChunkRecord> candidates) {
        return c.goldDocuments().isEmpty() ? null
                : firstDocumentRank(c.goldDocuments(), candidates, candidates.size());
    }

    private static Integer strictestDocumentRank(
            List<RetrievalEvaluationCase.GoldDocument> goldDocuments,
            Integer fileRank,
            Integer sectionRank,
            Integer childRank) {
        if (goldDocuments.stream().anyMatch(gold -> gold.childOrder() != null)) {
            return childRank;
        }
        if (goldDocuments.stream().anyMatch(gold -> gold.parentOrder() != null)) {
            return sectionRank;
        }
        return fileRank;
    }

    private static Integer stageCodeRank(RetrievalEvaluationCase c, List<CodeChunk> candidates) {
        return c.goldCode().isEmpty() ? null : firstCodeRank(c.goldCode(), candidates, candidates.size());
    }

    private static List<String> failureAttributions(
            RetrievalEvaluationCase c,
            boolean success,
            String documentError,
            String codeError,
            List<RagWarning> warnings,
            EvaluationTrace trace,
            Integer documentRank,
            Integer codeRank,
            Integer documentRawRank,
            Integer documentRerankInputRank,
            Integer documentRerankedRank,
            Integer codeRawRank,
            Integer codeRankedRank,
            List<ChunkRecord> finalDocuments,
            List<CodeChunk> finalCode) {
        if (success) {
            return List.of();
        }
        LinkedHashSet<String> causes = new LinkedHashSet<>();
        if (documentError != null || codeError != null
                || warnings.stream().anyMatch(RetrievalEvaluationMatcher::isUnavailableWarning)) {
            causes.add("INFRASTRUCTURE_FAILURE");
        }
        if (c.expectedOutcome() == RetrievalEvaluationCase.ExpectedOutcome.NO_RESULTS) {
            if (!finalDocuments.isEmpty() || !finalCode.isEmpty()) {
                causes.add("NO_RESULT_FALSE_POSITIVE");
            }
            return List.copyOf(causes);
        }
        if (!c.goldDocuments().isEmpty() && documentRank == null) {
            if (!trace.documentTraceAvailable()) {
                causes.add("DOCUMENT_TRACE_UNAVAILABLE");
            } else if (documentRawRank == null) {
                causes.add("DOCUMENT_CANDIDATE_RECALL_MISS");
            } else if (documentRerankInputRank == null) {
                causes.add("DOCUMENT_PRE_RERANK_FILTER_LOSS");
            } else if (documentRerankedRank == null) {
                causes.add("DOCUMENT_RERANK_LOSS");
            } else {
                causes.add("DOCUMENT_PARENT_AGGREGATION_LOSS");
            }
        }
        if (!c.goldCode().isEmpty() && codeRank == null) {
            if (!trace.codeTraceAvailable()) {
                causes.add("CODE_TRACE_UNAVAILABLE");
            } else if (codeRawRank == null) {
                if (containsGoldenCode(trace.codeDenseCandidates(), c.goldCode())
                        || containsGoldenCode(trace.codeSparseCandidates(), c.goldCode())) {
                    causes.add("CODE_FUSION_LOSS");
                } else {
                    causes.add("CODE_CANDIDATE_RECALL_MISS");
                }
            } else if (codeRankedRank == null) {
                causes.add("CODE_RERANK_LOSS");
            } else {
                causes.add("CODE_FINAL_FILTER_LOSS");
            }
        }
        if (causes.isEmpty()) {
            causes.add("UNCLASSIFIED_RETRIEVAL_FAILURE");
        }
        return List.copyOf(causes);
    }

    private static boolean containsGoldenCode(List<CodeChunk> candidates, List<RetrievalEvaluationCase.GoldCode> goldCode) {
        for (CodeChunk candidate : candidates) {
            for (RetrievalEvaluationCase.GoldCode gold : goldCode) {
                if (gold.projectId().equals(candidate.projectId())
                        && RetrievalEvaluationDataset.normalizePath(gold.filePath()).equals(
                        RetrievalEvaluationDataset.normalizePath(candidate.filePath()))
                        && gold.symbolName().equals(candidate.symbolName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isUnavailableWarning(RagWarning warning) {
        return warning.code() != null && warning.code().endsWith("_UNAVAILABLE");
    }

    private static String rankMovement(Integer before, Integer after) {
        if (before == null && after == null) {
            return "MISSING";
        }
        if (before == null) {
            return "FOUND";
        }
        if (after == null) {
            return "LOST";
        }
        if (after < before) {
            return "PROMOTED";
        }
        if (after > before) {
            return "DEMOTED";
        }
        return "UNCHANGED";
    }

    private static boolean documentOrderChanged(List<ChunkRecord> raw, List<ChunkRecord> result) {
        List<String> rawParents = distinctDocumentKeys(raw, result.size());
        List<String> finalParents = distinctDocumentKeys(result, result.size());
        return !rawParents.isEmpty() && !finalParents.isEmpty() && !rawParents.equals(finalParents);
    }

    private static List<String> distinctDocumentKeys(List<ChunkRecord> values, int limit) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (ChunkRecord value : values) {
            keys.add(documentKey(value));
            if (keys.size() >= limit) {
                break;
            }
        }
        return List.copyOf(keys);
    }

    private static String documentKey(ChunkRecord value) {
        if (value.parentId() != null && !value.parentId().isBlank()) {
            return value.parentId();
        }
        return normalize(value.filename()) + ':' + value.parentOrder();
    }

    private static boolean codeOrderChanged(List<CodeChunk> raw, List<CodeChunk> result) {
        int size = Math.min(result.size(), raw.size());
        if (size == 0) {
            return false;
        }
        List<String> rawKeys = raw.stream().limit(size).map(RetrievalEvaluationMatcher::codeKey).toList();
        List<String> finalKeys = result.stream().limit(size).map(RetrievalEvaluationMatcher::codeKey).toList();
        return !rawKeys.equals(finalKeys);
    }

    private static String codeKey(CodeChunk value) {
        if (value.id() != null && !value.id().isBlank()) {
            return value.id();
        }
        return normalize(value.projectId()) + ':'
                + RetrievalEvaluationDataset.normalizePath(value.filePath()) + ':' + normalize(value.symbolName());
    }

    private static boolean isBgeNoCandidateSkip(RagStageDiagnostic diagnostic) {
        return "bge.rerank".equals(diagnostic.stage())
                && diagnostic.status() == RagOutcomeStatus.NO_RESULTS
                && diagnostic.itemCount() == 0;
    }

    private static boolean isBgeSingletonSkip(RagStageDiagnostic diagnostic) {
        return "bge.rerank.singleton_skip".equals(diagnostic.stage())
                && diagnostic.status() == RagOutcomeStatus.SUCCESS
                && diagnostic.itemCount() == 1;
    }

    private static boolean containsAll(String text, List<String> fragments) {
        String normalized = normalize(text);
        return fragments.stream().allMatch(f -> normalized.contains(normalize(f)));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static List<DocumentCandidate> summarizeDocuments(List<ChunkRecord> values) {
        List<DocumentCandidate> out = new ArrayList<>();
        for (int i = 0; i < Math.min(DEFAULT_CUTOFF, values.size()); i++) {
            ChunkRecord value = values.get(i);
            out.add(new DocumentCandidate(i + 1, value.filename(), value.parentOrder(), value.childOrder()));
        }
        return List.copyOf(out);
    }

    private static List<CodeCandidate> summarizeCode(List<CodeChunk> values) {
        List<CodeCandidate> out = new ArrayList<>();
        for (int i = 0; i < Math.min(DEFAULT_CUTOFF, values.size()); i++) {
            CodeChunk value = values.get(i);
            out.add(new CodeCandidate(i + 1, value.projectId(), value.filePath(), value.symbolName()));
        }
        return List.copyOf(out);
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    @FunctionalInterface
    private interface DocumentMatch {
        boolean test(RetrievalEvaluationCase.GoldDocument gold, ChunkRecord candidate);
    }

    public record EvaluationTrace(
            boolean documentTraceAvailable,
            List<ChunkRecord> documentRawCandidates,
            List<ChunkRecord> documentRerankCandidates,
            List<ChunkRecord> documentRerankedCandidates,
            boolean codeTraceAvailable,
            List<CodeChunk> codeRawCandidates,
            List<CodeChunk> codeRankedCandidates,
            List<CodeChunk> codeDenseCandidates,
            List<CodeChunk> codeSparseCandidates
    ) {
        public EvaluationTrace {
            documentRawCandidates = safeList(documentRawCandidates);
            documentRerankCandidates = safeList(documentRerankCandidates);
            documentRerankedCandidates = safeList(documentRerankedCandidates);
            codeRawCandidates = safeList(codeRawCandidates);
            codeRankedCandidates = safeList(codeRankedCandidates);
            codeDenseCandidates = safeList(codeDenseCandidates);
            codeSparseCandidates = safeList(codeSparseCandidates);
        }

        public static EvaluationTrace empty() {
            return new EvaluationTrace(false, List.of(), List.of(), List.of(),
                    false, List.of(), List.of(), List.of(), List.of());
        }

        /** Backward-compatible constructor for pre-0.8.3 callers. */
        public EvaluationTrace(boolean documentTraceAvailable,
                               List<ChunkRecord> documentRawCandidates,
                               List<ChunkRecord> documentRerankCandidates,
                               List<ChunkRecord> documentRerankedCandidates,
                               boolean codeTraceAvailable,
                               List<CodeChunk> codeRawCandidates,
                               List<CodeChunk> codeRankedCandidates) {
            this(documentTraceAvailable, documentRawCandidates, documentRerankCandidates,
                    documentRerankedCandidates, codeTraceAvailable, codeRawCandidates, codeRankedCandidates,
                    List.of(), List.of());
        }
    }

    public record DocumentCandidate(int rank, String filename, int parentOrder, int childOrder) {
    }

    public record CodeCandidate(int rank, String projectId, String filePath, String symbolName) {
    }

    public record CaseResult(
            String id,
            String query,
            RetrievalEvaluationCase.RetrievalProfile profile,
            RetrievalEvaluationCase.ExpectedOutcome expectedOutcome,
            int repetition,
            boolean expectsDocuments,
            boolean expectsDocumentSections,
            boolean expectsDocumentChildren,
            boolean expectsCode,
            Integer documentFileRank,
            Integer documentSectionRank,
            Integer documentChildRank,
            Integer documentRawRank,
            Integer documentRerankInputRank,
            Integer documentRerankedRank,
            Integer documentRank,
            String documentRankMovement,
            boolean documentOrderChanged,
            Integer codeRawRank,
            Integer codeRankedRank,
            Integer codeRank,
            String codeRankMovement,
            boolean codeOrderChanged,
            boolean success,
            boolean documentTraceAvailable,
            boolean codeTraceAvailable,
            int documentRawCandidateCount,
            int documentRerankCandidateCount,
            int documentRerankedCandidateCount,
            int documentCandidateCount,
            int codeRawCandidateCount,
            int codeRankedCandidateCount,
            int codeCandidateCount,
            long documentLatencyMs,
            long codeLatencyMs,
            long totalLatencyMs,
            String documentError,
            String codeError,
            List<DocumentCandidate> documentRawCandidates,
            List<DocumentCandidate> documentRerankCandidates,
            List<DocumentCandidate> documentRerankedCandidates,
            List<DocumentCandidate> documentCandidates,
            List<CodeCandidate> codeRawCandidates,
            List<CodeCandidate> codeRankedCandidates,
            List<CodeCandidate> codeCandidates,
            List<String> failureAttributions,
            List<String> tags,
            String notes,
            List<RagWarning> warnings,
            List<RagStageDiagnostic> stageDiagnostics,
            int bgeCalls,
            int bgeSuccesses,
            int bgeDegradations,
            int bgeNoCandidateSkips,
            int bgeSingletonSkips
    ) {
        public CaseResult {
            failureAttributions = safeList(failureAttributions);
            tags = safeList(tags);
            warnings = safeList(warnings);
            stageDiagnostics = safeList(stageDiagnostics);
            documentRawCandidates = safeList(documentRawCandidates);
            documentRerankCandidates = safeList(documentRerankCandidates);
            documentRerankedCandidates = safeList(documentRerankedCandidates);
            documentCandidates = safeList(documentCandidates);
            codeRawCandidates = safeList(codeRawCandidates);
            codeRankedCandidates = safeList(codeRankedCandidates);
            codeCandidates = safeList(codeCandidates);
        }
    }
}
