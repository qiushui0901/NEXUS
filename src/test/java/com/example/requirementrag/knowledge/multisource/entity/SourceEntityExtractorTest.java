package com.example.requirementrag.knowledge.multisource.entity;

import com.example.requirementrag.knowledge.multisource.alignment.AlignmentTestSupport;
import com.example.requirementrag.knowledge.multisource.alignment.BusinessConceptService;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.ConceptAlias;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.LoadedCode;
import com.example.requirementrag.knowledge.multisource.alignment.VersionContextService;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.SourceEntityRaw;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.SourceExtractionRaw;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.SourceFactRaw;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.SourceRelationRaw;
import com.example.requirementrag.knowledge.multisource.entity.SourceEntityExtractor.ExtractionOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SourceEntityExtractorTest {
    @TempDir Path tempDir;

    private SourceEntityExtractor extractor(AlignmentTestSupport.Stores stores,
                                            EntityExtractionProperties properties,
                                            ChatClient chatClient) {
        EntityExtractionValidator validator = new EntityExtractionValidator(properties);
        EntityLlmAssistant llm = new EntityLlmAssistant(chatClient, null, properties, validator);
        return new SourceEntityExtractor(stores.multiSource(), stores.alignment(), properties, llm);
    }

    private EntityExtractionProperties props(boolean allowLlm) {
        return new EntityExtractionProperties(true, "test-model", 8, 50_000, 200, 50, 100, 100, 0.7, allowLlm, 1);
    }

    private AlignmentTestSupport.Stores seededWithConcept(String subject) {
        AlignmentTestSupport.Stores stores = AlignmentTestSupport.stores(tempDir);
        AlignmentTestSupport.seedParameter(stores, "5.1", subject, "12", "combat");
        BusinessConceptService service = new BusinessConceptService(
                stores.multiSource(), stores.alignment(), AlignmentTestSupport.stubLoader(LoadedCode.empty()),
                new VersionContextService(stores.alignment(), AlignmentTestSupport.stubLoader(LoadedCode.empty())));
        service.build("immortal", "5.1");
        return stores;
    }

    private ChatClient mockLlm(ChatClient.CallResponseSpec callSpec) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.options(any())).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        return chatClient;
    }

    @Test
    void proposesAliasesAsProposedStatusWithoutAffectingConfirmedMatch() {
        AlignmentTestSupport.Stores stores = seededWithConcept("Fireball_CD");
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(callSpec.entity(SourceExtractionRaw.class)).thenReturn(new SourceExtractionRaw(
                List.of(new SourceEntityRaw("Fireball_CD", List.of("火球冷却", "fireballCd"), "PARAMETER", "desc", 0.9)),
                List.of(new SourceFactRaw("Fireball_CD", "max", "12", "秒", "param-combat-Fireball_CD-5.1", 0.8)),
                List.of(new SourceRelationRaw("Fireball_CD", "RoleLevelValidator", "IMPLEMENTED_BY", 0.86))));

        ExtractionOutcome outcome = extractor(stores, props(true), mockLlm(callSpec))
                .extract("immortal", "5.1");

        assertThat(outcome.proposedAliases()).isEqualTo(2);
        assertThat(outcome.warnings()).contains("RELATION_UNMAPPED:Fireball_CD->RoleLevelValidator");
        // PROPOSED 别名不参与确认匹配
        String conceptId = stores.alignment().findConceptIdsByAlias("immortal", "Fireball_CD").get(0);
        assertThat(stores.alignment().findAliases("immortal", conceptId))
                .filteredOn(a -> "PROPOSED".equals(a.status()))
                .extracting(ConceptAlias::alias).contains("火球冷却", "fireballCd");
        assertThat(stores.alignment().findConfirmedAliasesMentionedIn("immortal", "火球冷却", 100))
                .isEmpty();
    }

    @Test
    void rejectsFactReferencingClaimOutsideInputBatch() {
        AlignmentTestSupport.Stores stores = seededWithConcept("Fireball_CD");
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(callSpec.entity(SourceExtractionRaw.class)).thenReturn(new SourceExtractionRaw(
                List.of(),
                List.of(new SourceFactRaw("Fireball_CD", "max", "12", null, "nonexistent-claim", 0.8)),
                List.of()));

        ExtractionOutcome outcome = extractor(stores, props(true), mockLlm(callSpec))
                .extract("immortal", "5.1");

        assertThat(outcome.proposedAliases()).isZero();
        assertThat(outcome.warnings()).contains("ENTITY_EXTRACTION_REJECTED");
    }

    @Test
    void rejectsRelationTypeOutsideWhitelist() {
        AlignmentTestSupport.Stores stores = seededWithConcept("Fireball_CD");
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(callSpec.entity(SourceExtractionRaw.class)).thenReturn(new SourceExtractionRaw(
                List.of(),
                List.of(),
                List.of(new SourceRelationRaw("Fireball_CD", "RoleLevelValidator", "RANDOM_LINK", 0.9))));

        ExtractionOutcome outcome = extractor(stores, props(true), mockLlm(callSpec))
                .extract("immortal", "5.1");

        assertThat(outcome.warnings()).contains("ENTITY_EXTRACTION_REJECTED");
    }

    @Test
    void degradesGracefullyWhenLlmFails() {
        AlignmentTestSupport.Stores stores = seededWithConcept("Fireball_CD");
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.options(any())).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.entity(SourceExtractionRaw.class))
                .thenThrow(new RuntimeException("connection refused"));

        ExtractionOutcome outcome = extractor(stores, props(true), chatClient)
                .extract("immortal", "5.1");

        assertThat(outcome.proposedAliases()).isZero();
        assertThat(outcome.proposedRelations()).isZero();
        assertThat(outcome.warnings()).contains("ENTITY_EXTRACTION_REJECTED");
    }

    @Test
    void returnsUnavailableWarningWhenLlmDisabled() {
        AlignmentTestSupport.Stores stores = seededWithConcept("Fireball_CD");
        ExtractionOutcome outcome = extractor(stores, props(false), null)
                .extract("immortal", "5.1");

        assertThat(outcome.proposedAliases()).isZero();
        assertThat(outcome.warnings()).contains("ENTITY_LLM_UNAVAILABLE");
    }

    @Test
    void relationEndpointsAreScopedToCurrentVersion() {
        // Fix 7：关系端点必须属于当前输入版本（5.1），不得取 5.0 的成员 Claim
        AlignmentTestSupport.Stores stores = AlignmentTestSupport.stores(tempDir);
        AlignmentTestSupport.seedParameter(stores, "5.0", "Attack", "100", "combat");
        AlignmentTestSupport.seedParameter(stores, "5.1", "Attack", "110", "combat");
        AlignmentTestSupport.seedParameter(stores, "5.1", "Defense", "50", "combat");
        BusinessConceptService builder = new BusinessConceptService(
                stores.multiSource(), stores.alignment(), AlignmentTestSupport.stubLoader(LoadedCode.empty()),
                new VersionContextService(stores.alignment(), AlignmentTestSupport.stubLoader(LoadedCode.empty())));
        builder.buildProject("immortal");

        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(callSpec.entity(SourceExtractionRaw.class)).thenReturn(new SourceExtractionRaw(
                List.of(), List.of(),
                List.of(new SourceRelationRaw("Attack", "Defense", "IMPLEMENTED_BY", 0.9))));

        ExtractionOutcome outcome = extractor(stores, props(true), mockLlm(callSpec))
                .extract("immortal", "5.1");

        assertThat(outcome.proposedRelations()).isEqualTo(1);
        // 端点必须是 5.1 的 Attack Claim（不是 5.0 的）
        String attackClaim51 = stores.alignment().findMembers("immortal",
                stores.alignment().findConceptIdsByAlias("immortal", "Attack").get(0), "5.1")
                .get(0).claimId();
        String defenseClaim51 = stores.alignment().findMembers("immortal",
                stores.alignment().findConceptIdsByAlias("immortal", "Defense").get(0), "5.1")
                .get(0).claimId();
        assertThat(stores.alignment().findAlignmentRelationsForClaim("immortal", attackClaim51))
                .anyMatch(r -> defenseClaim51.equals(r.targetClaimId()));
        String attackClaim50 = stores.alignment().findMembers("immortal",
                stores.alignment().findConceptIdsByAlias("immortal", "Attack").get(0), "5.0")
                .get(0).claimId();
        assertThat(stores.alignment().findAlignmentRelationsForClaim("immortal", attackClaim50)).isEmpty();
    }
}