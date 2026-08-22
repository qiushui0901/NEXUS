package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.conflict.KnowledgeConflictModels.Authority;
import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.AnswerStatus;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.CrossSourceRelation;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.CrossSourceRelationType;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.DoubtClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.DoubtStatus;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeQueryIntent;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeStatus;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.UnifiedKnowledgeClaim;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MultiSourceKnowledgeRoutingTest {
    private final KnowledgeQueryIntentClassifier classifier = new KnowledgeQueryIntentClassifier();
    private final MultiSourceKnowledgeGate gate = new MultiSourceKnowledgeGate();
    private final MultiSourceConflictAnalyzer conflictAnalyzer = new MultiSourceConflictAnalyzer();
    private final CrossSourceRelationExtractor relationExtractor = new CrossSourceRelationExtractor();

    @Test
    void classifiesQueriesByIntent() {
        assertThat(classifier.classify("权限撤销传播时间是多少？")).isEqualTo(KnowledgeQueryIntent.PARAMETER);
        assertThat(classifier.classify("该需求是否有测试覆盖？")).isEqualTo(KnowledgeQueryIntent.VALIDATION);
        assertThat(classifier.classify("哪些存疑尚未确认？")).isEqualTo(KnowledgeQueryIntent.DOUBT);
        assertThat(classifier.classify("需求和测试是否一致？")).isEqualTo(KnowledgeQueryIntent.CONSISTENCY);
        assertThat(classifier.classify("需求和测试是否一致")).isEqualTo(KnowledgeQueryIntent.CONSISTENCY);
        assertThat(classifier.classify("修改后会影响哪些模块？")).isEqualTo(KnowledgeQueryIntent.IMPACT);
        assertThat(classifier.classify("系统应该怎么做？")).isEqualTo(KnowledgeQueryIntent.NORMATIVE);
        assertThat(classifier.classify("随便聊聊")).isEqualTo(KnowledgeQueryIntent.GENERAL);
    }

    @Test
    void excludesRejectedStaleObsoleteAndOpenDoubtOutsideDoubtIntent() {
        assertThat(gate.isRetrievable(KnowledgeStatus.VERIFIED)).isTrue();
        assertThat(gate.isRetrievable(KnowledgeStatus.REJECTED)).isFalse();
        assertThat(gate.isRetrievable(KnowledgeStatus.STALE)).isFalse();
        assertThat(gate.isRetrievable(KnowledgeStatus.OBSOLETE)).isFalse();

        DoubtClaim open = doubt(DoubtStatus.OPEN);
        DoubtClaim underDiscussion = doubt(DoubtStatus.UNDER_DISCUSSION);
        DoubtClaim resolved = doubt(DoubtStatus.RESOLVED);

        assertThat(gate.filterDoubts(List.of(open, underDiscussion, resolved), KnowledgeQueryIntent.NORMATIVE))
                .extracting(DoubtClaim::status)
                .containsExactly(DoubtStatus.RESOLVED);
        assertThat(gate.filterDoubts(List.of(open, underDiscussion, resolved), KnowledgeQueryIntent.DOUBT))
                .extracting(DoubtClaim::status)
                .containsExactly(DoubtStatus.OPEN, DoubtStatus.UNDER_DISCUSSION, DoubtStatus.RESOLVED);
        assertThat(gate.filterDoubts(List.of(open), KnowledgeQueryIntent.CONSISTENCY))
                .extracting(DoubtClaim::status)
                .containsExactly(DoubtStatus.OPEN);
    }

    @Test
    void conflictGroupsFallBackToSubjectPredicateWhenFactKeyDiffers() {
        UnifiedKnowledgeClaim parameter = new UnifiedKnowledgeClaim("tc:p", "p", "1.0",
                "P|1.0|权限撤销|传播时间", "权限撤销", "传播时间", "5分钟", "DURATION", "分钟",
                SourceType.PARAMETER_TABLE, Authority.PRIMARY, KnowledgeStatus.SUPPORTED,
                "1.0", null, "table!2", "权限撤销");
        UnifiedKnowledgeClaim testCase = new UnifiedKnowledgeClaim("tc:t", "p", "1.0",
                "T|1.0|tc-001", "权限撤销", "传播时间", "10分钟", "DURATION", "分钟",
                SourceType.TEST_CASE, Authority.SECONDARY, KnowledgeStatus.SUPPORTED,
                "1.0", null, "test#tc-001", "权限撤销");

        List<String> conflicts = conflictAnalyzer.analyze(List.of(parameter, testCase));

        assertThat(conflicts).anyMatch(message -> message.contains("PARAMETER_TEST"));
    }

    @Test
    void extractorProducesCrossSourceRelations() {
        UnifiedKnowledgeClaim requirement = claim("req-1", SourceType.REQUIREMENT, "权限撤销", "传播时间");
        UnifiedKnowledgeClaim testCase = new UnifiedKnowledgeClaim("tc-1", "p", "1.0",
                "req:req-1", "权限撤销", "取消订单测试覆盖", "成功", "TEXT", null,
                SourceType.TEST_CASE, Authority.SECONDARY, KnowledgeStatus.SUPPORTED,
                "1.0", null, "test#tc-1", "权限撤销");
        UnifiedKnowledgeClaim parameter = new UnifiedKnowledgeClaim("param-1", "p", "1.0",
                "P|1.0|权限撤销|传播时间", "权限撤销", "传播时间", "5分钟", "DURATION", "分钟",
                SourceType.PARAMETER_TABLE, Authority.PRIMARY, KnowledgeStatus.SUPPORTED,
                "1.0", null, "table!2", "权限撤销");
        DoubtClaim doubt = new DoubtClaim("doubt-1", "p", "1.0", "权限撤销", "未确认", "",
                "存疑", 1, DoubtStatus.OPEN, "owner", "高", null, List.of(), "存疑!2");

        List<CrossSourceRelation> relations = relationExtractor.extract(
                List.of(requirement, testCase, parameter), List.of(doubt));

        assertThat(relations).extracting(CrossSourceRelation::type)
                .contains(CrossSourceRelationType.VERIFIES, CrossSourceRelationType.SUPPORTS,
                        CrossSourceRelationType.RAISES_DOUBT);
    }

    private UnifiedKnowledgeClaim claim(String claimId, SourceType type, String subject, String predicate) {
        return new UnifiedKnowledgeClaim(claimId, "p", "1.0",
                subject + "|" + predicate, subject, predicate, "5", "NUMBER", null,
                type, Authority.PRIMARY, KnowledgeStatus.SUPPORTED, "1.0", null, "ev-" + claimId, subject);
    }

    private DoubtClaim doubt(DoubtStatus status) {
        return new DoubtClaim("doubt:" + status.name(), "p", "1.0", "模块",
                "问题", "", "存疑", 1, status, "owner", "高", null, List.of(), "存疑!1");
    }
}