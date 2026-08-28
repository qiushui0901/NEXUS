package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.conflict.KnowledgeConflictModels.Authority;
import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeQueryIntent;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeStatus;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.UnifiedKnowledgeClaim;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticAnnotationRecord;
import com.example.requirementrag.requirement.semantic.RequirementSemanticException;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticClaimCandidate;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticCondition;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticEntity;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticNumericFact;
import com.example.requirementrag.requirement.semantic.RequirementSemanticProperties;
import com.example.requirementrag.requirement.semantic.SQLiteRequirementSemanticStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * REQUIREMENT_SEMANTIC 来源适配器：把 active 构建下成功标注的语义候选
 * （实体 / 条件 / 数值事实 / Claim 候选）投影为统一 Claim 参与多源候选召回。
 *
 * <p>治理边界（与 Review 第三批要求一致）：</p>
 * <ul>
 *   <li>语义结果只做候选召回：状态固定 {@code EXTRACTED}，权威最高 SECONDARY，
 *       单独出现时结论状态只能是 SUPPORTED，不会把答案推成 CONFIRMED；</li>
 *   <li>NORMATIVE（规范事实）意图默认不可见，需显式开启
 *       {@code app.rag.requirement-semantic.normative-retrieval-enabled}；</li>
 *   <li>INFERRED / UNKNOWN 置信度默认不进入候选，需显式开启
 *       {@code app.rag.requirement-semantic.allow-inferred-candidate}；</li>
 *   <li>只消费 active 构建代际下的成功标注（绑定 source_revision + 模型 + Prompt + Schema），
 *       旧 revision / 旧 Prompt 结果不可见；</li>
 *   <li>subject|predicate 与参数表等来源对齐，冲突分析可检测语义候选与参数事实的不一致。</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "app.rag.requirement-semantic", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public class RequirementSemanticCandidateAdapter implements MultiSourceCandidateAdapter {
    private static final Logger log = LoggerFactory.getLogger(RequirementSemanticCandidateAdapter.class);

    private final SQLiteRequirementSemanticStore store;
    private final RequirementSemanticProperties properties;

    public RequirementSemanticCandidateAdapter(SQLiteRequirementSemanticStore store,
                                               RequirementSemanticProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @Override
    public SourceType sourceType() {
        return SourceType.REQUIREMENT_SEMANTIC;
    }

    @Override
    public List<UnifiedKnowledgeClaim> load(String projectId, String version, String query) {
        // 兼容旧契约的直调入口：无意图信息时按 GENERAL（候选可见）处理。
        return load(projectId, version, query, KnowledgeQueryIntent.GENERAL);
    }

    @Override
    public List<UnifiedKnowledgeClaim> load(String projectId, String version, String query,
                                            KnowledgeQueryIntent intent) {
        return loadDetailed(projectId, version, query, intent).claims();
    }

    @Override
    public CandidateLoad loadDetailed(String projectId, String version, String query,
                                      KnowledgeQueryIntent intent) {
        // P1：配置关闭不能静默返回空候选（否则前端把“配置关闭”误判成“召回质量差”）。
        // 返回稳定警告码，由检索层并入响应 warnings；前端据此提示且不用此类结果做评测。
        if (!properties.candidateRetrievalEnabled()) {
            return new CandidateLoad(List.of(),
                    List.of("SEMANTIC_CANDIDATE_RETRIEVAL_DISABLED:语义候选检索已关闭（candidate-retrieval-enabled=false），本次结果不含语义候选；请勿将其作为召回评测数据"));
        }
        if (intent == KnowledgeQueryIntent.NORMATIVE && !properties.normativeRetrievalEnabled()) {
            return new CandidateLoad(List.of(),
                    List.of("SEMANTIC_NORMATIVE_RETRIEVAL_DISABLED:规范事实意图下语义候选被开关拦截（normative-retrieval-enabled=false），本次结果不含语义候选；请勿将其作为召回评测数据"));
        }
        try {
            // 中（第七批 Review M2）：截断探测改由存储层显式标志（truncated）——存储内部多取一行并
            // 区分“刚好等于上限”与“被上限截断”；此前用 limit+1 探测，在恰好命中 SQL 硬顶 20000 时
            // 探测被存储钳制吞掉，后段相关候选静默丢失却无警告。
            int limit = properties.maxCandidateAnnotations();
            // 高（Review）：同一查询同时返回实际读取的构建代际 ids（buildIds）——评测上下文
            // 必须绑定本次检索实际读取的代际，而非前端另行请求的构建状态（两请求间可能已发布新代际）。
            SQLiteRequirementSemanticStore.ActiveAnnotations loaded =
                    store.listActiveByProjectVersionWithBuilds(projectId, version, limit, query);
            boolean truncated = loaded.truncated();
            List<SemanticAnnotationRecord> annotations = loaded.annotations();
            if (truncated) {
                log.warn("SEMANTIC_CANDIDATE_TRUNCATED project={} version={} limit={}",
                        safe(projectId), safe(version), limit);
            }
            // 重叠窗口会产生相同事实：按（factKey|主体|谓词|值|单位|值类型）折叠，保留最早窗口的首个声明；
            // factKey 纳入键避免不同领域事实（不同 factKey、相同字段值）被错误吞并。
            Map<String, UnifiedKnowledgeClaim> unique = new LinkedHashMap<>();
            for (SemanticAnnotationRecord annotation : annotations) {
                if (annotation.result() == null) continue;
                project(annotation, unique);
            }
            return new CandidateLoad(List.copyOf(unique.values()),
                    truncated ? List.of("SEMANTIC_CANDIDATE_TRUNCATED") : List.of(),
                    loaded.buildIds());
        } catch (RuntimeException exception) {
            // 存储故障不能伪装成“没有语义结果”：抛稳定异常，由 MultiSourceSearchService 转成检索警告。
            throw new RequirementSemanticException("SEMANTIC_CANDIDATE_LOAD_FAILED",
                    "语义候选加载失败 project=" + safe(projectId) + " version=" + safe(version), exception);
        }
    }

    private void project(SemanticAnnotationRecord annotation, Map<String, UnifiedKnowledgeClaim> unique) {
        String annotationId = annotation.annotationId();
        var result = annotation.result();
        int entityIndex = 0;
        int conditionIndex = 0;
        int numericIndex = 0;
        int claimIndex = 0;
        for (SemanticEntity entity : result.entities()) {
            add(unique, claim(annotationId + "#entity-" + entityIndex++, annotation, entity.name(), "entity",
                    safe(entity.type()), "TEXT", null, entity.certainty()), entity.certainty());
        }
        for (SemanticCondition condition : result.conditions()) {
            add(unique, claim(annotationId + "#condition-" + conditionIndex++, annotation, condition.subject(),
                    condition.field(), condition.value(), condition.valueType(), condition.unit(),
                    condition.certainty()), condition.certainty());
        }
        for (SemanticNumericFact fact : result.numericFacts()) {
            String value = fact.normalizedValue() == null
                    ? fact.value() : trimNumber(fact.normalizedValue());
            String unit = fact.normalizedUnit() == null || fact.normalizedUnit().isBlank()
                    ? fact.unit() : fact.normalizedUnit();
            add(unique, claim(annotationId + "#numeric-" + numericIndex++, annotation, fact.subject(),
                    fact.field(), value, "NUMBER", unit, fact.certainty()), fact.certainty());
        }
        for (SemanticClaimCandidate candidate : result.claims()) {
            add(unique, claim(annotationId + "#claim-" + claimIndex++, annotation, candidate.subject(),
                    candidate.predicate(), candidate.value(), "TEXT", candidate.unit(),
                    candidate.certainty(), candidate.factKey()), candidate.certainty());
        }
    }

    private UnifiedKnowledgeClaim claim(String claimId, SemanticAnnotationRecord annotation,
                                        String subject, String predicate, String value, String valueType,
                                        String unit, String certainty) {
        return claim(claimId, annotation, subject, predicate, value, valueType, unit, certainty, null);
    }

    private UnifiedKnowledgeClaim claim(String claimId, SemanticAnnotationRecord annotation,
                                        String subject, String predicate, String value, String valueType,
                                        String unit, String certainty, String factKeyOverride) {
        // 优先使用模型输出的领域 factKey（如 growth_fund.unlock.min_level），不要丢弃；
        // 该 factKey 尚未经过领域词汇表校验，后续应由 BusinessConceptService 或人工词汇表归一化。
        String resolvedFactKey = safe(factKeyOverride).isBlank()
                ? factKey(annotation.projectId(), annotation.requirementVersion(), subject, predicate)
                : safe(factKeyOverride);
        // 保留语义摘要作为检索文本：数据库预过滤命中 semantic_summary 但结构化字段
        // （subject/predicate/value）未包含查询词时，评分阶段靠 searchText 兜底，避免候选被归零过滤。
        String searchText = safe(annotation.semanticSummary());
        if (searchText.isEmpty()) searchText = safe(annotation.rawText());
        return new UnifiedKnowledgeClaim(
                claimId, annotation.projectId(), annotation.requirementVersion(),
                resolvedFactKey,
                safe(subject), safe(predicate), safe(value), safe(valueType), safe(unit),
                SourceType.REQUIREMENT_SEMANTIC, authority(certainty), KnowledgeStatus.EXTRACTED,
                annotation.requirementVersion(), null,
                "requirement-semantic:" + annotation.annotationId() + claimIdSuffix(claimId),
                safe(subject), searchText);
    }

    private void add(Map<String, UnifiedKnowledgeClaim> unique, UnifiedKnowledgeClaim claim, String certainty) {
        // INFERRED / UNKNOWN 默认不进入候选：模型推断未获原文直接支撑，需要显式开关放行；
        // EXPLICIT / DERIVED（同块推导）不受此限制。
        if (!properties.allowInferredCandidate() && inferred(certainty)) return;
        // factKey 必须纳入去重键：不同领域事实（growth_fund.reward_currency 与 lottery.reward_currency）
        // 可能拥有相同主体/谓词/值，仅按字段值折叠会静默丢失领域事实。
        String key = (safe(claim.factKey()) + "|" + safe(claim.subject()) + "|" + safe(claim.predicate())
                + "|" + safe(claim.value()) + "|" + safe(claim.unit())
                + "|" + safe(claim.valueType())).toLowerCase(Locale.ROOT);
        unique.putIfAbsent(key, claim);
    }

    private boolean inferred(String certainty) {
        return !"EXPLICIT".equals(certainty) && !"DERIVED".equals(certainty);
    }

    /** 原文明确表达的候选最多 SECONDARY（原文 Chunk 才是 PRIMARY），其余一律 DERIVED。 */
    private Authority authority(String certainty) {
        return "EXPLICIT".equals(certainty) ? Authority.SECONDARY : Authority.DERIVED;
    }

    /** claimId 形如 annotationId#entity-3，证据指针只保留 # 后缀。 */
    private String claimIdSuffix(String claimId) {
        int index = claimId.indexOf('#');
        return index < 0 ? "" : claimId.substring(index);
    }

    private String factKey(String projectId, String version, String subject, String predicate) {
        return (safe(projectId) + "|" + safe(version) + "|" + safe(subject) + "|" + safe(predicate))
                .toLowerCase(Locale.ROOT);
    }

    /** 数值归一化展示：整数去掉小数尾巴，避免 30.0 与参数表 30 被误判为不同值。 */
    private String trimNumber(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
