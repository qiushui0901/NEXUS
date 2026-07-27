package com.example.requirementrag.retrieval;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 以受控批次生成向量。批次失败时自动二分降级，避免并发请求压垮本地嵌入服务。
 */
@Component
public class EmbeddingBatcher {

    static final int DEFAULT_BATCH_SIZE = 32;

    private final EmbeddingModel embeddingModel;

    public EmbeddingBatcher(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /** 为全部文本生成与输入顺序一致的向量。 */
    public List<float[]> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += DEFAULT_BATCH_SIZE) {
            int end = Math.min(start + DEFAULT_BATCH_SIZE, texts.size());
            vectors.addAll(embedBatch(texts.subList(start, end), start));
        }
        return List.copyOf(vectors);
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
