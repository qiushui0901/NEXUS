package com.example.requirementrag.retrieval.pageindex;

import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 Qdrant 已持久化的 ChunkRecord 构建文档目录树。
 * 依赖 ParentChunker 产生的 parentOrder 与 parentText 首行标题（Jsoup 产生的 "# " 前缀）。
 * 文档侧专用，与代码侧完全隔离。
 */
@Component
public class HtmlTreeExtractor {

    private final QdrantHybridStore documentStore;

    public HtmlTreeExtractor(QdrantHybridStore documentStore) {
        this.documentStore = documentStore;
    }

    /** 文档章节节点。 */
    public record TocNode(String filename, int parentOrder, String title, int level, int childCount, String parentId) {}

    /**
     * 构建指定文档版本的目录树。无数据时返回空列表（调用方回退 hybridSearch）。
     */
    public List<TocNode> tree(String collection, String documentId, String version) {
        List<ChunkRecord> all;
        try {
            all = documentStore.scrollVersion(collection, documentId, version);
        } catch (RuntimeException e) {
            return List.of();
        }
        if (all.isEmpty()) return List.of();
        // 去重父块
        Map<String, TocNode> byParentId = new LinkedHashMap<>();
        Map<String, Integer> childCount = new LinkedHashMap<>();
        for (ChunkRecord c : all) {
            childCount.merge(c.parentId(), 1, Integer::sum);
        }
        for (ChunkRecord c : all) {
            if (byParentId.containsKey(c.parentId())) continue;
            String title = extractTitle(c.parentText());
            int level = extractLevel(c.parentText());
            byParentId.put(c.parentId(), new TocNode(c.filename(), c.parentOrder(), title, level,
                    childCount.getOrDefault(c.parentId(), 1), c.parentId()));
        }
        List<TocNode> result = new ArrayList<>(byParentId.values());
        result.sort((a, b) -> {
            int cmp = a.filename().compareTo(b.filename());
            if (cmp != 0) return cmp;
            return Integer.compare(a.parentOrder(), b.parentOrder());
        });
        return List.copyOf(result);
    }

    private String extractTitle(String parentText) {
        if (parentText == null || parentText.isBlank()) return "";
        for (String line : parentText.split("\\R")) {
            String t = line.strip();
            if (t.startsWith("# ")) return t.substring(2).strip();
            if (t.startsWith("## ")) return t.substring(3).strip();
            if (t.startsWith("### ")) return t.substring(4).strip();
            if (t.startsWith("#### ")) return t.substring(5).strip();
        }
        // 回退首行 80 字
        String first = parentText.strip().split("\\R", 2)[0].strip();
        return first.length() > 80 ? first.substring(0, 80) : first;
    }

    private int extractLevel(String parentText) {
        if (parentText == null) return 3;
        String first = parentText.strip();
        if (first.startsWith("# ")) return 1;
        if (first.startsWith("## ")) return 2;
        if (first.startsWith("### ")) return 3;
        return 3;
    }
}
