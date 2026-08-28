package com.example.requirementrag.knowledge.multisource.entity;

import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeClaimRecord;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeRelation;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeStore;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricAlignmentStore;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.AlignmentRelation;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.BusinessConcept;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.EntitySearchResponse;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.EntityView;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.FactRef;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.VersionFactBlock;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 局部图扩展（dev md §14 Phase 6）：实体一跳/两跳关系召回 + 向量/Claim 命中映射回实体。
 *
 * <p>事实权威不变：图扩展只追加召回线索与关系视图，不改变 factAssessment、不写图。
 * 图/向量只做召回增强；实体层语义 = 全部已发布文档（不绑定 active manifest）。
 * 项目级历史 Claim collection 与向量索引扩大以评测数据证明收益为前提（dev md §10.3 第 3 种），
 * 本阶段仅提供候选映射能力与指标，不接 Qdrant。
 */
@Service
public class EntityGraphExpansionService {

    private static final int MAX_NODES = 30;
    private static final int MAX_RELATIONS = 60;
    private static final int MAX_DEPTH = 2;

    private final MultiSourceKnowledgeStore knowledgeStore;
    private final CodeCentricAlignmentStore alignmentStore;

    public EntityGraphExpansionService(MultiSourceKnowledgeStore knowledgeStore,
                                       CodeCentricAlignmentStore alignmentStore) {
        this.knowledgeStore = knowledgeStore;
        this.alignmentStore = alignmentStore;
    }

    /** 相关实体节点。 */
    public record RelatedEntity(String entityId, String canonicalName, String relationType, String viaClaimId) {
    }

    /** 相关关系边。 */
    public record RelatedLink(String relationId, String sourceClaimId, String targetClaimId,
                              String relationType, String matchMethod, String status) {
    }

    /** 局部图扩展结果。 */
    public record RelatedGraph(List<RelatedEntity> entities, List<RelatedLink> links, int depth) {
    }

    /** 实体检索指标（评测用）。 */
    public record EntityRetrievalMetrics(int entityCount, int relationCount, int versionCoverage,
                                         boolean hasCode, boolean hasParameters, boolean hasTests) {
    }

    /** 一跳/两跳关系召回：从实体所有事实 Claim 出发。 */
    public RelatedGraph expand(String projectId, EntitySearchResponse response) {
        List<String> seedClaimIds = claimIdsOf(response.entities());
        if (seedClaimIds.isEmpty()) {
            return new RelatedGraph(List.of(), List.of(), 0);
        }
        Map<String, BusinessConcept> conceptById = new HashMap<>();
        for (BusinessConcept concept : alignmentStore.findConcepts(projectId)) {
            conceptById.put(concept.conceptId(), concept);
        }

        Set<String> publishedClaimIds = knowledgeStore.findPublishedClaimIdsByIdsAll(projectId, seedClaimIds);
        Set<String> visitedClaims = new LinkedHashSet<>(publishedClaimIds);
        List<String> frontier = new ArrayList<>(publishedClaimIds);
        List<RelatedLink> links = new ArrayList<>();
        Set<String> seenLinks = new LinkedHashSet<>();
        int depth = 0;
        // 遍历中维护 claimId → businessVersion（含二跳节点），供跨源 knowledge_relation 查询使用
        Map<String, String> claimVersion = new LinkedHashMap<>(
                knowledgeStore.findPublishedClaimVersions(projectId, publishedClaimIds));
        for (String seed : publishedClaimIds) {
            String responseVersion = versionOf(response, seed);
            if (responseVersion != null && !responseVersion.isBlank()) {
                claimVersion.put(seed, responseVersion);
            }
        }

        while (!frontier.isEmpty() && depth < MAX_DEPTH && links.size() < MAX_RELATIONS) {
            List<String> next = new ArrayList<>();
            for (String claimId : frontier) {
                for (AlignmentRelation relation : alignmentStore.findAlignmentRelationsForClaim(projectId, claimId)) {
                    if (relation.version() == null || !relation.version().equals(claimVersion.get(claimId))) {
                        continue;
                    }
                    Set<String> relationClaims = new LinkedHashSet<>();
                    if (relation.sourceClaimId() != null) relationClaims.add(relation.sourceClaimId());
                    if (relation.targetClaimId() != null) relationClaims.add(relation.targetClaimId());
                    Map<String, String> endpointVersions =
                            knowledgeStore.findPublishedClaimVersions(projectId, relationClaims);
                    if (relationClaims.size() < 2
                            || endpointVersions.size() != relationClaims.size()
                            || endpointVersions.values().stream().anyMatch(version -> !relation.version().equals(version))) {
                        continue;
                    }
                    if (!seenLinks.add(relation.relationId()) || links.size() >= MAX_RELATIONS) {
                        continue;
                    }
                    // 反向关系也正确扩展：当前 claim 是 target 时取 source
                    String other = claimId.equals(relation.sourceClaimId())
                            ? relation.targetClaimId()
                            : relation.sourceClaimId();
                    String relationStatus = knowledgeStore.isPublishedEvidenceForRelation(
                            projectId, relation.version(), relation.evidenceId(),
                            relation.sourceClaimId(), relation.targetClaimId())
                            ? relation.status() : "UNVERIFIED";
                    links.add(new RelatedLink(relation.relationId(), relation.sourceClaimId(),
                            relation.targetClaimId(), relation.relationType(),
                            relation.matchMethod(), relationStatus));
                    if (other != null && !other.isBlank() && visitedClaims.add(other)) {
                        next.add(other);
                        if (relation.version() != null && !relation.version().isBlank()) {
                            claimVersion.putIfAbsent(other, relation.version());
                        }
                    }
                }
                if (links.size() >= MAX_RELATIONS) {
                    break;
                }
            }
            frontier = next;
            depth++;
        }

        // 其它版本的 knowledge_relation（跨源图关系）；版本取自遍历维护的映射
        Map<String, Set<String>> claimsByVersion = new LinkedHashMap<>();
        for (String claimId : visitedClaims) {
            String version = claimVersion.getOrDefault(claimId, "");
            if (version.isBlank()) continue;
            claimsByVersion.computeIfAbsent(version, ignored -> new LinkedHashSet<>()).add(claimId);
        }
        for (Map.Entry<String, Set<String>> entry : claimsByVersion.entrySet()) {
            String version = entry.getKey();
            if (version == null || version.isBlank()) continue;
            for (KnowledgeRelation relation : knowledgeStore.findRelationsForClaims(projectId, version, entry.getValue())) {
                Set<String> relationClaims = Set.of(relation.sourceClaimId(), relation.targetClaimId());
                Map<String, String> endpointVersions =
                        knowledgeStore.findPublishedClaimVersions(projectId, relationClaims);
                if (endpointVersions.size() != relationClaims.size()
                        || endpointVersions.values().stream().anyMatch(endpointVersion -> !version.equals(endpointVersion))) {
                    continue;
                }
                if (!seenLinks.add(relation.relationId()) || links.size() >= MAX_RELATIONS) {
                    continue;
                }
                String relationStatus = knowledgeStore.isPublishedEvidenceForRelation(
                        projectId, version, relation.evidenceId(),
                        relation.sourceClaimId(), relation.targetClaimId())
                        ? relation.status() : "UNVERIFIED";
                links.add(new RelatedLink(relation.relationId(), relation.sourceClaimId(),
                        relation.targetClaimId(), relation.relationType(), relation.extractionMethod(),
                        relationStatus));
            }
        }

        // 相关 Claim → 实体节点（去重、封顶）
        Map<String, String> viaByEntity = new LinkedHashMap<>();
        Set<String> known = new LinkedHashSet<>();
        for (String claimId : visitedClaims) {
            if (seedClaimIds.contains(claimId)) continue;
            String version = claimVersion.getOrDefault(claimId, "");
            for (String conceptId : alignmentStore.findConceptIdsByClaim(projectId, claimId, version)) {
                if (known.size() >= MAX_NODES) break;
                if (known.add(conceptId)) {
                    BusinessConcept concept = conceptById.get(conceptId);
                    viaByEntity.put(conceptId, claimId);
                    known.add(conceptId);
                }
            }
        }
        List<RelatedEntity> entities = new ArrayList<>();
        for (String conceptId : known) {
            BusinessConcept concept = conceptById.get(conceptId);
            entities.add(new RelatedEntity(conceptId,
                    concept == null ? conceptId : concept.displayName(),
                    "RELATED_TO", viaByEntity.get(conceptId)));
        }
        return new RelatedGraph(entities, links, depth);
    }

