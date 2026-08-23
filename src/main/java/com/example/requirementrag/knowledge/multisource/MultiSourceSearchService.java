package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.CrossSourceRelation;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.MultiSourceSearchResponse;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.ParameterClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.TestCaseClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.TestResultClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.UnifiedKnowledgeClaim;
import com.example.requirementrag.conflict.KnowledgeConflictModels.Authority;
import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.AnswerStatus;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.DoubtClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeQueryIntent;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 多源检索服务（Phase 4 核心）：
 * 意图分类 -> 读取结构化知识 + 适配器（需求/代码） -> 来源过滤 + 存疑门禁 -> 加权评分召回
 * -> 冲突分析 -> 结论状态与解释。
 *
 * <p>第一版为确定性检索，不依赖 Qdrant/LLM；便于建立 Golden Dataset 与离线评估基线。
 * 灰度开关（按项目）与可选的 LLM 意图回退见 {@link MultiSourceKnowledgeProperties}。
 */
@Service
public class MultiSourceSearchService {
    private final MultiSourceKnowledgeStore store;
    private final KnowledgeQueryIntentClassifier classifier;
    private final MultiSourceKnowledgeGate gate;
    private final SourceFilterStrategy sourceFilter;
    private final MultiSourceConflictAnalyzer conflictAnalyzer;
    private final List<MultiSourceCandidateAdapter> adapters;
    private final CrossSourceRelationExtractor relationExtractor;
    private final KnowledgeQueryIntentLlmFallback intentFallback;
    private final MultiSourceKnowledgeProperties properties;
    private final CrossSourceRelationConfirmer relationConfirmer;

    private static final KnowledgeQueryIntentLlmFallback NO_OP_FALLBACK = query -> Optional.empty();
    private static final CrossSourceRelationConfirmer NO_OP_CONFIRMER =
            (source, relationType, target, evidence) ->
                    new CrossSourceRelationConfirmer.Confirmation(true, "no-op");

    @Autowired
    public MultiSourceSearchService(MultiSourceKnowledgeStore store,
                                    KnowledgeQueryIntentClassifier classifier,
                                    MultiSourceKnowledgeGate gate,
                                    SourceFilterStrategy sourceFilter,
                                    MultiSourceConflictAnalyzer conflictAnalyzer,
                                    List<MultiSourceCandidateAdapter> adapters,
                                    CrossSourceRelationExtractor relationExtractor,
                                    KnowledgeQueryIntentLlmFallback intentFallback,
                                    MultiSourceKnowledgeProperties properties,
                                    CrossSourceRelationConfirmer relationConfirmer) {
        this.store = store;
        this.classifier = classifier;
        this.gate = gate;
        this.sourceFilter = sourceFilter;
        this.conflictAnalyzer = conflictAnalyzer;
        this.adapters = adapters == null ? List.of() : adapters;
        this.relationExtractor = relationExtractor == null ? new CrossSourceRelationExtractor() : relationExtractor;
        this.intentFallback = intentFallback == null ? NO_OP_FALLBACK : intentFallback;
        this.properties = properties == null ? MultiSourceKnowledgeProperties.enabledDefault() : properties;
        this.relationConfirmer = relationConfirmer == null ? NO_OP_CONFIRMER : relationConfirmer;
    }

    /** 测试/离线场景无 LLM 回退、灰度配置与关系确认器时的兼容构造器（默认启用）。 */
    public MultiSourceSearchService(MultiSourceKnowledgeStore store,
                                    KnowledgeQueryIntentClassifier classifier,
                                    MultiSourceKnowledgeGate gate,
                                    SourceFilterStrategy sourceFilter,
                                    MultiSourceConflictAnalyzer conflictAnalyzer,
                                    List<MultiSourceCandidateAdapter> adapters,
                                    CrossSourceRelationExtractor relationExtractor) {
        this(store, classifier, gate, sourceFilter, conflictAnalyzer, adapters, relationExtractor,
                NO_OP_FALLBACK, MultiSourceKnowledgeProperties.enabledDefault(), NO_OP_CONFIRMER);
    }

    /** 测试/离线场景无关系确认器时的兼容构造器。 */
    public MultiSourceSearchService(MultiSourceKnowledgeStore store,
                                    KnowledgeQueryIntentClassifier classifier,
                                    MultiSourceKnowledgeGate gate,
                                    SourceFilterStrategy sourceFilter,
                                    MultiSourceConflictAnalyzer conflictAnalyzer,
                                    List<MultiSourceCandidateAdapter> adapters,
                                    CrossSourceRelationExtractor relationExtractor,
                                    KnowledgeQueryIntentLlmFallback intentFallback,
                                    MultiSourceKnowledgeProperties properties) {
        this(store, classifier, gate, sourceFilter, conflictAnalyzer, adapters, relationExtractor,
                intentFallback, properties, NO_OP_CONFIRMER);
    }

