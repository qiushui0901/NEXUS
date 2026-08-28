package com.example.requirementrag.knowledge.multisource.vector;

import com.example.requirementrag.conflict.KnowledgeConflictModels.Authority;
import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeClaimRecord;
import com.example.requirementrag.knowledge.multisource.alignment.GameplayCardModuleResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeClaimVectorBlockBuilderTest {

    private final KnowledgeClaimVectorProperties properties = new KnowledgeClaimVectorProperties(
            true, true, true, false,
            "knowledge_claims_live", "knowledge-claim-vector-v2", "knowledge-claim-text-v2",
            200, 3, 32, 3, 2, "target/test-vector.db", "ACTIVE_DOC",
            100_000, false, "");
    private final KnowledgeClaimVectorTextComposer composer =
            new KnowledgeClaimVectorTextComposer(properties);

    @Test
    void gameplayCardBlockKeepsAllClaimsWhenPayloadFits() {
        List<KnowledgeClaimRecord> claims = new ArrayList<>();
        for (int index = 0; index < 201; index++) {
            claims.add(new KnowledgeClaimRecord(
                    "claim-" + index, "immortal", "dv-immortal-5.1-data-ImmortalFarmChest",
                    SourceType.PARAMETER_TABLE, Authority.PRIMARY,
                    "immortal|5.1|Sheet1|field-" + index + "|value", "field-" + index,
                    "value", String.valueOf(index), "INTEGER", "个", "SUPPORTED",
                    null, null, null, "RULE", null, null, null));
        }

        KnowledgeClaimVectorBlockBuilder builder = new KnowledgeClaimVectorBlockBuilder(
                composer, null, properties);
        List<KnowledgeClaimVectorBlockBuilder.SemanticBlock> blocks = builder.build(claims, "5.1");

        assertThat(blocks).hasSize(1);
        assertThat(blocks).extracting(KnowledgeClaimVectorBlockBuilder.SemanticBlock::groupName)
                .containsOnly("山河图");
        assertThat(blocks.stream().flatMap(block -> block.claimIds().stream()).distinct().toList())
                .hasSize(201)
                .containsExactlyInAnyOrderElementsOf(claims.stream()
                        .map(KnowledgeClaimRecord::claimId).toList());
    }

    @Test
    void semanticEnhancementAppendsWithinPayloadLimit() {
        KnowledgeClaimVectorProperties boundedProperties = new KnowledgeClaimVectorProperties(
                true, true, true, false,
                "knowledge_claims_live", "knowledge-claim-vector-v2", "knowledge-claim-text-v2",
                200, 3, 32, 3, 2, "target/test-vector.db", "ACTIVE_DOC",
                4_000, true, "gpt-5.6-luna");
        KnowledgeClaimVectorTextComposer boundedComposer =
                new KnowledgeClaimVectorTextComposer(boundedProperties);
        KnowledgeClaimVectorSemanticEnhancer enhancer = mock(KnowledgeClaimVectorSemanticEnhancer.class);
        when(enhancer.enhance(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.of("召回辅助表达 ".repeat(2_000)));
        KnowledgeClaimVectorBlockBuilder builder = new KnowledgeClaimVectorBlockBuilder(
                boundedComposer, enhancer, boundedProperties);
        SemanticBlockFixture fixture = new SemanticBlockFixture(builder, boundedComposer);

        String enhanced = builder.enhancedText("immortal", "5.1", fixture.block());

        assertThat(enhanced).startsWith(fixture.block().deterministicText());
        assertThat(enhanced).hasSizeLessThanOrEqualTo(4_000);
    }

    @Test
    void failedSemanticEnhancementKeepsDeterministicText() {
        KnowledgeClaimVectorSemanticEnhancer enhancer = mock(KnowledgeClaimVectorSemanticEnhancer.class);
        when(enhancer.enhance(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(Optional.empty());
        KnowledgeClaimVectorBlockBuilder builder = new KnowledgeClaimVectorBlockBuilder(
                composer, enhancer, properties);
        SemanticBlockFixture fixture = new SemanticBlockFixture(builder, composer);

        assertThat(builder.enhancedText("immortal", "5.1", fixture.block()))
                .isEqualTo(fixture.block().deterministicText());
    }

    @Test
    void gameplayCardResolverMergesVersionAndUiPageSuffixes() {
        GameplayCardModuleResolver resolver = new GameplayCardModuleResolver();
        assertThat(resolver.resolve(claim(
                "dv-immortal-5.1-prd-联盟_ver_2_", "联盟管理"))).isEqualTo("联盟");
        assertThat(resolver.resolve(claim(
                "dv-immortal-5.1-prd-山河图-宠物农场", "宠物奖励"))).isEqualTo("山河图");
    }

    private record SemanticBlockFixture(KnowledgeClaimVectorBlockBuilder builder,
                                         KnowledgeClaimVectorTextComposer composer) {
        private KnowledgeClaimVectorBlockBuilder.SemanticBlock block() {
            return builder.build(List.of(claimRecord()), "5.1").get(0);
        }

        private KnowledgeClaimRecord claimRecord() {
            return new KnowledgeClaimRecord(
                    "claim-fixture", "immortal", "dv-immortal-5.1-prd-签到", SourceType.REQUIREMENT,
                    Authority.PRIMARY, "签到.rule", "玩家", "累计签到达到 8 天", "领取碎片 10",
                    "TEXT", "个", "SUPPORTED", null, null, null, "RULE", null, null, null);
        }
    }

    private KnowledgeClaimRecord claim(String documentVersionId, String subject) {
        return new KnowledgeClaimRecord(
                "claim-" + subject, "immortal", documentVersionId, SourceType.REQUIREMENT,
                Authority.PRIMARY, "immortal|5.1|" + subject + "|rule", subject,
                "rule", "支持", "TEXT", null, "SUPPORTED", null, null, null,
                "RULE", null, null, null);
    }
}
