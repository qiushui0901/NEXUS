package com.example.requirementrag.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 父子分块器：先按较大窗口切父块，再按较小窗口切子块并保留重叠。
 */
@Component
public class ParentChildChunker {

    static final int PARENT_SIZE = 2_000;
    static final int CHILD_SIZE = 500;
    static final int CHILD_OVERLAP = 80;

    /**
     * 将文本拆分为父块及其子块列表。
     *
     * @param text 原始文本
     * @return 父块列表，每块包含有序子块；空文本返回空列表
     */
    public List<ParentChunk> split(String text) {
        List<String> parents = splitByBoundary(text, PARENT_SIZE, 0);
        List<ParentChunk> result = new ArrayList<>();
        for (int parentIndex = 0; parentIndex < parents.size(); parentIndex++) {
            String parent = parents.get(parentIndex);
            result.add(new ParentChunk(parentIndex, parent, splitByBoundary(parent, CHILD_SIZE, CHILD_OVERLAP)));
        }
        return result;
    }

    /**
     * 按字符边界（换行、句号、分号）切分，支持块间重叠以保留上下文。
     */
    private List<String> splitByBoundary(String text, int size, int overlap) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + size, text.length());
            if (end < text.length()) {
                int boundary = Math.max(text.lastIndexOf('\n', end), Math.max(text.lastIndexOf('。', end), text.lastIndexOf('；', end)));
                if (boundary > start + size / 2) {
                    end = boundary + 1;
                }
            }
            String chunk = text.substring(start, end).strip();
            if (!chunk.isBlank()) {
                chunks.add(chunk);
            }
            if (end >= text.length()) {
                break;
            }
            start = Math.max(start + 1, end - overlap);
        }
        return chunks;
    }

    /** 父块及其有序子块列表。 */
    public record ParentChunk(int order, String text, List<String> children) {
    }
}
