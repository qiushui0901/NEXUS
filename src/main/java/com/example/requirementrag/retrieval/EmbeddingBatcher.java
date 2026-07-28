package com.example.requirementrag.retrieval;

import com.example.requirementrag.cache.BoundedTtlCache;
import com.example.requirementrag.config.RagProperties;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 以受控批次生成向量。批次失败时自动二分降级，避免并发请求压垮本地嵌入服务。
 */
@Component
public class EmbeddingBatcher {

    static final int DEFAULT_BATCH_SIZE = 32;

    private final EmbeddingModel embeddingModel;
    private final BoundedTtlCache<String, float[]> cache;
    private final String modelFingerprint;

    public EmbeddingBatcher(EmbeddingModel embeddingModel) {
        this(embeddingModel, new BoundedTtlCache<>(Duration.ZERO, 0));
    }

    @Autowired
    public EmbeddingBatcher(EmbeddingModel embeddingModel, RagProperties properties) {
        this(embeddingModel, new BoundedTtlCache<>(
                Duration.ofSeconds(properties.retrieval().resolvedEmbeddingCacheTtlSeconds()),
                properties.retrieval().resolvedEmbeddingCacheMaxEntries()));
    }

    EmbeddingBatcher(EmbeddingModel embeddingModel, BoundedTtlCache<String, float[]> cache) {
        this.embeddingModel = embeddingModel;
        this.cache = cache;
        this.modelFingerprint = embeddingModel.getClass().getName();
    }

    /** 为全部文本生成与输入顺序一致的向量。 */
    public List<float[]> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += DEFAULT_BATCH_SIZE) {
            int end = Math.min(start + DEFAULT_BATCH_SIZE, texts.size());
            vectors.addAll(embedCachedBatch(texts.subList(start, end), start));
        }
        return List.copyOf(vectors);
    }

    private List<float[]> embedCachedBatch(List<String> texts, int absoluteStart) {
        List<float[]> result = new ArrayList<>(java.util.Collections.nCopies(texts.size(), null));
        List<String> misses = new ArrayList<>();
        List<Integer> missIndexes = new ArrayList<>();
        for (int index = 0; index < texts.size(); index++) {
            String key = key(texts.get(index));
            var cached = cache.get(key);
            if (cached.isPresent()) {
                result.set(index, cached.get().clone());
            } else {
                misses.add(texts.get(index));
                missIndexes.add(index);
            }
        }
        if (!misses.isEmpty()) {
            List<float[]> embedded = embedBatch(misses, absoluteStart);
            for (int index = 0; index < embedded.size(); index++) {
                float[] vector = embedded.get(index);
                int resultIndex = missIndexes.get(index);
                cache.put(key(texts.get(resultIndex)), vector.clone());
                result.set(resultIndex, vector);
            }
        }
        return List.copyOf(result);
    }

    private String key(String text) {
        return modelFingerprint + '\u0000' + (text == null ? "" : text);
    }

    private List<float[]> embedBatch(List<String> texts, int absoluteStart) {
        try {
            List<float[]> vectors = embeddingModel.embed(texts);
            if (vectors == null || vectors.size() != texts.size()) {
                throw new IllegalStateException("嵌入服务返回的向量数量与文本数量不一致");
            }
            return vectors;
        }
        catch (RuntimeException exception) {
            if (texts.size() == 1) {
                String text = texts.getFirst();
                throw new EmbeddingUnavailableException(
                        "第 " + (absoluteStart + 1) + " 个文本无法生成向量（字符数 "
                                + (text == null ? 0 : text.length()) + "），请确认 Ollama 与嵌入模型可用",
                        exception);
            }
            int middle = texts.size() / 2;
            List<float[]> vectors = new ArrayList<>(texts.size());
            vectors.addAll(embedBatch(texts.subList(0, middle), absoluteStart));
            vectors.addAll(embedBatch(texts.subList(middle, texts.size()), absoluteStart + middle));
            return vectors;
        }
    }
}
