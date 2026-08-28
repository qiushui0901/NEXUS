package com.example.requirementrag.knowledge.multisource.entity;

import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.AssessmentItem;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.CurrentFacts;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.EntityView;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.FactAssessment;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.FactRef;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.VersionFactBlock;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.EntityQueryPlan;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.QueryIntent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EntityFactPriorityServiceTest {

    private final EntityFactPriorityService service = new EntityFactPriorityService();

    private EntityQueryPlan plan(boolean numeric, boolean implementation) {
        return new EntityQueryPlan("immortal", "等级上限多少？现在代码支持到多少？",
                List.of(), QueryIntent.CURRENT_STATE, List.of(), false,
                true, implementation, numeric);
    }

    private FactRef ref(String sourceType, String subject, String value, String unit) {
        return new FactRef("c-" + Math.abs(subject.hashCode()), null, sourceType,
                subject, value, unit, "5.1", List.of(), null);
    }

    private EntityView view(CurrentFacts current, List<VersionFactBlock> timeline) {
        return new EntityView("con:1", "LevelCap", List.of("LevelCap"), current,
                timeline, List.of(), List.of(), List.of());
    }

    @Test
    void currentBehaviorPrefersCodeOverParameterTable() {
        EntityView view = view(
                new CurrentFacts(
                        List.of(ref("CODE", "LevelValidator", null, null)),
                        List.of(ref("PARAMETER_TABLE", "LevelCap", "120", "级")),
                        List.of()),
                List.of());

        FactAssessment assessment = service.assess(plan(true, false), view);

        assertThat(assessment.currentBehavior()).hasSize(1);
        assertThat(assessment.currentBehavior().get(0).sourceType()).isEqualTo("CODE");
        assertThat(assessment.currentValues()).extracting(AssessmentItem::value).contains("LevelCap=120级");
    }

    @Test
    void requirementParameterMismatchIsConflicted() {
        EntityView view = view(
                new CurrentFacts(
                        List.of(),
                        List.of(ref("PARAMETER_TABLE", "LevelCap", "100", "级")),
                        List.of()),
                List.of(new VersionFactBlock("5.1",
                        List.of(ref("REQUIREMENT", "LevelCap", "120", "级")),
                        List.of(), List.of())));

        FactAssessment assessment = service.assess(plan(true, false), view);

        assertThat(assessment.implementationGaps())
                .anyMatch(item -> item.type().equals("REQUIREMENT_PARAMETER_MISMATCH")
                        && item.status().equals("CONFLICTED"));
    }

    @Test
    void failedTestRaisesImplementationGapAndCodeParameterMismatchWhenCodePresent() {
        EntityView view = view(
                new CurrentFacts(
                        List.of(ref("CODE", "LevelValidator", null, null)),
                        List.of(ref("PARAMETER_TABLE", "LevelCap", "120", "级")),
                        List.of(ref("TEST_RESULT", "TC-001", "FAILED", null))),
                List.of());

        FactAssessment assessment = service.assess(plan(true, true), view);

        assertThat(assessment.implementationGaps())
                .anyMatch(item -> item.type().equals("REQUIREMENT_IMPLEMENTATION_GAP")
                        && item.status().equals("REVIEW_REQUIRED"));
        assertThat(assessment.implementationGaps())
                .anyMatch(item -> item.type().equals("CODE_PARAMETER_MISMATCH")
                        && item.status().equals("REVIEW_REQUIRED"));
        assertThat(assessment.validation()).extracting(AssessmentItem::status)
                .contains("REVIEW_REQUIRED");
    }

    @Test
    void requirementParameterMismatchUsesLatestVersionRequirement() {
        // 5.0 需求=100（与参数一致），5.1 需求=120（与参数 100 不一致）→ 必须对最新版本判 mismatch
        EntityView view = view(
                new CurrentFacts(
                        List.of(),
                        List.of(ref("PARAMETER_TABLE", "LevelCap", "100", "级")),
                        List.of()),
                List.of(new VersionFactBlock("5.0",
                                List.of(ref("REQUIREMENT", "LevelCap", "100", "级")),
                                List.of(), List.of()),
                        new VersionFactBlock("5.1",
                                List.of(ref("REQUIREMENT", "LevelCap", "120", "级")),
                                List.of(), List.of())));

        FactAssessment assessment = service.assess(plan(true, false), view);

        assertThat(assessment.implementationGaps())
                .anyMatch(item -> item.type().equals("REQUIREMENT_PARAMETER_MISMATCH")
                        && item.value().contains("LevelCap=120级"));
    }

    @Test
    void passeedTestDoesNotRaiseGapWhenEvidencePresent() {
        EntityView view = view(
                new CurrentFacts(
                        List.of(),
                        List.of(),
                        List.of(new FactRef("c-test", null, "TEST_RESULT", "TC-001", "PASSED", null,
                                "5.1", List.of("ev-test-passed"), null))),
                List.of());

        FactAssessment assessment = service.assess(plan(true, false), view);

        assertThat(assessment.implementationGaps()).isEmpty();
        assertThat(assessment.validation()).extracting(AssessmentItem::status)
                .contains("SUPPORTED");
    }

    @Test
    void differentUnitsAreNotNumericallyEqual() {
        FactRef requirement = new FactRef("req", null, "REQUIREMENT", "LevelCap", "100", "级",
                "5.1", List.of("ev-r"), null);
        FactRef parameter = new FactRef("param", null, "PARAMETER_TABLE", "LevelCap", "100", "秒",
                "5.1", List.of("ev-p"), null);
        EntityView view = view(new CurrentFacts(List.of(), List.of(parameter), List.of()),
                List.of(new VersionFactBlock("5.1", List.of(requirement), List.of(), List.of())));

        FactAssessment assessment = service.assess(plan(true, false), view);

        assertThat(assessment.implementationGaps())
                .anyMatch(item -> item.type().equals("REQUIREMENT_PARAMETER_MISMATCH"));
    }

    @Test
    void rangeValuesAreComparedAsRangesNotSingleNumbers() {
        FactRef requirement = new FactRef("req", null, "REQUIREMENT", "LevelCap", "1-100", "级",
                "5.1", List.of("ev-r"), null);
        FactRef parameter = new FactRef("param", null, "PARAMETER_TABLE", "LevelCap", "1-120", "级",
                "5.1", List.of("ev-p"), null);
        EntityView view = view(new CurrentFacts(List.of(), List.of(parameter), List.of()),
                List.of(new VersionFactBlock("5.1", List.of(requirement), List.of(), List.of())));

        assertThat(service.assess(plan(true, false), view).implementationGaps())
                .anyMatch(item -> item.type().equals("REQUIREMENT_PARAMETER_MISMATCH"));
    }

    @Test
    void factWithoutEvidenceIsNeverSupported() {
        // Fix 5：无 Evidence 的事实最多 UNVERIFIED，不能进入确认状态
        EntityView view = view(
                new CurrentFacts(
                        List.of(new FactRef(null, "s-1", "CODE", "LevelValidator", null, null,
                                "5.1", List.of(), null)),
                        List.of(new FactRef("c-param", null, "PARAMETER_TABLE", "LevelCap", "100", "级",
                                "5.1", List.of(), null)),
                        List.of()),
                List.of(new VersionFactBlock("5.1",
                        List.of(new FactRef("c-req", null, "REQUIREMENT", "LevelCap", "120", "级",
                                "5.1", List.of(), null)),
                        List.of(), List.of())));

        FactAssessment assessment = service.assess(plan(true, false), view);

        assertThat(assessment.currentBehavior())
                .extracting(AssessmentItem::status).contains("UNVERIFIED");
        assertThat(assessment.currentValues())
                .extracting(AssessmentItem::status).contains("UNVERIFIED");
        assertThat(assessment.requirementTarget())
                .extracting(AssessmentItem::status).contains("UNVERIFIED");
        // 无证据时需求-参数 mismatch 仍需报告（确定性信号），但事实本身不确认
        assertThat(assessment.implementationGaps())
                .anyMatch(item -> item.type().equals("REQUIREMENT_PARAMETER_MISMATCH"));
    }
}