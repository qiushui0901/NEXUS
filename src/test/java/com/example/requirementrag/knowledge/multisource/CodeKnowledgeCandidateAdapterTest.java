package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.code.CodeKnowledgeService;
import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeQueryIntent;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.UnifiedKnowledgeClaim;
import com.example.requirementrag.model.CodeChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodeKnowledgeCandidateAdapterTest {

    private final CodeKnowledgeService codeKnowledgeService = mock(CodeKnowledgeService.class);
    private final CodeKnowledgeCandidateAdapter adapter =
            new CodeKnowledgeCandidateAdapter(codeKnowledgeService);

    @Test
    void loadDetailedProjectsHitsAsCodeClaims() {
        when(codeKnowledgeService.search("订单", "p1", 200)).thenReturn(List.of(
                new CodeChunk("c-1", "p1", "commit-a", "OrderService.java", "class", "OrderService",
                        10, 30, "orders...", "hash-1")));

        MultiSourceCandidateAdapter.CandidateLoad loaded =
                adapter.loadDetailed("p1", "v1", "订单", KnowledgeQueryIntent.VALIDATION);

        assertThat(loaded.claims()).hasSize(1);
        UnifiedKnowledgeClaim claim = loaded.claims().get(0);
        assertThat(claim.sourceType()).isEqualTo(SourceType.CODE);
        assertThat(claim.claimId()).isEqualTo("c-1");
        assertThat(claim.evidenceLocation()).contains("OrderService.java");
    }

    @Test
    void loadDetailedSurfacesFailureAsStableWarning() {
        // 中（vaxr M4）：代码检索故障必须作为稳定告警码返回，而不是静默成"无代码结果"。
        when(codeKnowledgeService.search(anyString(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("qdrant down"));

        MultiSourceCandidateAdapter.CandidateLoad loaded =
                adapter.loadDetailed("p1", "v1", "订单", KnowledgeQueryIntent.VALIDATION);

        assertThat(loaded.claims()).isEmpty();
        assertThat(loaded.warnings()).contains("CODE_SEARCH_FAILED");
        // 旧契约 load() 仍降级为空候选（供直接调用方），不抛异常。
        assertThat(adapter.load("p1", "v1", "订单")).isEmpty();
    }
}
