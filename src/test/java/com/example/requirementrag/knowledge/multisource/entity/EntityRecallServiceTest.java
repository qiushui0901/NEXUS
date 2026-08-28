package com.example.requirementrag.knowledge.multisource.entity;

import com.example.requirementrag.knowledge.multisource.MultiSourceCandidateAdapter.CandidateLoad;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.UnifiedKnowledgeClaim;
import com.example.requirementrag.knowledge.multisource.alignment.AlignmentTestSupport;
import com.example.requirementrag.knowledge.multisource.alignment.BusinessConceptService;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.AlignmentRelation;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.LoadedCode;
import com.example.requirementrag.knowledge.multisource.alignment.VersionContextService;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.EntityRecallResponse;
import com.example.requirementrag.knowledge.multisource.entity.EntityQueryService.EntitySearchRequest;
import com.example.requirementrag.knowledge.multisource.vector.ClaimVectorCandidateAdapter;
import com.example.requirementrag.conflict.KnowledgeConflictModels.Authority;
import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EntityRecallServiceTest {
    @TempDir Path tempDir;

    private EntityQueryService queryService(AlignmentTestSupport.Stores stores) {
        EntityExtractionProperties properties = new EntityExtractionProperties(
                true, "test-model", 8, 50_000, 200, 50, 100, 100, 0.7, false, 1);
        EntityExtractionValidator validator = new EntityExtractionValidator(properties);
        EntityLlmAssistant llm = new EntityLlmAssistant(null, null, properties, validator);
        return new EntityQueryService(
                new QuestionEntityAnalyzer(stores.alignment(), properties, llm),
                new EntityResolverService(stores.alignment(), properties, llm),
                new EntityEvidenceAggregator(stores.multiSource(), stores.alignment(),
                        AlignmentTestSupport.stubLoader(LoadedCode.empty())),
                new EntityFactPriorityService());
    }

    private AlignmentTestSupport.Stores seededWithRelation(String queryText) {
        AlignmentTestSupport.Stores stores = AlignmentTestSupport.stores(tempDir);
        AlignmentTestSupport.seedParameter(stores, "5.1", "Attack", "100", "combat");
        AlignmentTestSupport.seedParameter(stores, "5.1", "Defense", "50", "combat");
        BusinessConceptService builder = new BusinessConceptService(
                stores.multiSource(), stores.alignment(), AlignmentTestSupport.stubLoader(LoadedCode.empty()),
                new VersionContextService(stores.alignment(), AlignmentTestSupport.stubLoader(LoadedCode.empty())));
        builder.buildProject("immortal");
        String attackClaim = stores.alignment().findMembers("immortal",
                stores.alignment().findConceptIdsByAlias("immortal", "Attack").get(0), "5.1").get(0).claimId();
        String defenseClaim = stores.alignment().findMembers("immortal",
                stores.alignment().findConceptIdsByAlias("immortal", "Defense").get(0), "5.1").get(0).claimId();
        stores.alignment().saveAlignmentRelation(new AlignmentRelation(
                "rel-1", "immortal", "5.1", "vc-1",
                attackClaim, null, "CLAIM", defenseClaim, null, "CLAIM",
                "RELATED_TO", "RULE_CONFIRMED", "RULE_CONFIRMED", 0.9,
                null, "vc-1", "vc-1", "测试关系", null, null));
        return stores;
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<ClaimVectorCandidateAdapter> noVectorAdapter() {
        ObjectProvider<ClaimVectorCandidateAdapter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<ClaimVectorCandidateAdapter> vectorAdapterReturning(CandidateLoad load) {
        ClaimVectorCandidateAdapter adapter = mock(ClaimVectorCandidateAdapter.class);
        when(adapter.loadDetailed(anyString(), anyString(), anyString(), any()))
                .thenReturn(load);
        ObjectProvider<ClaimVectorCandidateAdapter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(adapter);
        return provider;
    }

    private UnifiedKnowledgeClaim unifiedClaim(String claimId, String subject) {
        return new UnifiedKnowledgeClaim(claimId, "immortal", "5.1", "fk#speed", subject,
                null, "300", "INTEGER", "", SourceType.PARAMETER_TABLE, Authority.PRIMARY,
                com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeStatus.SUPPORTED,
                null, null, "loc", "fk", null);
    }

    private String memberClaimId(AlignmentTestSupport.Stores stores, String alias) {
        String conceptId = stores.alignment().findConceptIdsByAlias("immortal", alias).get(0);
        return stores.alignment().findMembers("immortal", conceptId, "5.1").get(0).claimId();
    }

    @Test
    void graphVectorExpandsGraphAndHydratesRelatedEntities() {
        AlignmentTestSupport.Stores stores = seededWithRelation("攻击");
        EntityQueryService queryService = queryService(stores);
        EntityGraphExpansionService graph = new EntityGraphExpansionService(
                stores.multiSource(), stores.alignment());
        EntityRecallService recallService = new EntityRecallService(
                queryService,
                new EntityEvidenceAggregator(stores.multiSource(), stores.alignment(),
                        AlignmentTestSupport.stubLoader(LoadedCode.empty())),
                graph, noVectorAdapter());

        EntityRecallResponse response = recallService.search(new EntitySearchRequest(
                "immortal", "Attack 攻击力是多少", null, null, true, true, true, 20),
                RecallMode.GRAPH_VECTOR);

        assertThat(response.recallMode()).isEqualTo("GRAPH_VECTOR");
        // 图扩展：Attack 的 Claim 出发 → Defense 相关实体
        assertThat(response.graph().links())
                .extracting(l -> l.relationType()).contains("RELATED_TO");
        assertThat(response.entities()).extracting(v -> v.canonicalName())
                .contains("Attack", "Defense");
        assertThat(response.relatedEntityCount()).isEqualTo(1);
        // 无向量适配器 → 向量补召回为空、不抛异常
        assertThat(response.vectorHits()).isEmpty();
    }

    @Test
    void hybridKeepsDeterministicEvidenceAndRelatedEntities() {
        AlignmentTestSupport.Stores stores = seededWithRelation("攻击");
        EntityQueryService queryService = queryService(stores);
        EntityRecallService recallService = new EntityRecallService(
                queryService,
                new EntityEvidenceAggregator(stores.multiSource(), stores.alignment(),
                        AlignmentTestSupport.stubLoader(LoadedCode.empty())),
                new EntityGraphExpansionService(stores.multiSource(), stores.alignment()),
                noVectorAdapter());

        EntityRecallResponse response = recallService.search(new EntitySearchRequest(
                "immortal", "Attack 攻击力是多少", null, null, true, true, true, 20),
                RecallMode.HYBRID);

        assertThat(response.recallMode()).isEqualTo("HYBRID");
        assertThat(response.evidence()).isNotNull();
        assertThat(response.entities()).hasSize(2);
    }

    @Test
    void deterministicEntitiesMatchSeedWithoutGraph() {
        // DETERMINISTIC 语义下不经过本服务（控制器直走 EntityQueryService），
        // 但这里验证即便误调本服务也保持种子实体为主
        AlignmentTestSupport.Stores stores = seededWithRelation("攻击");
        EntityQueryService queryService = queryService(stores);
        EntityRecallService recallService = new EntityRecallService(
                queryService,
                new EntityEvidenceAggregator(stores.multiSource(), stores.alignment(),
                        AlignmentTestSupport.stubLoader(LoadedCode.empty())),
                new EntityGraphExpansionService(stores.multiSource(), stores.alignment()),
                noVectorAdapter());

        EntityRecallResponse response = recallService.search(new EntitySearchRequest(
                "immortal", "Defense 防御是多少", null, null, true, true, true, 20),
                RecallMode.GRAPH_VECTOR);

        assertThat(response.entities()).extracting(v -> v.canonicalName())
                .contains("Defense");
    }

    @Test
    void vectorHitsMapToEntitiesAndEnterEvidencePackage() {
        // High 2：向量命中 Claim → 映射为实体并进证据包（entities/citations 同态），
        // 不能只显示 vectorHits 而答案仍只有确定性实体。
        AlignmentTestSupport.Stores stores = AlignmentTestSupport.stores(tempDir);
        AlignmentTestSupport.seedParameter(stores, "5.1", "Attack", "100", "combat");
        AlignmentTestSupport.seedParameter(stores, "5.1", "Speed", "300", "combat");
        BusinessConceptService builder = new BusinessConceptService(
                stores.multiSource(), stores.alignment(), AlignmentTestSupport.stubLoader(LoadedCode.empty()),
                new VersionContextService(stores.alignment(), AlignmentTestSupport.stubLoader(LoadedCode.empty())));
        builder.buildProject("immortal");
        String speedClaim = memberClaimId(stores, "Speed");
        EntityQueryService queryService = queryService(stores);
        EntityRecallService recallService = new EntityRecallService(
                queryService,
                new EntityEvidenceAggregator(stores.multiSource(), stores.alignment(),
                        AlignmentTestSupport.stubLoader(LoadedCode.empty())),
                new EntityGraphExpansionService(stores.multiSource(), stores.alignment()),
                vectorAdapterReturning(new CandidateLoad(
                        List.of(unifiedClaim(speedClaim, "Speed")), List.of(), List.of())));

        EntityRecallResponse response = recallService.search(new EntitySearchRequest(
                "immortal", "Attack 攻击力是多少", null, null, true, true, true, 20),
                RecallMode.GRAPH_VECTOR);

        // 向量命中存在，且映射进实体集与证据包
        assertThat(response.vectorHits()).hasSize(1);
        assertThat(response.evidence().entities()).extracting(v -> v.canonicalName())
                .contains("Attack", "Speed");
        assertThat(response.entities()).extracting(v -> v.canonicalName()).contains("Speed");
    }

    @Test
    void vectorRecallCoversAllRequestedVersions() {
        // Medium 7：请求多个版本时向量补召回必须逐版本查询，不能只查第一个
        AlignmentTestSupport.Stores stores = AlignmentTestSupport.stores(tempDir);
        AlignmentTestSupport.seedParameter(stores, "5.1", "Attack", "100", "combat");
        AlignmentTestSupport.seedParameter(stores, "5.2", "Attack", "120", "combat");
        BusinessConceptService builder = new BusinessConceptService(
                stores.multiSource(), stores.alignment(), AlignmentTestSupport.stubLoader(LoadedCode.empty()),
                new VersionContextService(stores.alignment(), AlignmentTestSupport.stubLoader(LoadedCode.empty())));
        builder.buildProject("immortal");
        EntityQueryService queryService = queryService(stores);
        ClaimVectorCandidateAdapter adapter = mock(ClaimVectorCandidateAdapter.class);
        when(adapter.loadDetailed(anyString(), anyString(), anyString(), any()))
                .thenReturn(new CandidateLoad(List.of(), List.of(), List.of()));
        @SuppressWarnings("unchecked")
        ObjectProvider<ClaimVectorCandidateAdapter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(adapter);
        EntityRecallService recallService = new EntityRecallService(
                queryService,
                new EntityEvidenceAggregator(stores.multiSource(), stores.alignment(),
                        AlignmentTestSupport.stubLoader(LoadedCode.empty())),
                new EntityGraphExpansionService(stores.multiSource(), stores.alignment()),
                provider);

        recallService.search(new EntitySearchRequest(
                "immortal", "Attack 攻击力", null, true, true, true, true, 20),
                RecallMode.GRAPH_VECTOR);

        // 5.1 与 5.2 都被向量检索覆盖
        verify(adapter, atLeastOnce()).loadDetailed(eq("immortal"), eq("5.1"), any(), any());
        verify(adapter, atLeastOnce()).loadDetailed(eq("immortal"), eq("5.2"), any(), any());
    }

    @Test
    void vectorDiagnosticsPropagateToWarnings() {
        // Medium 8：向量链路降级诊断必须透传到响应 warnings（不能只显示 VECTOR_RECALL_EMPTY）
        AlignmentTestSupport.Stores stores = seededWithRelation("攻击");
        EntityQueryService queryService = queryService(stores);
        EntityRecallService recallService = new EntityRecallService(
                queryService,
                new EntityEvidenceAggregator(stores.multiSource(), stores.alignment(),
                        AlignmentTestSupport.stubLoader(LoadedCode.empty())),
                new EntityGraphExpansionService(stores.multiSource(), stores.alignment()),
                vectorAdapterReturning(new CandidateLoad(List.of(),
                        List.of("CLAIM_VECTOR_NO_ACTIVE_GENERATION:项目 immortal 版本 5.1 无活跃 Claim 向量代际"),
                        List.of())));

        EntityRecallResponse response = recallService.search(new EntitySearchRequest(
                "immortal", "Attack 攻击力是多少", null, null, true, true, true, 20),
                RecallMode.GRAPH_VECTOR);

        assertThat(response.warnings())
                .anyMatch(w -> w.contains("CLAIM_VECTOR_NO_ACTIVE_GENERATION"));
        // 已有具体诊断时不再叠加泛化的 VECTOR_RECALL_EMPTY
        assertThat(response.warnings()).doesNotContain("VECTOR_RECALL_EMPTY");
    }

    @Test
    void vectorRecallPartialVersionFailureKeepsEarlierHits() {
        // Medium：多版本向量召回，单个版本失败不得丢弃其它版本的成功命中（逐版本独立捕获）
        AlignmentTestSupport.Stores stores = AlignmentTestSupport.stores(tempDir);
        AlignmentTestSupport.seedParameter(stores, "5.1", "Attack", "100", "combat");
        AlignmentTestSupport.seedParameter(stores, "5.2", "Attack", "120", "combat");
        BusinessConceptService builder = new BusinessConceptService(
                stores.multiSource(), stores.alignment(), AlignmentTestSupport.stubLoader(LoadedCode.empty()),
                new VersionContextService(stores.alignment(), AlignmentTestSupport.stubLoader(LoadedCode.empty())));
        builder.buildProject("immortal");
        String attackClaim51 = memberClaimId(stores, "Attack");
        EntityQueryService queryService = queryService(stores);
        ClaimVectorCandidateAdapter adapter = mock(ClaimVectorCandidateAdapter.class);
        when(adapter.loadDetailed(eq("immortal"), eq("5.1"), anyString(), any()))
                .thenReturn(new CandidateLoad(List.of(unifiedClaim(attackClaim51, "Attack")), List.of(), List.of()));
        when(adapter.loadDetailed(eq("immortal"), eq("5.2"), anyString(), any()))
                .thenThrow(new RuntimeException("qdrant down"));
        @SuppressWarnings("unchecked")
        ObjectProvider<ClaimVectorCandidateAdapter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(adapter);
        EntityRecallService recallService = new EntityRecallService(
                queryService,
                new EntityEvidenceAggregator(stores.multiSource(), stores.alignment(),
                        AlignmentTestSupport.stubLoader(LoadedCode.empty())),
                new EntityGraphExpansionService(stores.multiSource(), stores.alignment()),
                provider);

        EntityRecallResponse response = recallService.search(new EntitySearchRequest(
                "immortal", "Attack 攻击力", null, true, true, true, true, 20),
                RecallMode.GRAPH_VECTOR);

        // 5.1 的命中保留（映射进实体），5.2 失败按版本级告警透传
        assertThat(response.vectorHits()).hasSize(1);
        assertThat(response.warnings())
                .anyMatch(w -> w.contains("VECTOR_RECALL_UNAVAILABLE") && w.contains("5.2"));
        assertThat(response.entities()).extracting(v -> v.canonicalName()).contains("Attack");
    }
}