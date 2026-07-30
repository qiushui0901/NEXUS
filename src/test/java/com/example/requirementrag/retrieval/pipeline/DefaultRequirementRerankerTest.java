package com.example.requirementrag.retrieval.pipeline;

import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.RagOutcome;
import com.example.requirementrag.model.RagOutcomeStatus;
import com.example.requirementrag.observability.RagObservability;
import com.example.requirementrag.rerank.BgeReranker;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultRequirementRerankerTest {

    @Test
    void preservesRetrievalOrderAndReportsStableWarningWhenBgeIsUnavailable() {
        BgeReranker bgeReranker = mock(BgeReranker.class);
        RagProperties properties = mock(RagProperties.class);
        RagObservability observability = mock(RagObservability.class);
        when(properties.retrieval()).thenReturn(new RagProperties.Retrieval(
                50, 50, 40, 20, 10, false, 1_000, 2, 3, 30_000,
                -1, -1, -1, -1));
        when(bgeReranker.rerank(any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("endpoint unavailable"));
        DefaultRequirementReranker reranker = new DefaultRequirementReranker(
                bgeReranker, mock(ChatClient.class), properties, observability);
        ChunkRecord first = chunk("first");
        ChunkRecord second = chunk("second");
        ChunkRecord third = chunk("third");

        RagOutcome<List<ChunkRecord>> outcome = reranker.rerank(
                "query", "requirements", "5.1", List.of(first, second, third), 2);

        assertThat(outcome.status()).isEqualTo(RagOutcomeStatus.DEGRADED);
        assertThat(outcome.data()).containsExactly(first, second);
        assertThat(outcome.warnings()).singleElement().satisfies(warning -> {
            assertThat(warning.stage()).isEqualTo("bge.rerank");
            assertThat(warning.code()).isEqualTo("BGE_RERANK_UNAVAILABLE");
            assertThat(warning.message()).isEqualTo("BGE 重排暂时不可用");
        });
        assertThat(outcome.stageDiagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.stage()).isEqualTo("bge.rerank");
            assertThat(diagnostic.status()).isEqualTo(RagOutcomeStatus.DEGRADED);
            assertThat(diagnostic.itemCount()).isEqualTo(3);
        });
        verify(observability).outcome(
                eq("bge.rerank"), eq("requirements"), eq("5.1"), eq(RagOutcomeStatus.DEGRADED),
                anyLong(), eq("BGE_RERANK_UNAVAILABLE"), any(RuntimeException.class));
    }

    private ChunkRecord chunk(String id) {
        return new ChunkRecord(id, "requirements", "5.1", id + ".md", "parent-" + id,
                "parent " + id, "child " + id, "hash-" + id, 0, 0);
    }
}
