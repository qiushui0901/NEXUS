package com.example.requirementrag.retrieval;

import com.example.requirementrag.cache.BoundedTtlCache;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.ArrayList;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
                .hasMessageContaining("嵌入服务与模型可用");
    }

    @Test
    void coalescesConcurrentMissesForTheSameText() throws Exception {
        EmbeddingModel model = mock(EmbeddingModel.class);
        AtomicInteger modelCalls = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(model.embed(anyList())).thenAnswer(invocation -> {
            modelCalls.incrementAndGet();
            started.countDown();
            assertThat(release.await(2, TimeUnit.SECONDS)).isTrue();
            return List.of(new float[]{42.0f});
        });
        EmbeddingBatcher batcher = new EmbeddingBatcher(model,
                new BoundedTtlCache<>(Duration.ofMinutes(1), 10));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<List<float[]>> first = executor.submit(() -> batcher.embedAll(List.of("same query")));
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            Future<List<float[]>> second = executor.submit(() -> batcher.embedAll(List.of("same query")));

            release.countDown();
            assertThat(first.get(2, TimeUnit.SECONDS).get(0)[0]).isEqualTo(42.0f);
            assertThat(second.get(2, TimeUnit.SECONDS).get(0)[0]).isEqualTo(42.0f);
            assertThat(modelCalls).hasValue(1);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void reusesCachedVectorsWithoutSharingMutableArrays() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyList())).thenReturn(List.of(new float[]{7.0f}));
        EmbeddingBatcher batcher = new EmbeddingBatcher(model,
                new BoundedTtlCache<>(Duration.ofMinutes(1), 10));

        List<float[]> first = batcher.embedAll(List.of("same query"));
        first.get(0)[0] = 99.0f;
        List<float[]> second = batcher.embedAll(List.of("same query"));

        assertThat(second.get(0)[0]).isEqualTo(7.0f);
        verify(model, times(1)).embed(anyList());
    }
}
