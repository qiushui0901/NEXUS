package com.example.requirementrag.evaluation;

import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.model.RagStageDiagnostic;
import com.example.requirementrag.model.RagWarning;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure stable-label matching and per-case scoring functions. */
public final class RetrievalEvaluationMatcher {
    public static final int DEFAULT_CUTOFF = 10;
    private RetrievalEvaluationMatcher() {}

    public static Integer firstDocumentRank(List<RetrievalEvaluationCase.GoldDocument> goldDocuments,
                                            List<ChunkRecord> candidates, int cutoff) {
        Integer best = null;
        for (RetrievalEvaluationCase.GoldDocument gold : goldDocuments) {
            StringBuilder accumulated = new StringBuilder();
            for (int i = 0; i < Math.min(cutoff, candidates.size()); i++) {
                ChunkRecord candidate = candidates.get(i);
                if (!gold.filename().equals(candidate.filename())
                        || (gold.parentOrder() != null && gold.parentOrder() != candidate.parentOrder())) continue;
                accumulated.append('\n').append(candidate.parentText()).append('\n').append(candidate.childText());
                if (containsAll(accumulated.toString(), gold.mustContain())) {
                    best = best == null ? i + 1 : Math.min(best, i + 1);
                    break;
                }
            }
        }
        return best;
    }

    public static Integer firstCodeRank(List<RetrievalEvaluationCase.GoldCode> goldCode,
                                        List<CodeChunk> candidates, int cutoff) {
        for (int i = 0; i < Math.min(cutoff, candidates.size()); i++) {
            CodeChunk candidate = candidates.get(i);
            for (RetrievalEvaluationCase.GoldCode gold : goldCode) {
                if (gold.projectId().equals(candidate.projectId())
                        && RetrievalEvaluationDataset.normalizePath(gold.filePath()).equals(
                        RetrievalEvaluationDataset.normalizePath(candidate.filePath()))
                        && gold.symbolName().equals(candidate.symbolName())) return i + 1;
            }
        }
        return null;
    }

    public static CaseResult evaluate(RetrievalEvaluationCase c, List<ChunkRecord> documents, List<CodeChunk> code,
                                      long documentLatencyMs, long codeLatencyMs, long totalLatencyMs) {
        return evaluate(c, documents, code, documentLatencyMs, codeLatencyMs, totalLatencyMs,
                null, null, 1, List.of(), List.of());
    }

    public static CaseResult evaluate(RetrievalEvaluationCase c, List<ChunkRecord> documents, List<CodeChunk> code,
                                      long documentLatencyMs, long codeLatencyMs, long totalLatencyMs,
                                      String documentError, String codeError) {
        return evaluate(c, documents, code, documentLatencyMs, codeLatencyMs, totalLatencyMs,
                documentError, codeError, 1, List.of(), List.of());
    }

    public static CaseResult evaluate(RetrievalEvaluationCase c, List<ChunkRecord> documents, List<CodeChunk> code,
                                      long documentLatencyMs, long codeLatencyMs, long totalLatencyMs,
                                      String documentError, String codeError, int repetition,
                                      List<RagWarning> warnings, List<RagStageDiagnostic> diagnostics) {
        Integer documentRank = firstDocumentRank(c.goldDocuments(), documents, DEFAULT_CUTOFF);
        Integer codeRank = firstCodeRank(c.goldCode(), code, DEFAULT_CUTOFF);
        boolean expectsDocuments = !c.goldDocuments().isEmpty();
        boolean expectsCode = !c.goldCode().isEmpty();
        boolean failed = documentError != null || codeError != null;
        boolean success = !failed && (c.expectedOutcome() == RetrievalEvaluationCase.ExpectedOutcome.NO_RESULTS
                ? documents.isEmpty() && code.isEmpty()
                : (!expectsDocuments || documentRank != null) && (!expectsCode || codeRank != null));
        int bgeNoCandidateSkips = (int) diagnostics.stream().filter(
                RetrievalEvaluationMatcher::isBgeNoCandidateSkip).count();
        int bgeCalls = (int) diagnostics.stream().filter(d -> "bge.rerank".equals(d.stage()))
                .filter(d -> !isBgeNoCandidateSkip(d)).count();
        int bgeSuccesses = (int) diagnostics.stream().filter(d -> "bge.rerank".equals(d.stage())
                && d.status() == com.example.requirementrag.model.RagOutcomeStatus.SUCCESS).count();
        int bgeDegradations = (int) warnings.stream().filter(w -> "BGE_RERANK_UNAVAILABLE".equals(w.code())).count();
        return new CaseResult(c.id(), c.query(), c.profile(), c.expectedOutcome(), repetition,
                expectsDocuments, expectsCode, documentRank, codeRank, success, documents.size(), code.size(),
                documentLatencyMs, codeLatencyMs, totalLatencyMs, documentError, codeError,
                summarizeDocuments(documents), summarizeCode(code), c.tags(), c.notes(),
                warnings, diagnostics, bgeCalls, bgeSuccesses, bgeDegradations, bgeNoCandidateSkips);
    }

    private static boolean isBgeNoCandidateSkip(RagStageDiagnostic diagnostic) {
        return "bge.rerank".equals(diagnostic.stage())
                && diagnostic.status() == com.example.requirementrag.model.RagOutcomeStatus.NO_RESULTS
                && diagnostic.itemCount() == 0;
    }

    private static boolean containsAll(String text, List<String> fragments) {
        String normalized = normalize(text);
        return fragments.stream().allMatch(f -> normalized.contains(normalize(f)));
    }
    private static String normalize(String v) { return v == null ? "" : v.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT); }
    private static List<DocumentCandidate> summarizeDocuments(List<ChunkRecord> values) {
        List<DocumentCandidate> out = new ArrayList<>();
        for (int i=0;i<Math.min(DEFAULT_CUTOFF, values.size());i++) { var v=values.get(i); out.add(new DocumentCandidate(i+1,v.filename(),v.parentOrder(),v.childOrder())); }
        return List.copyOf(out);
    }
    private static List<CodeCandidate> summarizeCode(List<CodeChunk> values) {
        List<CodeCandidate> out = new ArrayList<>();
        for (int i=0;i<Math.min(DEFAULT_CUTOFF, values.size());i++) { var v=values.get(i); out.add(new CodeCandidate(i+1,v.projectId(),v.filePath(),v.symbolName())); }
        return List.copyOf(out);
    }
    public record DocumentCandidate(int rank,String filename,int parentOrder,int childOrder) {}
    public record CodeCandidate(int rank,String projectId,String filePath,String symbolName) {}
    public record CaseResult(String id,String query,RetrievalEvaluationCase.RetrievalProfile profile,
                             RetrievalEvaluationCase.ExpectedOutcome expectedOutcome,int repetition,
                             boolean expectsDocuments,boolean expectsCode,Integer documentRank,Integer codeRank,
                             boolean success,int documentCandidateCount,int codeCandidateCount,
                             long documentLatencyMs,long codeLatencyMs,long totalLatencyMs,
                             String documentError,String codeError,List<DocumentCandidate> documentCandidates,
                             List<CodeCandidate> codeCandidates,List<String> tags,String notes,
                             List<RagWarning> warnings,List<RagStageDiagnostic> stageDiagnostics,
                             int bgeCalls,int bgeSuccesses,int bgeDegradations,int bgeNoCandidateSkips) {}
}