    /** 向量/Claim 命中映射回实体（Qdrant claim 命中 → entityId 集合）。 */
    public Set<String> mapVectorHitsToEntities(String projectId, List<String> claimIds) {
        Set<String> entityIds = new LinkedHashSet<>();
        if (claimIds == null || claimIds.isEmpty()) {
            return entityIds;
        }
        Set<String> publishedClaimIds = knowledgeStore.findPublishedClaimIdsByIdsAll(projectId, claimIds);
        Map<String, String> versions = knowledgeStore.findPublishedClaimVersions(projectId, publishedClaimIds);
        for (String claimId : publishedClaimIds) {
            entityIds.addAll(alignmentStore.findConceptIdsByClaim(projectId, claimId,
                    versions.get(claimId)));
            if (entityIds.size() >= MAX_NODES) {
                break;
            }
        }
        return entityIds;
    }

    /** 实体检索指标（版本覆盖 = 命中版本去重数）。 */
    public EntityRetrievalMetrics metrics(EntitySearchResponse response) {
        Set<String> versions = new LinkedHashSet<>();
        int relations = 0;
        boolean hasCode = false;
        boolean hasParameters = false;
        boolean hasTests = false;
        for (EntityView view : response.entities()) {
            hasCode |= !view.currentFacts().code().isEmpty();
            hasParameters |= !view.currentFacts().parameterTables().isEmpty();
            hasTests |= !view.currentFacts().testResults().isEmpty();
            relations += view.relations().size();
            for (VersionFactBlock block : view.timeline()) {
                versions.add(block.businessVersion());
            }
        }
        return new EntityRetrievalMetrics(response.entities().size(), relations,
                versions.size(), hasCode, hasParameters, hasTests);
    }

    private List<String> claimIdsOf(List<EntityView> views) {
        List<String> ids = new ArrayList<>();
        for (EntityView view : views) {
            for (FactRef ref : allFacts(view)) {
                if (ref.claimId() != null) {
                    ids.add(ref.claimId());
                }
            }
        }
        return ids;
    }

    private String versionOf(EntitySearchResponse response, String claimId) {
        for (EntityView view : response.entities()) {
            for (FactRef ref : allFacts(view)) {
                if (claimId.equals(ref.claimId()) && ref.businessVersion() != null
                        && !ref.businessVersion().isBlank()) {
                    return ref.businessVersion();
                }
            }
        }
        return "";
    }

    private List<FactRef> allFacts(EntityView view) {
        List<FactRef> facts = new ArrayList<>();
        facts.addAll(view.currentFacts().code());
        facts.addAll(view.currentFacts().parameterTables());
        facts.addAll(view.currentFacts().testResults());
        for (VersionFactBlock block : view.timeline()) {
            facts.addAll(block.requirements());
            facts.addAll(block.parameterTables());
            facts.addAll(block.tests());
        }
        return facts;
    }
}