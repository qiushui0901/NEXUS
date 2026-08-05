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
        return vectorize(text, false);
    }

    /**
     * 代码专用稀疏向量：先拆分 camelCase / snake_case 标识符再做字符级分词，
     * 使 `syncRevocation` 可被 `sync` / `revocation` 命中。索引与查询必须使用同一变体。
     */
    public SparseVector vectorizeCode(String text) {
        return vectorize(text, true);
    }

    /** 分词后按哈希去重累加权重，做 L2 归一化并按索引升序输出。 */
    private SparseVector vectorize(String text, boolean codeAware) {
        Map<Integer, Float> weights = new LinkedHashMap<>();
        for (String token : tokenize(text, codeAware)) {
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

    /**
     * 计算两段文本的归一化稀疏余弦相似度。
     * 向量已在 {@link #vectorize(String)} 中归一化，因此这里只需对有序索引做点积。
     */
    public double similarity(String left, String right) {
        SparseVector leftVector = vectorize(left == null ? "" : left);
        SparseVector rightVector = vectorize(right == null ? "" : right);
        int leftIndex = 0;
        int rightIndex = 0;
        double score = 0;
        while (leftIndex < leftVector.indices().size() && rightIndex < rightVector.indices().size()) {
            int leftTerm = leftVector.indices().get(leftIndex);
            int rightTerm = rightVector.indices().get(rightIndex);
            if (leftTerm == rightTerm) {
                score += leftVector.values().get(leftIndex) * rightVector.values().get(rightIndex);
                leftIndex++;
                rightIndex++;
            }
            else if (leftTerm < rightTerm) {
                leftIndex++;
            }
            else {
                rightIndex++;
            }
        }
        return score;
    }

    /** 中英文混合分词：汉字按单字/双字切分，英文按词切分；代码模式先拆分标识符。 */
    private List<String> tokenize(String text, boolean codeAware) {
        String split = codeAware
                ? text.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replaceAll("[_$]+", " ")
                : text;
        String normalized = split.toLowerCase(Locale.ROOT).replaceAll("[^\\p{IsHan}a-z0-9]+", " ");
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
