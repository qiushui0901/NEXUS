package com.example.requirementrag.knowledge.multisource.entity;

import com.example.requirementrag.conflict.KnowledgeConflictModels.Authority;
import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeClaimRecord;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeDocumentVersion;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeStatus;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.ParameterClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.ParameterValueType;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.TestCaseClaim;
import com.example.requirementrag.knowledge.multisource.alignment.AlignmentTestSupport;
import com.example.requirementrag.knowledge.multisource.alignment.BusinessConceptService;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.CodeSymbolView;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.LoadedCode;
import com.example.requirementrag.knowledge.multisource.alignment.VersionContextService;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceAggregator.Options;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.ConflictView;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.EntityView;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.VersionFactBlock;
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

class EntityEvidenceAggregatorTest {
    @TempDir Path tempDir;

    private AlignmentTestSupport.Stores stores;

    private AlignmentTestSupport.Stores newStores() {
        stores = AlignmentTestSupport.stores(tempDir);
        return stores;
    }

    private void buildConcepts() {
        BusinessConceptService service = new BusinessConceptService(
                stores.multiSource(), stores.alignment(), AlignmentTestSupport.stubLoader(LoadedCode.empty()),
                new VersionContextService(stores.alignment(), AlignmentTestSupport.stubLoader(LoadedCode.empty())));
        service.buildProject("immortal");
    }

    private EntityMention mentionFor(String alias) {
        String conceptId = stores.alignment().findConceptIdsByAlias("immortal", alias).get(0);
        return new EntityMention(alias, conceptId, alias, MatchMethod.CONFIRMED_ALIAS, 1.0, MentionStatus.RESOLVED);
    }

    private EntityQueryPlan plan(EntityMention mention, List<String> versions) {
        return new EntityQueryPlan("immortal", "等级上限是多少", List.of(mention),
                QueryIntent.NUMERIC_VALUE, versions, false, false, false, true);
    }

    private EntityView aggregate(EntityMention mention, List<String> versions) {
        return new EntityEvidenceAggregator(stores.multiSource(), stores.alignment(),
                AlignmentTestSupport.stubLoader(LoadedCode.empty()))
                .aggregate("immortal", plan(mention, versions), List.of(mention),
                        new Options(true, true, true))
                .get(0);
    }

    @Test
    void aggregatesCrossVersionTimelineAndCurrentFacts() {
        newStores();
        AlignmentTestSupport.seedParameter(stores, "5.0", "LevelCap", "100", "combat");
        AlignmentTestSupport.seedParameter(stores, "5.1", "LevelCap", "120", "combat");
        buildConcepts();

        EntityView view = aggregate(mentionFor("LevelCap"), List.of());

        assertThat(view.timeline()).extracting(VersionFactBlock::businessVersion)
                .containsExactlyInAnyOrder("5.0", "5.1");
        assertThat(view.currentFacts().parameterTables())
                .extracting(f -> f.objectValue()).contains("120");
        assertThat(view.currentFacts().code()).isEmpty();
        assertThat(view.warnings()).contains("CODE_CONTEXT_UNAVAILABLE");
    }

    @Test
    void timelineFilteredByRequestedVersions() {
        newStores();
        AlignmentTestSupport.seedParameter(stores, "5.0", "LevelCap", "100", "combat");
        AlignmentTestSupport.seedParameter(stores, "5.1", "LevelCap", "120", "combat");
        buildConcepts();

        EntityView view = aggregate(mentionFor("LevelCap"), List.of("5.0"));

        assertThat(view.timeline()).extracting(VersionFactBlock::businessVersion)
                .containsExactly("5.0");
    }

    @Test
    void includeHistoryFalseKeepsOnlyLatestVersionBlock() {
        newStores();
        AlignmentTestSupport.seedParameter(stores, "5.0", "LevelCap", "100", "combat");
        AlignmentTestSupport.seedParameter(stores, "5.1", "LevelCap", "120", "combat");
        buildConcepts();
        EntityMention mention = mentionFor("LevelCap");

        List<EntityView> views = new EntityEvidenceAggregator(stores.multiSource(), stores.alignment(),
                AlignmentTestSupport.stubLoader(LoadedCode.empty()))
                .aggregate("immortal", plan(mention, List.of()), List.of(mention),
                        new Options(20, true, true, true, false));

        // 只保留最新已发布版本块（当前版本，非“历史版本”），当前事实仍可用
        assertThat(views.get(0).timeline()).extracting(VersionFactBlock::businessVersion)
                .containsExactly("5.1");
        assertThat(views.get(0).currentFacts().parameterTables()).isNotEmpty();
    }

    @Test
    void codeFactsCarryFileLocationAndCommit() {
        newStores();
        AlignmentTestSupport.seedParameter(stores, "5.1", "Fireball_CD", "12", "combat");
        List<CodeSymbolView> symbols = List.of(
                AlignmentTestSupport.symbol("s-1", "method", "com.game.skill.resolveFireballCd",
                        "Fireball_CD", "FireballSkill.java", 10, 30, false));
        LoadedCode loaded = AlignmentTestSupport.loadedCode(symbols);
        BusinessConceptService service = new BusinessConceptService(
                stores.multiSource(), stores.alignment(), AlignmentTestSupport.stubLoader(loaded),
                new VersionContextService(stores.alignment(), AlignmentTestSupport.stubLoader(loaded)));
        service.buildProject("immortal");

        EntityView view = new EntityEvidenceAggregator(stores.multiSource(), stores.alignment(),
                AlignmentTestSupport.stubLoader(loaded))
                .aggregate("immortal", plan(mentionFor("Fireball_CD"), List.of()),
                        List.of(mentionFor("Fireball_CD")), new Options(true, true, true))
                .get(0);

        assertThat(view.currentFacts().code()).isNotEmpty();
        assertThat(view.currentFacts().code().get(0).location())
                .contains("FireballSkill.java")
                .contains("10-30");
    }

