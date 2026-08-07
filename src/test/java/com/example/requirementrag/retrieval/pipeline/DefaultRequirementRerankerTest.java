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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
                -1, -1, -1, -1, null, null, null, null, null));
        when(bgeReranker.rerankScored(any(), any(), anyInt()))
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


    @Test
    void prunesBgeInputToResolvedTopKByRrfOrder() {
        BgeReranker bgeReranker = mock(BgeReranker.class);
        RagProperties properties = mock(RagProperties.class);
        RagObservability observability = mock(RagObservability.class);
        when(properties.retrieval()).thenReturn(retrieval(false));
        DefaultRequirementReranker reranker = new DefaultRequirementReranker(
                bgeReranker, mock(ChatClient.class), properties, observability);
        java.util.ArrayList<ChunkRecord> candidates = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            candidates.add(chunk("chunk-" + i));
        }

        reranker.rerank("query", "requirements", "5.1", candidates, 10);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<ChunkRecord>> captor =
                org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(bgeReranker).rerankScored(eq("query"), captor.capture(), eq(20));
        assertThat(captor.getValue()).hasSize(20);
        assertThat(captor.getValue().get(0).id()).isEqualTo("chunk-0");
        assertThat(captor.getValue().get(19).id()).isEqualTo("chunk-19");
    }


    @Test
    void skipsBgeForSingletonOnlyWhenChildFirstQualityModeIsEnabled() {
        BgeReranker bgeReranker = mock(BgeReranker.class);
        RagProperties properties = mock(RagProperties.class);
        RagObservability observability = mock(RagObservability.class);
        when(properties.retrieval()).thenReturn(retrieval(true));
        DefaultRequirementReranker reranker = new DefaultRequirementReranker(
                bgeReranker, mock(ChatClient.class), properties, observability);
        ChunkRecord only = chunk("only");

        RagOutcome<List<ChunkRecord>> outcome = reranker.rerank(
                "query", "requirements", "5.1", List.of(only), 10);

        assertThat(outcome.status()).isEqualTo(RagOutcomeStatus.SUCCESS);
        assertThat(outcome.data()).containsExactly(only);
        assertThat(outcome.stageDiagnostics()).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.stage()).isEqualTo("bge.rerank.singleton_skip");
            assertThat(diagnostic.status()).isEqualTo(RagOutcomeStatus.SUCCESS);
            assertThat(diagnostic.itemCount()).isEqualTo(1);
        });
        verify(bgeReranker, never()).rerankScored(any(), any(), anyInt());
        verify(observability).outcome(
                "bge.rerank.singleton_skip", "requirements", "5.1",
                RagOutcomeStatus.SUCCESS, 0, null, null);
    }

    @Test
    void baselineStillCallsBgeForSingletonCandidate() {
        BgeReranker bgeReranker = mock(BgeReranker.class);
        RagProperties properties = mock(RagProperties.class);
        when(properties.retrieval()).thenReturn(retrieval(false));
        ChunkRecord only = chunk("only");
        when(bgeReranker.rerankScored(any(), any(), anyInt()))
                .thenReturn(List.of(new com.example.requirementrag.model.ScoredChunk(only, 1.0)));
        DefaultRequirementReranker reranker = new DefaultRequirementReranker(
                bgeReranker, mock(ChatClient.class), properties, mock(RagObservability.class));

        RagOutcome<List<ChunkRecord>> outcome = reranker.rerank(
                "query", "requirements", "5.1", List.of(only), 10);

        assertThat(outcome.stageDiagnostics()).singleElement().satisfies(diagnostic ->
                assertThat(diagnostic.stage()).isEqualTo("bge.rerank"));
        verify(bgeReranker).rerankScored("query", List.of(only), 1);
    }

    @Test
    void skipsLlmRerankWhenBgeTopGapExceedsThreshold() {
        BgeReranker bgeReranker = mock(BgeReranker.class);
        RagProperties properties = mock(RagProperties.class);
        when(properties.retrieval()).thenReturn(new RagProperties.Retrieval(
                50, 50, 40, 20, 10, true, 1_000, 3, 3, 30_000,
                -1, -1, -1, -1, null, null, null, null, null, 0.5));
        ChunkRecord top = chunk("top");
        ChunkRecord second = chunk("second");
        when(bgeReranker.rerankScored(any(), any(), anyInt())).thenReturn(List.of(
                new com.example.requirementrag.model.ScoredChunk(top, 0.9),
                new com.example.requirementrag.model.ScoredChunk(second, 0.2)));
        ChatClient chatClient = mock(ChatClient.class);
        DefaultRequirementReranker reranker = new DefaultRequirementReranker(
                bgeReranker, chatClient, properties, mock(RagObservability.class));

        RagOutcome<List<ChunkRecord>> outcome = reranker.rerank(
                "query", "requirements", "5.1", List.of(top, second), 10);

        assertThat(outcome.status()).isEqualTo(RagOutcomeStatus.SUCCESS);
        assertThat(outcome.data()).containsExactly(top, second);
        verify(chatClient, never()).prompt();
    }

    @Test
    void runsLlmRerankWhenBgeTopGapBelowThreshold() {
        BgeReranker bgeReranker = mock(BgeReranker.class);
        RagProperties properties = mock(RagProperties.class);
        when(properties.retrieval()).thenReturn(new RagProperties.Retrieval(
                50, 50, 40, 20, 10, true, 1_000, 3, 3, 30_000,
                -1, -1, -1, -1, null, null, null, null, null, 0.5));
        when(properties.llm()).thenReturn(new RagProperties.Llm("g", "r", null, null, null));
        ChunkRecord top = chunk("top");
        ChunkRecord second = chunk("second");
        when(bgeReranker.rerankScored(any(), any(), anyInt())).thenReturn(List.of(
                new com.example.requirementrag.model.ScoredChunk(top, 0.6),
                new com.example.requirementrag.model.ScoredChunk(second, 0.5)));
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.system(any(String.class))).thenReturn(spec);
        when(spec.user(any(String.class))).thenReturn(spec);
        when(spec.options(any())).thenReturn(spec);
        when(spec.call()).thenReturn(mock(ChatClient.CallResponseSpec.class));
        DefaultRequirementReranker reranker = new DefaultRequirementReranker(
                bgeReranker, chatClient, properties, mock(RagObservability.class));

        reranker.rerank("query", "requirements", "5.1", List.of(top, second), 10);

        verify(chatClient, times(1)).prompt();
    }

    @Test
    void missingRetrievalConfigKeepsLegacyBgeBehavior() {
        BgeReranker bgeReranker = mock(BgeReranker.class);
        RagProperties properties = mock(RagProperties.class);
        ChunkRecord only = chunk("only");
        when(bgeReranker.rerankScored(any(), any(), anyInt()))
                .thenReturn(List.of(new com.example.requirementrag.model.ScoredChunk(only, 1.0)));
        DefaultRequirementReranker reranker = new DefaultRequirementReranker(
                bgeReranker, mock(ChatClient.class), properties, mock(RagObservability.class));

        RagOutcome<List<ChunkRecord>> outcome = reranker.rerank(
                "query", "requirements", "5.1", List.of(only), 10);

        assertThat(outcome.status()).isEqualTo(RagOutcomeStatus.SUCCESS);
        assertThat(outcome.data()).containsExactly(only);
        verify(bgeReranker).rerankScored("query", List.of(only), 1);
    }

    private RagProperties.Retrieval retrieval(Boolean childFirst) {
        return new RagProperties.Retrieval(
                50, 50, 40, 20, 10, false, 1_000, 2, 3, 30_000,
                -1, -1, -1, -1, childFirst, null, null, null, null);
    }

    private ChunkRecord chunk(String id) {
        return new ChunkRecord(id, "requirements", "5.1", id + ".md", "parent-" + id,
                "parent " + id, "child " + id, "hash-" + id, 0, 0);
    }
}
