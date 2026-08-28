package com.example.requirementrag.knowledge.multisource.entity;

import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.AssessmentItem;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.CurrentFacts;
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