    /** 测试/离线场景无适配器时的兼容构造器。 */
    public MultiSourceSearchService(MultiSourceKnowledgeStore store,
                                    KnowledgeQueryIntentClassifier classifier,
                                    MultiSourceKnowledgeGate gate,
                                    SourceFilterStrategy sourceFilter,
                                    MultiSourceConflictAnalyzer conflictAnalyzer) {
        this(store, classifier, gate, sourceFilter, conflictAnalyzer, List.of(), new CrossSourceRelationExtractor());
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
        int effectiveLimit = Math.max(1, Math.min(limit <= 0 ? 20 : limit, 50));
        int effectivePage = Math.max(0, page);
        if (!properties.enabledFor(projectId)) {
            return new MultiSourceSearchResponse(query,
                    intentOverride != null ? intentOverride : KnowledgeQueryIntent.GENERAL,
                    AnswerStatus.NO_RESULT, List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of("MULTI_SOURCE_DISABLED"), List.of(),
                    0, effectivePage, effectiveLimit, false);
        }
        KnowledgeQueryIntent intent = intentOverride != null ? intentOverride : classifier.classify(query);
        boolean llmUsed = false;
        if (intent == KnowledgeQueryIntent.GENERAL && properties.llmFallbackEnabled()) {
            Optional<KnowledgeQueryIntent> llmIntent = intentFallback.tryClassify(query);
            if (llmIntent.isPresent()) {
                intent = llmIntent.get();
                llmUsed = true;
            }
        }
        Set<SourceType> allowedSources = sourceFilter.allowedSources(intent);
        List<UnifiedKnowledgeClaim> candidates = loadCandidates(projectId, version, allowedSources, query).stream()
                .filter(claim -> gate.isRetrievable(claim.status()))
                .toList();
        List<String> tokens = tokenize(query);
        String normalizedQuery = query == null ? "" : query.toLowerCase(Locale.ROOT);
        Set<String> conflictGroups = conflictAnalyzer.conflictGroups(candidates);

        // 确定性评分召回：字段加权 + 冲突惩罚，稳定排序后一次性分页。
        List<ScoredClaim> scored = new ArrayList<>();
        for (UnifiedKnowledgeClaim claim : candidates) {
            double base = score(claim, normalizedQuery, tokens);
            double penalty = conflictPenalty(claim, conflictGroups);
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

        // 跨来源关系：查询只读预生成关系（knowledge_relation），并裁剪到当前命中页的一跳邻域；
        // 不再在查询侧生成/持久化/调用 LLM。旧 multi_source_relation 作为迁移期只读回退。
        List<CrossSourceRelation> relations = loadPageRelations(projectId, version, claims);

        // 冲突范围与分页一致：只对当前页 Claim 做冲突分析与结论状态。
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
        if (llmUsed) {
            warnings.add("intent classified via LLM: " + intent.name());
        }
        boolean hasMore = (long) (effectivePage + 1) * effectiveLimit < scored.size();
        return new MultiSourceSearchResponse(query, intent, status, claims, evidence, conflicts, doubts,
                explanations, warnings, relations, scored.size(), effectivePage, effectiveLimit, hasMore);
    }

    /** 读取当前命中页 Claim 的一跳预生成关系；无新关系时回退旧表只读。 */
    private List<CrossSourceRelation> loadPageRelations(String projectId, String version,
                                                        List<UnifiedKnowledgeClaim> claims) {
        if (claims.isEmpty()) {
            return List.of();
        }
        Set<String> pageIds = claims.stream().map(UnifiedKnowledgeClaim::claimId)
                .collect(java.util.stream.Collectors.toSet());
        List<KnowledgeCatalogModels.KnowledgeRelation> persisted =
                store.findRelationsForClaims(projectId, version, pageIds);
        if (!persisted.isEmpty()) {
            return persisted.stream().map(this::toCrossSourceRelation).toList();
        }
        return store.findRelations(projectId, version).stream()
                .filter(relation -> pageIds.contains(relation.sourceClaimId())
                        || pageIds.contains(relation.targetClaimId()))
                .toList();
    }

    private CrossSourceRelation toCrossSourceRelation(KnowledgeCatalogModels.KnowledgeRelation relation) {
        return new CrossSourceRelation(relation.relationId(), relation.projectId(), relation.version(),
                relation.sourceClaimId(), relation.targetClaimId(),
                MultiSourceKnowledgeModels.CrossSourceRelationType.valueOf(relation.relationType()),
                relation.evidenceId(), relation.confirmationReason());
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
    private double conflictPenalty(UnifiedKnowledgeClaim claim, Set<String> conflictGroups) {
        if (conflictGroups.isEmpty()) return 0.0;
        String group = (safe(claim.subject()) + "|" + safe(claim.predicate())).toLowerCase(Locale.ROOT);
        if ("|".equals(group)) {
            group = safe(claim.factKey()).toLowerCase(Locale.ROOT);
        }
        return conflictGroups.contains(group) ? 0.2 : 0.0;
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

    private List<UnifiedKnowledgeClaim> loadCandidates(String projectId, String version, Set<SourceType> allowed,
                                                       String query) {
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
                adapter.load(projectId, version, query).forEach(result::add);
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