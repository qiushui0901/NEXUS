package com.example.requirementrag.retrieval;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 稀疏向量化器：基于字符级分词与哈希映射生成归一化稀疏向量。
 */
@Component
public class SparseVectorizer {

    /**
     * 将文本转换为稀疏向量（indices + values），用于 Qdrant 稀疏检索。
     */
    public SparseVector vectorize(String text) {
        Map<Integer, Float> weights = new LinkedHashMap<>();
        for (String token : tokenize(text)) {
            int index = token.hashCode() & 0x7fffffff;
            weights.merge(index, 1.0f, Float::sum);
        }
        float norm = (float) Math.sqrt(weights.values().stream().mapToDouble(v -> v * v).sum());
        List<Integer> indices = new ArrayList<>(weights.size());
        List<Float> values = new ArrayList<>(weights.size());
        weights.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            indices.add(entry.getKey());
            values.add(norm == 0 ? entry.getValue() : entry.getValue() / norm);
        });
        return new SparseVector(indices, values);
    }

    /** 中英文混合分词：汉字按单字/双字切分，英文按词切分。 */
    private List<String> tokenize(String text) {
        String normalized = text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{IsHan}a-z0-9]+", " ");
        List<String> tokens = new ArrayList<>();
        for (String word : normalized.split("\\s+")) {
            if (word.isBlank()) continue;
            if (word.matches(".*\\p{IsHan}.*")) {
                for (int i = 0; i < word.length(); i++) {
                    tokens.add(word.substring(i, i + 1));
                    if (i + 1 < word.length()) tokens.add(word.substring(i, i + 2));
                }
            }
            else if (word.length() > 1) {
                tokens.add(word);
            }
        }
        return tokens;
    }

    /** 稀疏向量：索引列表与对应权重值。 */
    public record SparseVector(List<Integer> indices, List<Float> values) {
    }
}
