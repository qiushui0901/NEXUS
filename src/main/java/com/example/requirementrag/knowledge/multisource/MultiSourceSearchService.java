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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 多源检索服务（Phase 4 核心）：
 * 意图分类 -> 读取结构化知识 + 适配器（需求/代码） -> 来源过滤 + 存疑门禁 -> 加权评分召回
 * -> 冲突分析 -> 结论状态与解释。
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
    private final List<MultiSourceCandidateAdapter> adapters;

    @Autowired
    public MultiSourceSearchService(MultiSourceKnowledgeStore store,
                                    KnowledgeQueryIntentClassifier classifier,
                                    MultiSourceKnowledgeGate gate,
                                    SourceFilterStrategy sourceFilter,
                                    MultiSourceConflictAnalyzer conflictAnalyzer,
                                    List<MultiSourceCandidateAdapter> adapters) {
        this.store = store;
        this.classifier = classifier;
        this.gate = gate;
        this.sourceFilter = sourceFilter;
        this.conflictAnalyzer = conflictAnalyzer;
        this.adapters = adapters == null ? List.of() : adapters;
    }

    /** 测试/离线场景无适配器时的兼容构造器。 */
    public MultiSourceSearchService(MultiSourceKnowledgeStore store,
                                    KnowledgeQueryIntentClassifier classifier,
                                    MultiSourceKnowledgeGate gate,
                                    SourceFilterStrategy sourceFilter,
                                    MultiSourceConflictAnalyzer conflictAnalyzer) {
        this(store, classifier, gate, sourceFilter, conflictAnalyzer, List.of());
    }

    public MultiSourceSearchResponse search(String projectId, String version, String query) {
        return search(projectId, version, query, null, 20, 0);
    }

    public MultiSourceSearchResponse search(String projectId, String version, String query,
                                            KnowledgeQueryIntent intentOverride) {
        return search(projectId, version, query, intentOverride, 20, 0);
    }

    public MultiSourceSearchResponse search(String projectId, String version, String query,
                                            KnowledgeQueryIntent intentOverride, int limit, int page) {
        KnowledgeQueryIntent intent = intentOverride != null ? intentOverride : classifier.classify(query);
        Set<SourceType> allowedSources = sourceFilter.allowedSources(intent);
        List<UnifiedKnowledgeClaim> candidates = loadCandidates(projectId, version, allowedSources).stream()
                .filter(claim -> gate.isRetrievable(claim.status()))
                .toList();
        List<String> tokens = tokenize(query);
        String normalizedQuery = query == null ? "" : query.toLowerCase(Locale.ROOT);
        List<String> conflicts = conflictAnalyzer.analyze(candidates);

        // 确定性评分召回：字段加权 + 冲突惩罚，稳定排序后一次性分页。
        int effectiveLimit = Math.max(1, Math.min(limit <= 0 ? 20 : limit, 50));
        int effectivePage = Math.max(0, page);
        List<ScoredClaim> scored = new ArrayList<>();
        for (UnifiedKnowledgeClaim claim : candidates) {
            double base = score(claim, normalizedQuery, tokens);
            double penalty = conflictPenalty(claim, conflicts);
            if (normalizedQuery.isEmpty() || base > 0) {
                scored.add(new ScoredClaim(claim, Math.max(0, base - penalty)));
            }
        }
        List<UnifiedKnowledgeClaim> claims = scored.stream()
                .sorted(java.util.Comparator.comparingDouble(ScoredClaim::score).reversed()
                        .thenComparing(item -> item.claim().sourceType().name())
                        .thenComparing(item -> item.claim().claimId()))
                .skip((long) effectivePage * effectiveLimit)
                .limit(effectiveLimit)
                .map(ScoredClaim::claim)
                .toList();

        List<DoubtClaim> doubts = intent == KnowledgeQueryIntent.DOUBT || intent == KnowledgeQueryIntent.CONSISTENCY
                ? gate.filterDoubts(store.findDoubts(projectId, version), intent)
                : List.of();

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

    /** 字段加权评分：factKey 命中权重最高，其次 subject/module/predicate/value/unit。 */
    private double score(UnifiedKnowledgeClaim claim, String normalizedQuery, List<String> tokens) {
        if (normalizedQuery.isEmpty()) return 0.0;
        String factKey = safe(claim.factKey()).toLowerCase(Locale.ROOT);
        String subject = safe(claim.subject()).toLowerCase(Locale.ROOT);
        String module = safe(claim.module()).toLowerCase(Locale.ROOT);
        String predicate = safe(claim.predicate()).toLowerCase(Locale.ROOT);
        String value = safe(claim.value()).toLowerCase(Locale.ROOT);
        String unit = safe(claim.unit()).toLowerCase(Locale.ROOT);
        String haystack = (module + " " + subject + " " + predicate + " " + value + " " + unit + " " + factKey);
        double score = haystack.contains(normalizedQuery) ? 3.0 : 0.0;
        for (String token : tokens) {
            if (token.length() < 2) continue;
            if (factKey.contains(token)) score += 2.0;
            else if (subject.contains(token) || module.contains(token)) score += 1.5;
            else if (predicate.contains(token)) score += 1.0;
            else if (value.contains(token)) score += 1.0;
            else if (unit.contains(token)) score += 0.5;
        }
        return score;
    }

    /** 冲突惩罚：命中冲突事实组的 Claim 扣分。 */
    private double conflictPenalty(UnifiedKnowledgeClaim claim, List<String> conflicts) {
        if (conflicts.isEmpty()) return 0.0;
        String group = (safe(claim.subject()) + "|" + safe(claim.predicate())).toLowerCase(Locale.ROOT);
        String marker;
        if ("|".equals(group)) {
            marker = "factKey=" + safe(claim.factKey());
        } else {
            marker = "factKey=" + group;
        }
        final String conflictMarker = marker;
        return conflicts.stream().anyMatch(message -> message.contains(conflictMarker)) ? 0.2 : 0.0;
    }

    /** CJK/英文分词：中文无空格查询按 2-gram 切分，避免整句匹配失败。 */
    private List<String> tokenize(String query) {
        if (query == null || query.isBlank()) return List.of();
        java.util.LinkedHashSet<String> tokens = new java.util.LinkedHashSet<>();
        String lower = query.toLowerCase(Locale.ROOT);
        for (String term : lower.split("[\\s,，。；;？?！!：:]+")) {
            if (term.isBlank()) continue;
            tokens.add(term);
            if (term.length() >= 2) {
                for (int index = 0; index < term.length() - 1; index++) {
                    char first = term.charAt(index);
                    char second = term.charAt(index + 1);
                    if (isCjk(first) && isCjk(second)) tokens.add(term.substring(index, index + 2));
                }
            }
        }
        return List.copyOf(tokens);
    }

    private boolean isCjk(char value) {
        return value >= 0x4E00 && value <= 0x9FFF;
    }

    private record ScoredClaim(UnifiedKnowledgeClaim claim, double score) {
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
        for (MultiSourceCandidateAdapter adapter : adapters) {
            if (allowed.contains(adapter.sourceType())) {
                adapter.load(projectId, version).forEach(result::add);
            }
        }
        return List.copyOf(result);
    }

    private UnifiedKnowledgeClaim toUnified(ParameterClaim claim) {
        return new UnifiedKnowledgeClaim(claim.claimId(), claim.projectId(), claim.version(), claim.factKey(),
                claim.module(), claim.parameter(), claim.normalizedValue(),
                claim.valueType() == null ? null : claim.valueType().name(), claim.unit(),
                SourceType.PARAMETER_TABLE, Authority.PRIMARY, claim.status(),
                claim.version(), null, claim.evidenceLocation(), claim.module());
    }

    private UnifiedKnowledgeClaim toUnified(TestCaseClaim claim) {
        return new UnifiedKnowledgeClaim(claim.claimId(), claim.projectId(), claim.version(),
                factKey(claim.projectId(), claim.version(), claim.module(), claim.coveredRequirementId() == null
                        ? claim.title() : claim.coveredRequirementId()),
                claim.module(), claim.title(), claim.expectedResult(), "TEXT", null,
                SourceType.TEST_CASE, Authority.SECONDARY, claim.status(),
                claim.version(), null, claim.evidenceLocation(), claim.module());
    }

    private UnifiedKnowledgeClaim toUnified(TestResultClaim claim) {
        return new UnifiedKnowledgeClaim(claim.claimId(), claim.projectId(), claim.version(),
                factKey(claim.projectId(), claim.version(), claim.testCaseId(), "execution"),
                claim.testCaseId(), "executionStatus", claim.executionStatus(), "TEXT", null,
                SourceType.TEST_RESULT, Authority.SECONDARY, claim.status(),
                claim.version(), null, claim.evidenceLocation(), claim.testCaseId());
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