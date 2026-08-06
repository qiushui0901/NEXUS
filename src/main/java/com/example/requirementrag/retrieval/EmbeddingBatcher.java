package com.example.requirementrag.retrieval;

import com.example.requirementrag.cache.BoundedTtlCache;
import com.example.requirementrag.config.RagProperties;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 以受控批次生成向量。批次失败时自动二分降级，避免并发请求压垮本地嵌入服务。
 */
@Component
public class EmbeddingBatcher {

    static final int DEFAULT_BATCH_SIZE = 32;

    private final EmbeddingModel embeddingModel;
    private final BoundedTtlCache<String, float[]> cache;
    private final String modelFingerprint;
    /** 同一文本并发 miss 只允许一次模型调用，避免需求与代码分支重复击穿本地 Ollama。 */
    private final ConcurrentHashMap<String, CompletableFuture<float[]>> inFlight = new ConcurrentHashMap<>();

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

    /**
     * 为全部文本生成向量，按 32 条一批分批调用嵌入服务。
     *
     * @param texts 待嵌入的文本列表；为 null 或空时返回空列表
     * @return 与输入顺序一致的向量列表（向量为缓存的克隆副本）
     */
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

    /**
     * 按批嵌入文本：先读缓存，未命中的文本通过 inFlight 合并并发请求（同一文本只调用一次模型），
     * 全部完成后按原始下标组装结果并写回缓存。
     */
    private List<float[]> embedCachedBatch(List<String> texts, int absoluteStart) {
        List<float[]> result = new ArrayList<>(java.util.Collections.nCopies(texts.size(), null));
        List<PendingEmbedding> pending = new ArrayList<>();
        List<PendingEmbedding> owners = new ArrayList<>();
        for (int index = 0; index < texts.size(); index++) {
            String text = texts.get(index);
            String key = key(text);
            var cached = cache.get(key);
            if (cached.isPresent()) {
                result.set(index, cached.get().clone());
                continue;
            }

            CompletableFuture<float[]> candidate = new CompletableFuture<>();
            CompletableFuture<float[]> future = inFlight.putIfAbsent(key, candidate);
            if (future == null) {
                future = candidate;
                owners.add(new PendingEmbedding(key, text, index, future));
            }
            pending.add(new PendingEmbedding(key, text, index, future));
        }

        if (!owners.isEmpty()) {
            try {
                List<float[]> embedded = embedBatch(owners.stream().map(PendingEmbedding::text).toList(),
                        absoluteStart + owners.get(0).resultIndex());
                for (int index = 0; index < owners.size(); index++) {
                    PendingEmbedding owner = owners.get(index);
                    float[] vector = embedded.get(index);
                    cache.put(owner.key(), vector.clone());
                    owner.future().complete(vector.clone());
                }
            } catch (RuntimeException exception) {
                owners.forEach(owner -> owner.future().completeExceptionally(exception));
                throw exception;
            } finally {
                owners.forEach(owner -> inFlight.remove(owner.key(), owner.future()));
            }
        }

        try {
            for (PendingEmbedding item : pending) {
                result.set(item.resultIndex(), item.future().join().clone());
            }
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("嵌入服务并发请求失败", cause);
        }
        return List.copyOf(result);
    }

    private record PendingEmbedding(String key, String text, int resultIndex, CompletableFuture<float[]> future) {
    }

    private String key(String text) {
        return modelFingerprint + '\u0000' + (text == null ? "" : text);
    }

    /**
     * 调用嵌入模型生成向量；校验返回数量。失败时若为单条文本则抛出
     * {@link EmbeddingUnavailableException}（含字符数与排查提示），否则二分拆批重试，
     * 以定位导致失败的文本并避免整体失败。
     */
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
                String text = texts.get(0);
                throw new EmbeddingUnavailableException(
                        "第 " + (absoluteStart + 1) + " 个文本无法生成向量（字符数 "
                                + (text == null ? 0 : text.length()) + "），请确认嵌入服务与模型可用",
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
