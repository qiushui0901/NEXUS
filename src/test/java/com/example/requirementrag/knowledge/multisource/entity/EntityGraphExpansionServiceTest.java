package com.example.requirementrag.knowledge.multisource.entity;

import com.example.requirementrag.knowledge.multisource.alignment.AlignmentTestSupport;
import com.example.requirementrag.knowledge.multisource.alignment.BusinessConceptService;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.AlignmentRelation;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.LoadedCode;
import com.example.requirementrag.knowledge.multisource.alignment.VersionContextService;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.EntitySearchResponse;
import com.example.requirementrag.knowledge.multisource.entity.EntityGraphExpansionService.RelatedGraph;
import com.example.requirementrag.knowledge.multisource.entity.EntityGraphExpansionService.EntityRetrievalMetrics;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.EntityMention;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.EntityQueryPlan;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.MatchMethod;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.MentionStatus;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.QueryIntent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EntityGraphExpansionServiceTest {
    @TempDir Path tempDir;

    @Test
    void expandsOneHopRelationsToRelatedEntityAndMapsVectorHits() {
        AlignmentTestSupport.Stores stores = AlignmentTestSupport.stores(tempDir);
        AlignmentTestSupport.seedParameter(stores, "5.1", "Attack", "100", "combat");
        AlignmentTestSupport.seedParameter(stores, "5.1", "Defense", "50", "combat");
        BusinessConceptService builder = new BusinessConceptService(
                stores.multiSource(), stores.alignment(), AlignmentTestSupport.stubLoader(LoadedCode.empty()),
                new VersionContextService(stores.alignment(), AlignmentTestSupport.stubLoader(LoadedCode.empty())));
        builder.buildProject("immortal");

        // 在两个实体的 claim 之间建立对齐关系
        String attackClaim = stores.alignment().findMembers("immortal",
                stores.alignment().findConceptIdsByAlias("immortal", "Attack").get(0), "5.1").get(0).claimId();
        String defenseClaim = stores.alignment().findMembers("immortal",
                stores.alignment().findConceptIdsByAlias("immortal", "Defense").get(0), "5.1").get(0).claimId();
        stores.alignment().saveAlignmentRelation(new AlignmentRelation(
                "rel-1", "immortal", "5.1", "vc-1",
                attackClaim, null, "CLAIM", defenseClaim, null, "CLAIM",
                "RELATED_TO", "RULE_CONFIRMED", "RULE_CONFIRMED", 0.9,
                null, "vc-1", "vc-1", "测试关系", null, null));

        EntityGraphExpansionService service =
                new EntityGraphExpansionService(stores.multiSource(), stores.alignment());

        // 构造一个只含 Attack 实体的响应
        EntityMention mention = new EntityMention("Attack",
                stores.alignment().findConceptIdsByAlias("immortal", "Attack").get(0),
                "Attack", MatchMethod.CONFIRMED_ALIAS, 1.0, MentionStatus.RESOLVED);
        EntityQueryPlan plan = new EntityQueryPlan("immortal", "攻击是多少", List.of(mention),
                QueryIntent.NUMERIC_VALUE, List.of(), false, true, false, true);
        EntitySearchResponse response = new EntitySearchResponse("攻击是多少", plan,
                new EntityEvidenceAggregator(stores.multiSource(), stores.alignment(),
                AlignmentTestSupport.stubLoader(LoadedCode.empty()))
                        .aggregate("immortal", plan, List.of(mention), new EntityEvidenceAggregator.Options(20, true, true, true, true)),
                EntityEvidenceModels.FactAssessment.EMPTY, List.of(), List.of());

        RelatedGraph graph = service.expand("immortal", response);

        assertThat(graph.links()).extracting(l -> l.relationType()).contains("RELATED_TO");
        assertThat(graph.entities()).isNotEmpty();
        // 相关实体是 Defense（而非 Attack 自身）
        assertThat(graph.entities()).anyMatch(e -> e.canonicalName().equals("Defense"));

        // 向量/Claim 命中映射回实体
        assertThat(service.mapVectorHitsToEntities("immortal", List.of(defenseClaim)))
                .contains(stores.alignment().findConceptIdsByAlias("immortal", "Defense").get(0));
    }

    @Test
    void reverseRelationExpandsWhenSeedIsTarget() {
        // 关系 Attack->Defense；以 Defense 为种子时应扩展出 Attack（反向关系遍历）
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

        EntityGraphExpansionService service =
                new EntityGraphExpansionService(stores.multiSource(), stores.alignment());
        // 以 Defense 为实体种子
        EntityMention mention = new EntityMention("Defense",
                stores.alignment().findConceptIdsByAlias("immortal", "Defense").get(0),
                "Defense", MatchMethod.CONFIRMED_ALIAS, 1.0, MentionStatus.RESOLVED);
        EntityQueryPlan plan = new EntityQueryPlan("immortal", "防御是多少", List.of(mention),
                QueryIntent.NUMERIC_VALUE, List.of(), false, true, false, true);
        EntitySearchResponse response = new EntitySearchResponse("防御是多少", plan,
                new EntityEvidenceAggregator(stores.multiSource(), stores.alignment(),
                        AlignmentTestSupport.stubLoader(LoadedCode.empty()))
                        .aggregate("immortal", plan, List.of(mention),
                                new EntityEvidenceAggregator.Options(20, true, true, true, true)),
                EntityEvidenceModels.FactAssessment.EMPTY, List.of(), List.of());

        RelatedGraph graph = service.expand("immortal", response);

        // 反向扩展应找到 Attack（而非再次返回 Defense 自身）
        assertThat(graph.entities()).anyMatch(e -> e.canonicalName().equals("Attack"));
        assertThat(graph.entities())
                .noneMatch(e -> e.canonicalName().equals("Defense") && e.viaClaimId() == null);
    }

    @Test
    void metricsReflectCoverageAndPresence() {
        AlignmentTestSupport.Stores stores = AlignmentTestSupport.stores(tempDir);
        AlignmentTestSupport.seedParameter(stores, "5.0", "Attack", "90", "combat");
        AlignmentTestSupport.seedParameter(stores, "5.1", "Attack", "100", "combat");
        BusinessConceptService builder = new BusinessConceptService(
                stores.multiSource(), stores.alignment(), AlignmentTestSupport.stubLoader(LoadedCode.empty()),
                new VersionContextService(stores.alignment(), AlignmentTestSupport.stubLoader(LoadedCode.empty())));
        builder.buildProject("immortal");

        EntityMention mention = new EntityMention("Attack",
                stores.alignment().findConceptIdsByAlias("immortal", "Attack").get(0),
                "Attack", MatchMethod.CONFIRMED_ALIAS, 1.0, MentionStatus.RESOLVED);
        EntityQueryPlan plan = new EntityQueryPlan("immortal", "攻击是多少", List.of(mention),
                QueryIntent.NUMERIC_VALUE, List.of(), false, true, false, true);
        EntitySearchResponse response = new EntitySearchResponse("攻击是多少", plan,
                new EntityEvidenceAggregator(stores.multiSource(), stores.alignment(),
                AlignmentTestSupport.stubLoader(LoadedCode.empty()))
                        .aggregate("immortal", plan, List.of(mention), new EntityEvidenceAggregator.Options(20, true, true, true, true)),
                EntityEvidenceModels.FactAssessment.EMPTY, List.of(), List.of());

        EntityRetrievalMetrics metrics = new EntityGraphExpansionService(stores.multiSource(), stores.alignment())
                .metrics(response);

        assertThat(metrics.entityCount()).isEqualTo(1);
        assertThat(metrics.versionCoverage()).isEqualTo(2);
        assertThat(metrics.hasParameters()).isTrue();
        assertThat(metrics.hasCode()).isFalse();
    }
}