    @Test
    void queryFiltersClaimsOfDemotedDocumentVersions() {
        // Fix 1：发布新文档版本后旧版本被降为 DRAFT（发布流程不触发 buildProject），
        // 查询时按当前 PUBLISHED 文档版本重校验，旧成员 Claim 不再泄漏
        newStores();
        AlignmentTestSupport.seedParameter(stores, "5.1", "LevelCap", "100", "combat");
        buildConcepts();
        // 先把旧文档登记为 active（发布流程的 manifest 语义），随后发布新文档触发降级
        stores.multiSource().publishDocumentVersion("immortal", "5.1", "dv-immortal-5.1");
        String newDv = "dv-immortal-5.1-new";
        stores.multiSource().upsertDocumentVersion(new KnowledgeDocumentVersion(
                newDv, "doc-immortal", "immortal", "5.1", "hash-new", "v1", "v1", null, "PUBLISHED", null, null));
        stores.multiSource().saveClaim(new KnowledgeClaimRecord(
                "p-new", "immortal", newDv, SourceType.PARAMETER_TABLE, Authority.PRIMARY,
                "immortal|5.1|combat|levelcap|value", "LevelCap", "value", "999", "INTEGER", "级",
                "SUPPORTED", null, null, null, "RULE", null, null, null));
        stores.multiSource().publishDocumentVersion("immortal", "5.1", newDv);

        // 旧成员指向的 dv-immortal-5.1 已被降级为 DRAFT → 查询过滤其 Claim
        EntityView view = aggregate(mentionFor("LevelCap"), List.of());
        assertThat(view.currentFacts().parameterTables()).isEmpty();
        assertThat(view.timeline()).isEmpty();
    }

    @Test
    void timelineFactRefsCarryBusinessVersion() {
        // Fix 4：历史时间轴 FactRef 必须带版本（供引用回源与跨源关系查询）
        newStores();
        AlignmentTestSupport.seedParameter(stores, "5.0", "LevelCap", "100", "combat");
        AlignmentTestSupport.seedParameter(stores, "5.1", "LevelCap", "120", "combat");
        buildConcepts();

        EntityView view = aggregate(mentionFor("LevelCap"), List.of());

        VersionFactBlock block50 = view.timeline().stream()
                .filter(b -> "5.0".equals(b.businessVersion())).findFirst().orElseThrow();
        assertThat(block50.parameterTables())
                .extracting(f -> f.businessVersion()).containsExactly("5.0");
    }

    @Test
    void sameFactKeyDifferentValuesMarkedConflicted() {
        newStores();
        ParameterClaim p1 = new ParameterClaim(
                "p-1", "immortal", "5.1", "skills.xlsx", "技能参数", 1, "A1", "combat",
                "LevelCap", "100", "100", "级", null, null, 0, true,
                ParameterValueType.INTEGER, "immortal|5.1|combat|levelcap|value",
                "skills.xlsx#A1", KnowledgeStatus.SUPPORTED);
        ParameterClaim p2 = new ParameterClaim(
                "p-2", "immortal", "5.1", "skills.xlsx", "技能参数", 2, "A2", "combat",
                "LevelCap", "120", "120", "级", null, null, 0, true,
                ParameterValueType.INTEGER, "immortal|5.1|combat|levelcap|value",
                "skills.xlsx#A2", KnowledgeStatus.SUPPORTED);
        AlignmentTestSupport.seed(stores, "immortal", "5.1", List.of(p1, p2),
                List.of(), List.of(), List.of(), List.of());
        buildConcepts();

        EntityView view = aggregate(mentionFor("LevelCap"), List.of());

        assertThat(view.conflicts()).isNotEmpty();
        ConflictView conflict = view.conflicts().get(0);
        assertThat(conflict.values()).contains("100", "120");
        assertThat(conflict.status()).isEqualTo("CONFLICTED");
    }

    @Test
    void requirementAndTestMembersAppearInTimelineBlocks() {
        newStores();
        AlignmentTestSupport.seedParameter(stores, "5.1", "LevelCap", "120", "combat");
        TestCaseClaim testCase = new TestCaseClaim(
                "tc-1", "immortal", "5.1", "TC-001", "LevelCap", "combat",
                "", "", "", "REQ-001", "JUnit", "LevelTest.java", "levelCapShouldBe120",
                "LevelTest.java#level", KnowledgeStatus.SUPPORTED);
        AlignmentTestSupport.seed(stores, "immortal", "5.1", List.of(), List.of(),
                List.of(testCase), List.of(), List.of());
        KnowledgeClaimRecord requirement = new KnowledgeClaimRecord(
                "req-1", "immortal", "dv-immortal-5.1",
                com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType.REQUIREMENT,
                com.example.requirementrag.conflict.KnowledgeConflictModels.Authority.PRIMARY,
                "immortal|5.1|combat|levelcap|rule", "LevelCap", "rule", "120 级", "TEXT", null,
                "VERIFIED", null, null, null, "RULE", null, null, null);
        stores.multiSource().saveClaim(requirement);
        buildConcepts();

        EntityView view = aggregate(mentionFor("LevelCap"), List.of());

        VersionFactBlock block = view.timeline().get(0);
        assertThat(block.requirements()).extracting(f -> f.claimId()).contains("req-1");
        assertThat(block.tests()).extracting(f -> f.claimId()).contains("tc-1");
        assertThat(block.parameterTables()).isNotEmpty();
    }
}