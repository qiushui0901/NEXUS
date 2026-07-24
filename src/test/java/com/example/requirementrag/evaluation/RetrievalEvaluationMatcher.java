package com.example.requirementrag.evaluation;

import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.CodeChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure stable-label matching and per-case scoring functions. */
public final class RetrievalEvaluationMatcher {

    public static final int DEFAULT_CUTOFF = 10;

    private RetrievalEvaluationMatcher() {
    }

    public static Integer firstDocumentRank(List<RetrievalEvaluationCase.GoldDocument> goldDocuments,
                                            List<ChunkRecord> candidates,
                                            int cutoff) {
        Integer best = null;
        for (RetrievalEvaluationCase.GoldDocument gold : goldDocuments) {
            StringBuilder accumulatedText = new StringBuilder();
            for (int index = 0; index < Math.min(cutoff, candidates.size()); index++) {
                ChunkRecord candidate = candidates.get(index);
                if (!sameDocumentLabel(gold, candidate)) {
                    continue;
                }
                accumulatedText.append('\n').append(candidate.parentText()).append('\n').append(candidate.childText());
                if (containsAll(accumulatedText.toString(), gold.mustContain())) {
                    int rank = index + 1;
                    best = best == null ? rank : Math.min(best, rank);
                    break;
                }
            }
        }
        return best;
    }

    public static Integer firstCodeRank(List<RetrievalEvaluationCase.GoldCode> goldCode,
                                        List<CodeChunk> candidates,
                                        int cutoff) {
        for (int index = 0; index < Math.min(cutoff, candidates.size()); index++) {
            CodeChunk candidate = candidates.get(index);
            for (RetrievalEvaluationCase.GoldCode gold : goldCode) {
                if (sameCodeLabel(gold, candidate)) {
                    return index + 1;
                }
            }
        }
        return null;
    }

    public static CaseResult evaluate(RetrievalEvaluationCase evaluationCase,
                                      List<ChunkRecord> documents,
                                      List<CodeChunk> code,
                                      long documentLatencyMs,
                                      long codeLatencyMs,
                                      long totalLatencyMs) {
        return evaluate(evaluationCase, documents, code, documentLatencyMs, codeLatencyMs, totalLatencyMs, null, null);
    }

    public static CaseResult evaluate(RetrievalEvaluationCase evaluationCase,
                                      List<ChunkRecord> documents,
                                      List<CodeChunk> code,
                                      long documentLatencyMs,
                                      long codeLatencyMs,
                                      long totalLatencyMs,
                                      String documentError,
                                      String codeError) {
        Integer documentRank = firstDocumentRank(evaluationCase.goldDocuments(), documents, DEFAULT_CUTOFF);
        Integer codeRank = firstCodeRank(evaluationCase.goldCode(), code, DEFAULT_CUTOFF);
        boolean expectsDocuments = !evaluationCase.goldDocuments().isEmpty();
        boolean expectsCode = !evaluationCase.goldCode().isEmpty();
        boolean retrievalFailed = documentError != null || codeError != null;
        boolean success;
        if (retrievalFailed) {
            success = false;
        } else if (evaluationCase.expectedOutcome() == RetrievalEvaluationCase.ExpectedOutcome.NO_RESULTS) {
            success = documents.isEmpty() && code.isEmpty();
        } else {
            success = (!expectsDocuments || documentRank != null) && (!expectsCode || codeRank != null);
        }
        return new CaseResult(
                evaluationCase.id(), evaluationCase.query(), evaluationCase.profile(),
                evaluationCase.expectedOutcome(), expectsDocuments, expectsCode,
                documentRank, codeRank, success, documents.size(), code.size(),
                documentLatencyMs, codeLatencyMs, totalLatencyMs, documentError, codeError,
                summarizeDocuments(documents), summarizeCode(code), evaluationCase.tags(), evaluationCase.notes());
    }

    private static boolean sameDocumentLabel(RetrievalEvaluationCase.GoldDocument gold, ChunkRecord candidate) {
        return gold.filename().equals(candidate.filename())
                && (gold.parentOrder() == null || gold.parentOrder() == candidate.parentOrder());
    }

    private static boolean sameCodeLabel(RetrievalEvaluationCase.GoldCode gold, CodeChunk candidate) {
        return gold.projectId().equals(candidate.projectId())
                && RetrievalEvaluationDataset.normalizePath(gold.filePath())
                .equals(RetrievalEvaluationDataset.normalizePath(candidate.filePath()))
                && gold.symbolName().equals(candidate.symbolName());
    }

    private static boolean containsAll(String candidateText, List<String> fragments) {
        String normalizedCandidate = normalizeText(candidateText);
        for (String fragment : fragments) {
            if (!normalizedCandidate.contains(normalizeText(fragment))) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static List<DocumentCandidate> summarizeDocuments(List<ChunkRecord> candidates) {
        List<DocumentCandidate> summaries = new ArrayList<>();
        for (int index = 0; index < Math.min(DEFAULT_CUTOFF, candidates.size()); index++) {
            ChunkRecord candidate = candidates.get(index);
            summaries.add(new DocumentCandidate(index + 1, candidate.filename(), candidate.parentOrder(), candidate.childOrder()));
        }
        return List.copyOf(summaries);
    }

    private static List<CodeCandidate> summarizeCode(List<CodeChunk> candidates) {
        List<CodeCandidate> summaries = new ArrayList<>();
        for (int index = 0; index < Math.min(DEFAULT_CUTOFF, candidates.size()); index++) {
            CodeChunk candidate = candidates.get(index);
            summaries.add(new CodeCandidate(index + 1, candidate.projectId(), candidate.filePath(), candidate.symbolName()));
        }
        return List.copyOf(summaries);
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
            boolean expectsDocuments,
            boolean expectsCode,
            Integer documentRank,
            Integer codeRank,
            boolean success,
            int documentCandidateCount,
            int codeCandidateCount,
            long documentLatencyMs,
            long codeLatencyMs,
            long totalLatencyMs,
            String documentError,
            String codeError,
            List<DocumentCandidate> documentCandidates,
            List<CodeCandidate> codeCandidates,
            List<String> tags,
            String notes
    ) {
    }
}
