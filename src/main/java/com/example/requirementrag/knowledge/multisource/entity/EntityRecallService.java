package com.example.requirementrag.knowledge.multisource.entity;

import com.example.requirementrag.knowledge.multisource.MultiSourceCandidateAdapter.CandidateLoad;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.EntityRecallResponse;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.EntitySearchResponse;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.EntityView;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.FactAssessment;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.VectorHit;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceAggregator.Options;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.EntityMention;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.EntityQueryPlan;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.MatchMethod;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.MentionStatus;
import com.example.requirementrag.knowledge.multisource.entity.EntityGraphExpansionService.RelatedEntity;
import com.example.requirementrag.knowledge.multisource.entity.EntityGraphExpansionService.RelatedGraph;
import com.example.requirementrag.knowledge.multisource.entity.EntityQueryService.EntitySearchRequest;
import com.example.requirementrag.knowledge.multisource.vector.ClaimVectorCandidateAdapter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图/向量可选召回方式（dev md §13 可选召回）：在确定性检索之上叠加局部图扩展与可选向量补召回。
 *
 * <ul>
 *   <li>GRAPH_VECTOR：解析命中实体 + 一跳/二跳关系实体（图扩展）+ 向量命中映射实体（可选），并集水化；</li>
 *   <li>HYBRID：与 GRAPH_VECTOR 相同的并集语义（图/向量只做召回增强，不改变事实权威）；</li>
 *   <li>DETERMINISTIC：此服务不参与（控制器直接走 {@link EntityQueryService#search}）。</li>
 * </ul>
 *
 * <p>向量补召回仅当 ClaimVectorCandidateAdapter 装配（candidate-retrieval-enabled）且代际存在时返回，
 * 失败/缺失一律降级为空并告警，绝不抛异常阻断确定性结果。证据包基于合并实体集（种子 + 图 + 向量），
 * 图/向量实体的事实、引用与评估同态并入，回答方可引用其真实 Evidence。
 */
@Service
public class EntityRecallService {

    private final EntityQueryService entityQueryService;
    private final EntityEvidenceAggregator aggregator;
    private final EntityGraphExpansionService graphExpansionService;
    private final ObjectProvider<ClaimVectorCandidateAdapter> vectorAdapterProvider;

    public EntityRecallService(EntityQueryService entityQueryService,
                               EntityEvidenceAggregator aggregator,
                               EntityGraphExpansionService graphExpansionService,
                               ObjectProvider<ClaimVectorCandidateAdapter> vectorAdapterProvider) {
        this.entityQueryService = entityQueryService;
        this.aggregator = aggregator;
        this.graphExpansionService = graphExpansionService;
        this.vectorAdapterProvider = vectorAdapterProvider;
    }

    /** 图/向量召回：确定性证据 + 局部图扩展 + 可选向量补召回（合并实体集同态进证据包）。 */
    public EntityRecallResponse search(EntitySearchRequest request, RecallMode mode) {
        String projectId = request.projectId();
        EntitySearchResponse deterministic = entityQueryService.search(request);
        EntityQueryPlan plan = deterministic.plan();
        List<String> warnings = new ArrayList<>(deterministic.warnings());

        // 1. 局部图扩展（种子实体的 Claim 出发，一跳/二跳关系；实体层同态 = 全部已发布文档）
        RelatedGraph graph = graphExpansionService.expand(projectId, deterministic);

        // 2. 可选 Claim 向量补召回（覆盖请求全部版本；失败降级为空 + 诊断告警）
        List<VectorHit> vectorHits = vectorRecall(projectId, request, plan, warnings);

        // 3. 合并实体集：种子解析实体 + 图一跳/二跳实体 + 向量命中映射实体（去重、水化）
        List<EntityMention> seed = deterministic.plan().mentions().stream()
                .filter(m -> m.entityId() != null)
                .toList();
        List<EntityMention> related = new ArrayList<>();
        for (RelatedEntity relatedEntity : graph.entities()) {
            related.add(new EntityMention(relatedEntity.canonicalName(), relatedEntity.entityId(),
                    relatedEntity.canonicalName(), MatchMethod.MEMBER_NAME, 0.6, MentionStatus.RESOLVED));
        }
        // 向量命中 Claim → 实体（复用图扩展的映射，与实体层同态）
        List<String> vectorClaimIds = vectorHits.stream().map(VectorHit::claimId).toList();
        for (String entityId : graphExpansionService.mapVectorHitsToEntities(projectId, vectorClaimIds)) {
            related.add(new EntityMention(entityId, entityId, entityId,
                    MatchMethod.MEMBER_NAME, 0.5, MentionStatus.RESOLVED));
        }
        List<EntityMention> merged = mergeMentions(seed, related);
        int relatedEntityCount = Math.max(0, merged.size() - seed.size());

        // 4. 水化 + 引用 + 合并评估；证据包基于合并实体集（图/向量实体的事实/引用/评估同态并入）
        int perBlock = request.limit() == null ? 20 : Math.max(1, Math.min(50, request.limit()));
        boolean includeCode = request.includeCode() == null || request.includeCode();
        boolean includeParameters = request.includeParameters() == null || request.includeParameters();
        boolean includeTests = request.includeTests() == null || request.includeTests();
        Options options = new Options(perBlock, includeCode, includeParameters, includeTests,
                plan.includeHistory());

        List<EntityView> entities = aggregator.aggregate(projectId, plan, merged, options);
        var citations = aggregator.citations(projectId, entities);
        FactAssessment assessment = entityQueryService.assessMerged(plan, entities);
        EntitySearchResponse evidence = new EntitySearchResponse(
                request.query(), plan, entities, assessment, citations, warnings);

        return new EntityRecallResponse(request.query(), plan, entities, assessment, citations, warnings,
                mode == null ? RecallMode.GRAPH_VECTOR.name() : mode.name(),
                evidence, graph, vectorHits, relatedEntityCount);
    }

    /** 可选向量补召回：adapter 未装配/代际缺失/检索失败 → 空列表 + 诊断告警（不抛异常）。 */
    private List<VectorHit> vectorRecall(String projectId, EntitySearchRequest request,
                                         EntityQueryPlan plan, List<String> warnings) {
        ClaimVectorCandidateAdapter adapter = vectorAdapterProvider.getIfAvailable();
        if (adapter == null) {
            return List.of();
        }
        // 确定性聚合覆盖全部已发布版本（requestedVersions 仅显式版本号）→ 向量补召回必须同范围
        List<String> versions = plan.requestedVersions().isEmpty()
                ? aggregator.publishedBusinessVersions(projectId)
                : plan.requestedVersions();
        if (versions.isEmpty()) {
            return List.of();
        }
        Map<String, VectorHit> distinct = new LinkedHashMap<>();
        boolean anyLoad = false;
        boolean vectorDiagnostic = false;
        for (String version : versions) {
            try {
                CandidateLoad load = adapter.loadDetailed(projectId, version, request.query(), null);
                anyLoad = true;
                for (String diagnostic : load.warnings()) {
                    if (diagnostic == null || diagnostic.isBlank() || warnings.contains(diagnostic)) {
                        continue;
                    }
                    warnings.add(diagnostic);
                    if (diagnostic.contains("CLAIM_VECTOR")) {
                        vectorDiagnostic = true;
                    }
                }
                for (var claim : load.claims()) {
                    if (claim.sourceType() == null) {
                        continue;
                    }
                    distinct.putIfAbsent(claim.claimId(), new VectorHit(
                            claim.claimId(), claim.subject(), claim.sourceType().name()));
                    if (distinct.size() >= 50) {
                        break;
                    }
                }
            } catch (RuntimeException exception) {
                // 单版本失败只丢弃该版本，保留其它版本的成功命中（告警语义=“部分版本不可用”）
                warnings.add("VECTOR_RECALL_UNAVAILABLE:版本 " + version
                        + " 向量召回失败（保留其他版本命中）");
            }
            if (distinct.size() >= 50) {
                break;
            }
        }
        if (distinct.isEmpty() && anyLoad && !vectorDiagnostic) {
            warnings.add("VECTOR_RECALL_EMPTY");
        }
        return List.copyOf(distinct.values());
    }

    private List<EntityMention> mergeMentions(List<EntityMention> seed, List<EntityMention> related) {
        Map<String, EntityMention> byId = new LinkedHashMap<>();
        for (EntityMention mention : seed) {
            byId.putIfAbsent(mention.entityId(), mention);
        }
        for (EntityMention mention : related) {
            byId.putIfAbsent(mention.entityId(), mention);
        }
        return List.copyOf(byId.values());
    }
}