package com.example.requirementrag.retrieval;

import com.example.requirementrag.cache.BoundedTtlCache;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.ArrayList;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

class EmbeddingBatcherTest {

    @Test
    void embedsInBoundedBatchesAndKeepsInputOrder() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        List<Integer> batchSizes = new ArrayList<>();
        when(model.embed(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            batchSizes.add(texts.size());
            return texts.stream().map(text -> new float[]{Float.parseFloat(text)}).toList();
        });

        List<String> texts = new ArrayList<>();
        for (int index = 0; index < 67; index++) {
            texts.add(String.valueOf(index));
        }

        List<float[]> vectors = new EmbeddingBatcher(model).embedAll(texts);

        assertThat(batchSizes).containsExactly(32, 32, 3);
        assertThat(vectors).extracting(vector -> vector[0])
                .containsExactlyElementsOf(texts.stream().map(Float::parseFloat).toList());
    }

    @Test
    void splitsFailedBatchAndReportsTheSingleBadInput() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            if (texts.contains("bad")) {
                throw new IllegalStateException("model rejected input");
            }
            return texts.stream().map(text -> new float[]{1.0f}).toList();
        });

        assertThatThrownBy(() -> new EmbeddingBatcher(model).embedAll(List.of("ok", "bad", "ok2")))
                .isInstanceOf(EmbeddingUnavailableException.class)
                .hasMessageContaining("第 2 个文本")
                .hasMessageContaining("Ollama");
    }

    @Test
    void reusesCachedVectorsWithoutSharingMutableArrays() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyList())).thenReturn(List.of(new float[]{7.0f}));
        EmbeddingBatcher batcher = new EmbeddingBatcher(model,
                new BoundedTtlCache<>(Duration.ofMinutes(1), 10));

        List<float[]> first = batcher.embedAll(List.of("same query"));
        first.getFirst()[0] = 99.0f;
        List<float[]> second = batcher.embedAll(List.of("same query"));

        assertThat(second.getFirst()[0]).isEqualTo(7.0f);
        verify(model, times(1)).embed(anyList());
    }
}
