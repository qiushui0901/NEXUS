package com.example.requirementrag.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 父子分块器：先按较大窗口切父块，再按较小窗口切子块并保留重叠。
 *
 * <p>{@link #split(String)} 保持历史固定窗口行为，供既有评测语料契约使用；
 * {@link #splitStructured(String)} 在检测到 Markdown 标题时按章节切分并保留章节路径，
 * 没有标题时自动回退到 {@link #split(String)}，用于需求文档等结构化文本入口。</p>
 */
@Component
public class ParentChildChunker {

    static final int PARENT_SIZE = 2_000;
    static final int CHILD_SIZE = 500;
    static final int CHILD_OVERLAP = 80;
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+)\\s*$");

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
     * 结构感知分块：优先按 Markdown 标题（h1-h6）切分父块，并把“章节路径”前缀保留在父块文本中；
     * 子块继续使用原有重叠窗口，以便检索命中时自带章节上下文。未检测到标题时回退到 {@link #split(String)}。
     *
     * @param text 原始文本
     * @return 父块列表；空文本返回空列表
     */
    public List<ParentChunk> splitStructured(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<Section> sections = parseSections(text);
        if (sections.isEmpty()) {
            return split(text);
        }
        List<ParentChunk> result = new ArrayList<>();
        int order = 0;
        for (Section section : sections) {
            String body = section.body().strip();
            if (body.isBlank()) {
                continue;
            }
            String sectionPath = String.join(" / ", section.path());
            String heading = section.path().isEmpty() ? "" : section.path().get(section.path().size() - 1);
            String sectionText = pathPrefix(section.path()) + body;
            for (String parent : splitByBoundary(sectionText, PARENT_SIZE, 0)) {
                result.add(new ParentChunk(order++, parent, splitByBoundary(parent, CHILD_SIZE, CHILD_OVERLAP),
                        sectionPath, heading));
            }
        }
        return result;
    }

    /**
     * 按 Markdown 标题解析成连续章节；无任何标题时返回空列表，由调用方回退。
     */
    private List<Section> parseSections(String text) {
        List<Section> sections = new ArrayList<>();
        List<String> path = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        String currentHeading = null;
        boolean anyHeading = false;
        for (String line : text.split("\\R", -1)) {
            Matcher matcher = HEADING.matcher(line);
            if (matcher.matches()) {
                if (currentHeading != null || !body.toString().isBlank()) {
                    sections.add(new Section(List.copyOf(path), body.toString()));
                    body.setLength(0);
                }
                int level = matcher.group(1).length();
                String title = matcher.group(2).strip();
                while (path.size() >= level) {
                    path.remove(path.size() - 1);
                }
                path.add(title);
                currentHeading = line.strip();
                anyHeading = true;
            } else {
                body.append(line).append('\n');
            }
        }
        if (currentHeading != null || !body.toString().isBlank()) {
            sections.add(new Section(List.copyOf(path), body.toString()));
        }
        return anyHeading ? sections : List.of();
    }

    private String pathPrefix(List<String> path) {
        if (path.isEmpty()) {
            return "";
        }
        return "【章节: " + String.join(" / ", path) + "】\n";
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

    /** 父块及其有序子块列表；结构感知分块时附带章节路径与标题元数据。 */
    public record ParentChunk(int order, String text, List<String> children,
                              String sectionPath, String heading) {
        /** 兼容旧构造器：无结构化元数据。 */
        public ParentChunk(int order, String text, List<String> children) {
            this(order, text, children, "", "");
        }
    }

    /** 结构感知分块的最小章节单元。 */
    private record Section(List<String> path, String body) {
    }
}
