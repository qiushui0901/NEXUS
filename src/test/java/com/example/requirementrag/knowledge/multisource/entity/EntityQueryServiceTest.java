package com.example.requirementrag.knowledge.multisource.entity;

import com.example.requirementrag.knowledge.multisource.alignment.AlignmentTestSupport;
import com.example.requirementrag.knowledge.multisource.alignment.BusinessConceptService;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.LoadedCode;
import com.example.requirementrag.knowledge.multisource.alignment.VersionContextService;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.EntitySearchResponse;
import com.example.requirementrag.knowledge.multisource.entity.EntityQueryService.EntitySearchRequest;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.MentionStatus;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.QueryIntent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EntityQueryServiceTest {
    @TempDir Path tempDir;

    private EntityQueryService service(AlignmentTestSupport.Stores stores) {
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

    @Test
    void endToEndSearchWithoutLlmReturnsEntityEvidence() {
        AlignmentTestSupport.Stores stores = AlignmentTestSupport.stores(tempDir);
        AlignmentTestSupport.seedParameter(stores, "5.0", "攻击力", "100", "combat");
        AlignmentTestSupport.seedParameter(stores, "5.1", "攻击力", "150", "combat");
        BusinessConceptService builder = new BusinessConceptService(
                stores.multiSource(), stores.alignment(), AlignmentTestSupport.stubLoader(LoadedCode.empty()),
                new VersionContextService(stores.alignment(), AlignmentTestSupport.stubLoader(LoadedCode.empty())));
        builder.buildProject("immortal");

        EntitySearchResponse response = service(stores).search(new EntitySearchRequest(
                "immortal", "角色达到 100 级时攻击力是多少？", null, null,
                true, true, true, 20));

        assertThat(response.plan().mentions()).isNotEmpty();
        assertThat(response.plan().mentions().get(0).status()).isEqualTo(MentionStatus.RESOLVED);
        assertThat(response.plan().intent()).isEqualTo(QueryIntent.NUMERIC_VALUE);
        assertThat(response.entities()).hasSize(1);
        assertThat(response.entities().get(0).currentFacts().parameterTables()).isNotEmpty();
        assertThat(response.entities().get(0).timeline()).hasSize(2);
        assertThat(response.citations()).isNotEmpty();
        // Phase 4：事实评估已填充（当前数值分区非空），不再是无内容骨架
        assertThat(response.factAssessment().currentValues()).isNotEmpty();
        assertThat(response.factAssessment()).isNotEqualTo(EntityEvidenceModels.FactAssessment.EMPTY);
    }

    @Test
    void unresolvedQueryReturnsEmptyEntitiesWithWarning() {
        AlignmentTestSupport.Stores stores = AlignmentTestSupport.stores(tempDir);
        AlignmentTestSupport.seedParameter(stores, "5.1", "攻击力", "100", "combat");
        BusinessConceptService builder = new BusinessConceptService(
                stores.multiSource(), stores.alignment(), AlignmentTestSupport.stubLoader(LoadedCode.empty()),
                new VersionContextService(stores.alignment(), AlignmentTestSupport.stubLoader(LoadedCode.empty())));
        builder.buildProject("immortal");

        EntitySearchResponse response = service(stores).search(new EntitySearchRequest(
                "immortal", "完全不存在的东西是什么", null, null,
                true, true, true, 20));

        assertThat(response.entities()).isEmpty();
        assertThat(response.warnings()).contains("ENTITY_UNRESOLVED");
        assertThat(response.citations()).isEmpty();
    }
}