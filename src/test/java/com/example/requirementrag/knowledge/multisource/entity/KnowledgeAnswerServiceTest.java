package com.example.requirementrag.knowledge.multisource.entity;

import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.AssessmentItem;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.CurrentFacts;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.EntityRecallResponse;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.EntitySearchResponse;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.EntityView;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.FactAssessment;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.FactRef;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.EntityMention;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.EntityQueryPlan;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.MatchMethod;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.MentionStatus;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.QueryIntent;
import com.example.requirementrag.knowledge.multisource.entity.KnowledgeAnswerService.AnswerOutcome;
import com.example.requirementrag.knowledge.multisource.entity.KnowledgeAnswerService.AnswerSection;
import com.example.requirementrag.knowledge.multisource.entity.KnowledgeAnswerService.AgentAnswerRaw;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeAnswerServiceTest {

    private EntityExtractionProperties props() {
        return new EntityExtractionProperties(true, "test-model", 8, 50_000, 200, 50, 100, 100, 0.7, true, 1);
    }

    private KnowledgeAnswerService llmAnswer(SectionRaw raw) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.options(any())).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(callSpec.entity(AgentAnswerRaw.class)).thenReturn(raw.raw());
        return new KnowledgeAnswerService(chatClient, null, props());
    }

    /** 造一个带偏差的实体证据响应：需求 120 / 参数 100 + 失败测试。 */
    private EntitySearchResponse gapEvidence() {
        EntityMention mention = new EntityMention("LevelCap", "con:1", "LevelCap",
                MatchMethod.CONFIRMED_ALIAS, 1.0, MentionStatus.RESOLVED);
        EntityQueryPlan plan = new EntityQueryPlan("immortal", "等级上限多少", List.of(mention),
                QueryIntent.NUMERIC_VALUE, List.of(), false, true, true, true);
        EntityView view = new EntityView("con:1", "LevelCap", List.of("LevelCap"),
                new CurrentFacts(
                        List.of(new FactRef(null, "s-1", "CODE", "LevelValidator", null, null,
                                "5.1", List.of("code:e1"), "immortal-game-service@abc123:LevelValidator.java:10-30")),
                        List.of(new FactRef("c-param", null, "PARAMETER_TABLE", "LevelCap", "100", "级",
                                "5.1", List.of("ev-table"), "skills.xlsx#Sheet1!2")),
                        List.of(new FactRef("c-test", null, "TEST_RESULT", "TC-001", "FAILED", null,
                                "5.1", List.of("ev-test"), null))),
                List.of(), List.of(), List.of(), List.of());
        FactAssessment assessment = new FactAssessment(
                List.of(new AssessmentItem("CURRENT_BEHAVIOR", "LevelValidator", "CODE", "SUPPORTED")),
                List.of(new AssessmentItem("CURRENT_VALUE", "LevelCap=100级", "PARAMETER_TABLE", "SUPPORTED")),
                List.of(new AssessmentItem("VALIDATION", "TC-001=FAILED", "TEST_RESULT", "REVIEW_REQUIRED")),
                List.of(new AssessmentItem("REQUIREMENT_TARGET", "LevelCap=120级", "REQUIREMENT", "SUPPORTED")),
                List.of(new AssessmentItem("REQUIREMENT_PARAMETER_MISMATCH", "需求目标=120，当前数值表=100",
                        "REQUIREMENT/PARAMETER_TABLE", "CONFLICTED")));
        return new EntitySearchResponse("等级上限多少", plan, List.of(view), assessment,
                List.of(new EntityEvidenceModels.Citation("c-param", "PARAMETER_TABLE", "5.1", "ev-table"),
                        new EntityEvidenceModels.Citation("c-test", "TEST_RESULT", "5.1", "ev-test")),
                List.of());
    }

    @Test
    void llmAnswerKeepsOnlyValidEvidenceReferences() {
        KnowledgeAnswerService service = llmAnswer(new SectionRaw(
                new AgentAnswerRaw("当前系统存在实现偏差。",
                        List.of(new AnswerSection("当前数值", "数值表 100。", "PARAMETER_TABLE",
                                        List.of("ev-table", "伪造引用"))))));

        AnswerOutcome outcome = service.answer(gapEvidence());

        assertThat(outcome.answer()).contains("REQUIREMENT_PARAMETER_MISMATCH");
        assertThat(outcome.answer()).doesNotContain("当前系统存在实现偏差。");
        assertThat(outcome.citationQuality()).isEqualTo("UNVERIFIED");
        assertThat(outcome.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(outcome.sections()).isEmpty();
    }

    @Test
    void evidenceTypeMismatchIsDroppedAndDowngradesQuality() {
        // 分节声明 CODE（代码行为结论），却引用参数表证据 ev-table → 类型错配引用被丢弃
        KnowledgeAnswerService service = llmAnswer(new SectionRaw(
                new AgentAnswerRaw("代码可能已实现 120。",
                        List.of(new AnswerSection("当前数值", "数值表 100。", "PARAMETER_TABLE",
                                        List.of("ev-table")),
                                new AnswerSection("代码行为", "代码可能实现 120。", "CODE",
                                        List.of("ev-table"))))));

        AnswerOutcome outcome = service.answer(gapEvidence());

        assertThat(outcome.citationQuality()).isEqualTo("UNVERIFIED");
        assertThat(outcome.sections()).isEmpty(); // 任一分节不可信时不保留其它模型文本
    }

    @Test
    void llmAnswerWithAllValidRefsIsVerified() {
        KnowledgeAnswerService service = llmAnswer(new SectionRaw(
                new AgentAnswerRaw("配置为 100 级，代码存在实现。",
                        List.of(new AnswerSection("当前数值", "数值表 100。", "PARAMETER_TABLE",
                                        List.of("ev-table")),
                                new AnswerSection("结论", "存在偏差需核对。", "TEST_RESULT",
                                        List.of("ev-test"))))));

        AnswerOutcome outcome = service.answer(gapEvidence());

        assertThat(outcome.citationQuality()).isEqualTo("VERIFIED");
    }

    @Test
    void mixedSectionCannotBypassTypeValidation() {
        // Fix 6：MIXED 不再合法——分节必须声明单一来源类型；MIXED + 参数表证据不能被接受
        KnowledgeAnswerService service = llmAnswer(new SectionRaw(
                new AgentAnswerRaw("当前代码支持到 999。",
                        List.of(new AnswerSection("代码行为", "当前代码支持到 999。", "MIXED",
                                List.of("ev-table"))))));

        AnswerOutcome outcome = service.answer(gapEvidence());

        // 引用全部不可信 → 回退确定性模板，不保留模型编造内容
        assertThat(outcome.llmUsed()).isFalse();
        assertThat(outcome.answer()).doesNotContain("999");
        assertThat(outcome.citationQuality()).isEqualTo("UNVERIFIED");
    }

    @Test
    void answerWithRecallIncludesRecallContextAndTypedValidation() {
        // 图/向量增强回答：确定性证据 + 相关图/向量上下文；分节类型校验仍生效
        EntitySearchResponse evidence = gapEvidence();
        EntityRecallResponse recall = new EntityRecallResponse("等级上限", evidence.plan(),
                evidence.entities(), evidence.factAssessment(), evidence.citations(), List.of(),
                "GRAPH_VECTOR", evidence,
                new com.example.requirementrag.knowledge.multisource.entity.EntityGraphExpansionService.RelatedGraph(
                        List.of(), List.of(new com.example.requirementrag.knowledge.multisource.entity.EntityGraphExpansionService.RelatedLink(
                                "rel-1", "c-param", "c-test", "RELATED_TO", "RULE_CONFIRMED", "CONFIRMED")), 1),
                List.of(new EntityEvidenceModels.VectorHit("c-test", "TC-001", "TEST_RESULT")), 1);
        KnowledgeAnswerService service = new KnowledgeAnswerService(null, null, props());

        AnswerOutcome outcome = service.answerWithRecall(recall);

        assertThat(outcome.llmUsed()).isFalse();
        assertThat(outcome.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(outcome.answer()).contains("REQUIREMENT_PARAMETER_MISMATCH");
    }

    @Test
    void claimIdCannotImpersonateEvidenceInRecallAnswer() {
        // High 1：图/向量增强回答中，模型引用 Claim ID（c-param）不得被当作合法 Evidence——
        // 允许集只注册真实 Evidence ID（citations / currentFacts 的 evidenceIds）
        EntitySearchResponse evidence = gapEvidence();
        EntityRecallResponse recall = new EntityRecallResponse("等级上限", evidence.plan(),
                evidence.entities(), evidence.factAssessment(), evidence.citations(), List.of(),
                "GRAPH_VECTOR", evidence,
                new com.example.requirementrag.knowledge.multisource.entity.EntityGraphExpansionService.RelatedGraph(
                        List.of(), List.of(), 0),
                List.of(), 0);
        KnowledgeAnswerService service = llmAnswer(new SectionRaw(
                new AgentAnswerRaw("数值表 100。",
                        List.of(new AnswerSection("当前数值", "数值表 100。", "PARAMETER_TABLE",
                                List.of("c-param"))))));  // c-param 是 Claim ID，不是 Evidence ID

        AnswerOutcome outcome = service.answerWithRecall(recall);

        // 引用不可信 → 回退模板，模型文本不保留
        assertThat(outcome.llmUsed()).isFalse();
        assertThat(outcome.citationQuality()).isEqualTo("UNVERIFIED");
        assertThat(outcome.answer()).doesNotContain("数值表 100。");
    }

    @Test
    void expandedEntityFactsAndEvidenceAreReferenceableInRecall() {
        // High 3 正向：合并实体集（含扩展实体 Speed）的事实与真实 Evidence 必须进入包，可被引用 → VERIFIED
        EntitySearchResponse evidence = gapEvidence();
        EntityView speedView = new EntityView("con:2", "Speed", List.of("Speed"),
                new CurrentFacts(List.of(),
                        List.of(new FactRef("c-speed", null, "PARAMETER_TABLE", "Speed", "300", "",
                                "5.1", List.of("ev-speed"), "xlsx#Sheet1!3")),
                        List.of()),
                List.of(), List.of(), List.of(), List.of());
        EntitySearchResponse merged = new EntitySearchResponse(evidence.query(), evidence.plan(),
                List.of(evidence.entities().get(0), speedView), evidence.factAssessment(),
                List.of(new EntityEvidenceModels.Citation("c-speed", "PARAMETER_TABLE", "5.1", "ev-speed")),
                evidence.warnings());
        EntityRecallResponse recall = new EntityRecallResponse("等级上限", merged.plan(), merged.entities(),
                merged.factAssessment(), merged.citations(), merged.warnings(), "GRAPH_VECTOR", merged,
                new com.example.requirementrag.knowledge.multisource.entity.EntityGraphExpansionService.RelatedGraph(
                        List.of(), List.of(), 0),
                List.of(), 1);
        KnowledgeAnswerService service = llmAnswer(new SectionRaw(
                new AgentAnswerRaw("Speed 配置 300。",
                        List.of(new AnswerSection("当前数值", "Speed 300。", "PARAMETER_TABLE",
                                List.of("ev-speed"))))));

        AnswerOutcome outcome = service.answerWithRecall(recall);

        assertThat(outcome.llmUsed()).isTrue();
        assertThat(outcome.citationQuality()).isEqualTo("VERIFIED");
    }

    @Test
    void secondEvidenceIdOfSameClaimIsReferenceable() {
        // Med：同 Claim 的第二及后续 Evidence ID 必须可引用——citations 只登记首条，
        // 但证据注册表从全部输出事实的 evidenceIds 建立
        EntitySearchResponse evidence = gapEvidence();
        EntityView view = evidence.entities().get(0);
        // 参数事实带两个 Evidence：ev-table（citations 首条）+ ev-table-backup（第二条）
        EntityView doubled = new EntityView(view.entityId(), view.canonicalName(), view.aliases(),
                new CurrentFacts(
                        view.currentFacts().code(),
                        List.of(new FactRef("c-param", null, "PARAMETER_TABLE", "LevelCap", "100", "级",
                                "5.1", List.of("ev-table", "ev-table-backup"), "skills.xlsx#Sheet1!2")),
                        view.currentFacts().testResults()),
                view.timeline(), view.relations(), view.conflicts(), view.warnings());
        EntitySearchResponse merged = new EntitySearchResponse(evidence.query(), evidence.plan(),
                List.of(doubled), evidence.factAssessment(), evidence.citations(), evidence.warnings());
        EntityRecallResponse recall = new EntityRecallResponse("等级上限", merged.plan(), merged.entities(),
                merged.factAssessment(), merged.citations(), merged.warnings(), "GRAPH_VECTOR", merged,
                new com.example.requirementrag.knowledge.multisource.entity.EntityGraphExpansionService.RelatedGraph(
                        List.of(), List.of(), 0),
                List.of(), 0);
        KnowledgeAnswerService service = llmAnswer(new SectionRaw(
                new AgentAnswerRaw("配置 100 级。",
                        List.of(new AnswerSection("当前数值", "数值表 100。", "PARAMETER_TABLE",
                                List.of("ev-table-backup"))))));  // 第二条 Evidence，citations 里没有

        AnswerOutcome outcome = service.answerWithRecall(recall);

        assertThat(outcome.llmUsed()).isTrue();
        assertThat(outcome.citationQuality()).isEqualTo("VERIFIED");
    }

    @Test
    void templateFallbackReportsDeviationWhenLlmUnavailable() {
        KnowledgeAnswerService service = new KnowledgeAnswerService(null, null, props());

        AnswerOutcome outcome = service.answer(gapEvidence());

        assertThat(outcome.llmUsed()).isFalse();
        assertThat(outcome.status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(outcome.answer()).contains("REQUIREMENT_PARAMETER_MISMATCH");
        assertThat(outcome.citationQuality()).isEqualTo("UNVERIFIED");
    }

    @Test
    void templateFallbackSaysUndeterminedWhenNoEvidence() {
        KnowledgeAnswerService service = new KnowledgeAnswerService(null, null, props());
        EntityMention mention = new EntityMention("不存在", null, "不存在",
                MatchMethod.UNRESOLVED, 0.5, MentionStatus.CANDIDATE);
        EntityQueryPlan plan = new EntityQueryPlan("immortal", "不存在的东西", List.of(mention),
                QueryIntent.GENERAL, List.of(), true, false, false, false);
        EntitySearchResponse empty = new EntitySearchResponse("不存在的东西", plan, List.of(),
                FactAssessment.EMPTY, List.of(), List.of());

        AnswerOutcome outcome = service.answer(empty);

        assertThat(outcome.answer()).contains("无法确定");
    }

    private record SectionRaw(AgentAnswerRaw raw) {
    }
}