package com.example.requirementrag.knowledge.multisource.entity;

import com.example.requirementrag.knowledge.multisource.alignment.AlignmentTestSupport;
import com.example.requirementrag.knowledge.multisource.alignment.BusinessConceptService;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.ConceptMember;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.LoadedCode;
import com.example.requirementrag.knowledge.multisource.alignment.VersionContextService;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.EntityMention;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.EntityResolution;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.MatchMethod;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.MentionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EntityResolverServiceTest {
    @TempDir Path tempDir;

    private EntityResolverService resolver(AlignmentTestSupport.Stores stores) {
        EntityExtractionProperties properties = new EntityExtractionProperties(
                true, "test-model", 8, 50_000, 200, 50, 100, 100, 0.7, false, 1);
        EntityLlmAssistant llm = new EntityLlmAssistant(null, null, properties,
                new EntityExtractionValidator(properties));
        return new EntityResolverService(stores.alignment(), properties, llm);
    }

    private AlignmentTestSupport.Stores seeded(String subject) {
        AlignmentTestSupport.Stores stores = AlignmentTestSupport.stores(tempDir);
        AlignmentTestSupport.seedParameter(stores, "5.1", subject, "12", "combat");
        BusinessConceptService service = new BusinessConceptService(
                stores.multiSource(), stores.alignment(), AlignmentTestSupport.stubLoader(LoadedCode.empty()),
                new VersionContextService(stores.alignment(), AlignmentTestSupport.stubLoader(LoadedCode.empty())));
        service.build("immortal", "5.1");
        return stores;
    }

    /** 向概念直插一个代码成员（display_name 不成别名，模拟其它管道加入的成员）。 */
    private void addCodeMember(AlignmentTestSupport.Stores stores, String subject, String memberName) {
        String conceptId = stores.alignment().findConceptIdsByAlias("immortal", subject).get(0);
        stores.alignment().upsertMember(new ConceptMember(
                "cm-" + memberName, "immortal", conceptId, null, "CODE",
                "IMPLEMENTATION", memberName, memberName,
                "immortal-game-service", "abc123", "code:" + memberName,
                "5.1", "vc-1", null));
    }

    @Test
    void resolvesViaConfirmedAlias() {
        AlignmentTestSupport.Stores stores = seeded("攻击力");
        EntityResolution resolution = resolver(stores).resolve("immortal", "攻击力是多少");

        assertThat(resolution.resolved()).isNotEmpty();
        assertThat(resolution.resolved().get(0).matchMethod()).isEqualTo(MatchMethod.CONFIRMED_ALIAS);
        assertThat(resolution.resolved().get(0).status()).isEqualTo(MentionStatus.RESOLVED);
    }

    @Test
    void unresolvedWhenNothingMatches() {
        AlignmentTestSupport.Stores stores = seeded("攻击力");
        EntityResolution resolution = resolver(stores).resolve("immortal", "副本掉率");

        assertThat(resolution.resolved()).isEmpty();
        assertThat(resolution.candidates()).isEmpty();
        assertThat(resolution.warnings()).contains("ENTITY_UNRESOLVED");
    }

    @Test
    void resolvesViaCodeSymbolMemberNameWhenAliasMisses() {
        AlignmentTestSupport.Stores stores = seeded("攻击力");
        addCodeMember(stores, "攻击力", "AttackPowerImpl");

        EntityResolution resolution = resolver(stores).resolve("immortal", "AttackPowerImpl");

        assertThat(resolution.resolved()).hasSize(1);
        assertThat(resolution.resolved().get(0).matchMethod()).isEqualTo(MatchMethod.MEMBER_NAME);
        assertThat(resolution.resolved().get(0).status()).isEqualTo(MentionStatus.RESOLVED);
    }

    @Test
    void ambiguousCandidatesMarkedNeedsReviewWithoutLlmSelection() {
        AlignmentTestSupport.Stores stores = AlignmentTestSupport.stores(tempDir);
        AlignmentTestSupport.seedParameter(stores, "5.1", "cooldownA", "5", "combat");
        AlignmentTestSupport.seedParameter(stores, "5.1", "cooldownB", "10", "combat");
        BusinessConceptService service = new BusinessConceptService(
                stores.multiSource(), stores.alignment(), AlignmentTestSupport.stubLoader(LoadedCode.empty()),
                new VersionContextService(stores.alignment(), AlignmentTestSupport.stubLoader(LoadedCode.empty())));
        service.build("immortal", "5.1");
        addCodeMember(stores, "cooldownA", "cooldownLimiter");
        addCodeMember(stores, "cooldownB", "cooldownTracker");

        EntityResolution resolution = resolver(stores)
                .resolve("immortal", "cooldownLimiter 和 cooldownTracker 支持到多少");

        assertThat(resolution.resolved()).isEmpty();
        assertThat(resolution.candidates()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(resolution.warnings()).contains("ENTITY_NEEDS_REVIEW");
    }

    @Test
    void neverFabricatesEntityId() {
        AlignmentTestSupport.Stores stores = seeded("攻击力");
        EntityResolution resolution = resolver(stores).resolve("immortal", "不存在的实体XYZ");

        assertThat(resolution.resolved()).isEmpty();
        for (EntityMention mention : resolution.resolved()) {
            assertThat(mention.entityId()).startsWith("con:");
        }
    }
}