package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.conflict.KnowledgeConflictModels.Authority;
import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.AnswerStatus;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.DoubtClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeQueryIntent;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeStatus;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.MultiSourceSearchResponse;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.ParameterClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.TestCaseClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.TestResultClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.UnifiedKnowledgeClaim;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 多源检索服务（Phase 4 核心）：
 * 意图分类 -> 读取结构化知识 -> 来源过滤 + 存疑门禁 -> 关键词召回 -> 冲突分析 -> 结论状态与解释。
 *
 * <p>第一版为确定性检索，不依赖 Qdrant/LLM；便于建立 Golden Dataset 与离线评估基线。
 */
@Service
public class MultiSourceSearchService {
    private final MultiSourceKnowledgeStore store;
    private final KnowledgeQueryIntentClassifier classifier;
    private final MultiSourceKnowledgeGate gate;
    private final SourceFilterStrategy sourceFilter;
    private final MultiSourceConflictAnalyzer conflictAnalyzer;

    public MultiSourceSearchService(MultiSourceKnowledgeStore store,
                                    KnowledgeQueryIntentClassifier classifier,
                                    MultiSourceKnowledgeGate gate,
                                    SourceFilterStrategy sourceFilter,
                                    MultiSourceConflictAnalyzer conflictAnalyzer) {
        this.store = store;
        this.classifier = classifier;
        this.gate = gate;
        this.sourceFilter = sourceFilter;
        this.conflictAnalyzer = conflictAnalyzer;
    }

    public MultiSourceSearchResponse search(String projectId, String version, String query) {
        return search(projectId, version, query, null);
    }

    public MultiSourceSearchResponse search(String projectId, String version, String query,
                                            KnowledgeQueryIntent intentOverride) {
        KnowledgeQueryIntent intent = intentOverride != null ? intentOverride : classifier.classify(query);
        Set<SourceType> allowedSources = sourceFilter.allowedSources(intent);
        List<UnifiedKnowledgeClaim> candidates = loadCandidates(projectId, version, allowedSources);
        List<String> tokens = tokens(query);

        // 确定性关键词召回：factKey/module/predicate/value 任一包含查询词。
        String normalizedQuery = query == null ? "" : query.toLowerCase(Locale.ROOT);
        List<UnifiedKnowledgeClaim> claims = candidates.stream()
                .filter(claim -> matches(claim, normalizedQuery, tokens))
                .toList();

        List<DoubtClaim> doubts = intent == KnowledgeQueryIntent.DOUBT || intent == KnowledgeQueryIntent.CONSISTENCY
                ? gate.filterDoubts(store.findDoubts(projectId, version), intent)
                : List.of();

        List<String> conflicts = conflictAnalyzer.analyze(claims);
        AnswerStatus status = conflictAnalyzer.resolveStatus(claims, conflicts);
        List<String> evidence = claims.stream().map(UnifiedKnowledgeClaim::evidenceLocation)
                .filter(location -> location != null && !location.isBlank())
                .distinct().toList();
        List<String> explanations = explanations(claims, intent);
        List<String> warnings = new ArrayList<>();
        if (intent == KnowledgeQueryIntent.NORMATIVE && !doubts.isEmpty()) {
            warnings.add("普通规范查询默认不返回 OPEN 存疑");
        }
        return new MultiSourceSearchResponse(query, intent, status, claims, evidence, conflicts, doubts,
                explanations, warnings);
    }

    private List<UnifiedKnowledgeClaim> loadCandidates(String projectId, String version, Set<SourceType> allowed) {
        Set<UnifiedKnowledgeClaim> result = new LinkedHashSet<>();
        if (allowed.contains(SourceType.PARAMETER_TABLE)) {
            store.findParameters(projectId, version).forEach(claim -> result.add(toUnified(claim)));
        }
        if (allowed.contains(SourceType.TEST_CASE)) {
            store.findTestCases(projectId, version).forEach(claim -> result.add(toUnified(claim)));
        }
        if (allowed.contains(SourceType.TEST_RESULT)) {
            store.findTestResults(projectId, version).forEach(claim -> result.add(toUnified(claim)));
        }
        return List.copyOf(result);
    }

    private UnifiedKnowledgeClaim toUnified(ParameterClaim claim) {
        return new UnifiedKnowledgeClaim(claim.claimId(), claim.projectId(), claim.version(), claim.factKey(),
                claim.module(), claim.parameter(), claim.normalizedValue(),
                claim.valueType() == null ? null : claim.valueType().name(), claim.unit(),
                SourceType.PARAMETER_TABLE, Authority.PRIMARY, KnowledgeStatus.SUPPORTED,
                claim.version(), null, claim.evidenceLocation(), claim.module());
    }

    private UnifiedKnowledgeClaim toUnified(TestCaseClaim claim) {
        return new UnifiedKnowledgeClaim(claim.claimId(), claim.projectId(), claim.version(),
                factKey(claim.projectId(), claim.version(), claim.module(), claim.coveredRequirementId() == null
                        ? claim.title() : claim.coveredRequirementId()),
                claim.module(), claim.title(), claim.expectedResult(), "TEXT", null,
                SourceType.TEST_CASE, Authority.SECONDARY, KnowledgeStatus.SUPPORTED,
                claim.version(), null, claim.evidenceLocation(), claim.module());
    }

    private UnifiedKnowledgeClaim toUnified(TestResultClaim claim) {
        return new UnifiedKnowledgeClaim(claim.claimId(), claim.projectId(), claim.version(),
                factKey(claim.projectId(), claim.version(), claim.testCaseId(), "execution"),
                claim.testCaseId(), "executionStatus", claim.executionStatus(), "TEXT", null,
                SourceType.TEST_RESULT, Authority.SECONDARY, KnowledgeStatus.SUPPORTED,
                claim.version(), null, claim.evidenceLocation(), claim.testCaseId());
    }

    private boolean matches(UnifiedKnowledgeClaim claim, String normalizedQuery, List<String> tokens) {
        if (normalizedQuery.isEmpty()) return true;
        String haystack = (claim.module() + " " + claim.subject() + " " + claim.predicate() + " " + claim.value()
                + " " + claim.unit() + " " + claim.factKey()).toLowerCase(Locale.ROOT);
        if (haystack.contains(normalizedQuery)) return true;
        for (String token : tokens) {
            if (token.length() > 1 && haystack.contains(token)) return true;
        }
        return false;
    }

    private List<String> tokens(String query) {
        if (query == null || query.isBlank()) return List.of();
        return java.util.Arrays.stream(query.toLowerCase(Locale.ROOT).split("\\s+"))
                .map(String::trim).filter(token -> !token.isBlank()).toList();
    }

    private List<String> explanations(List<UnifiedKnowledgeClaim> claims, KnowledgeQueryIntent intent) {
        List<String> result = new ArrayList<>();
        for (UnifiedKnowledgeClaim claim : claims) {
            result.add(claim.sourceType() + ":" + claim.factKey() + "@" + claim.evidenceLocation()
                    + " intent=" + intent.name());
        }
        return result;
    }

    private String factKey(String projectId, String version, String left, String right) {
        return (projectId + "|" + version + "|" + safe(left) + "|" + safe(right)).toLowerCase(Locale.ROOT);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}