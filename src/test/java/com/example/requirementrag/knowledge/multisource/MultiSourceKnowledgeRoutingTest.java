package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.DoubtClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.DoubtStatus;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeQueryIntent;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MultiSourceKnowledgeRoutingTest {
    private final KnowledgeQueryIntentClassifier classifier = new KnowledgeQueryIntentClassifier();
    private final MultiSourceKnowledgeGate gate = new MultiSourceKnowledgeGate();

    @Test
    void classifiesQueriesByIntent() {
        assertThat(classifier.classify("权限撤销传播时间是多少？")).isEqualTo(KnowledgeQueryIntent.PARAMETER);
        assertThat(classifier.classify("该需求是否有测试覆盖？")).isEqualTo(KnowledgeQueryIntent.VALIDATION);
        assertThat(classifier.classify("哪些存疑尚未确认？")).isEqualTo(KnowledgeQueryIntent.DOUBT);
        assertThat(classifier.classify("需求和测试是否一致？")).isEqualTo(KnowledgeQueryIntent.CONSISTENCY);
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
    }

    private DoubtClaim doubt(DoubtStatus status) {
        return new DoubtClaim("doubt:" + status.name(), "p", "1.0", "模块",
                "问题", "", "存疑", 1, status, "owner", "高", null, List.of(), "存疑!1");
    }
}