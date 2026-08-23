package com.example.requirementrag.requirement.graph;

import com.example.requirementrag.model.ChunkRecord;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/** Plans overlapping extraction windows without silently dropping the tail of a parent block. */
@Component
public class RequirementGraphWindowPlanner {
    private static final int DEFAULT_OVERLAP = 400;

    public Plan plan(ChunkRecord chunk, int maxChars, int overlapChars) {
        return plan(chunk, maxChars, PlanOptions.legacy(overlapChars));
    }

    public Plan plan(ChunkRecord chunk, int maxChars, PlanOptions options) {
        if (chunk == null || chunk.parentText() == null || chunk.parentText().isBlank()) {
            return new Plan(List.of(), 0, 0, 1.0);
        }
        if (maxChars < 1_000) throw new IllegalArgumentException("需求语义图窗口上限过小");
        String text = chunk.parentText();
        int overlap = Math.max(0, Math.min(options.overlapChars() <= 0 ? DEFAULT_OVERLAP : options.overlapChars(), maxChars - 1));
        int minWindowChars = Math.max(1, options.minWindowChars());
        int minProgressChars = Math.max(1, options.minProgressChars());
        List<RequirementGraphWindow> windows = new ArrayList<>();
        int start = 0;
        int index = 0;
        while (start < text.length()) {
            int targetEnd = Math.min(text.length(), start + maxChars);
            int end = targetEnd == text.length() ? targetEnd : boundary(text, start, targetEnd, options.structureAware());
            if (end <= start + minProgressChars - 1) end = targetEnd;
            if (end <= start) end = targetEnd;
            if (end - start < minWindowChars && end < text.length()) {
                end = Math.min(text.length(), start + minWindowChars);
            }
            String windowText = text.substring(start, end);
            String id = "window:" + sha256(chunk.id() + "|" + index + "|" + start + "|" + end).substring(0, 32);
            windows.add(new RequirementGraphWindow(id, chunk.filename(), chunk.parentId(), chunk.sectionPath(),
                    chunk.heading(), chunk.parentOrder(), index, start, end, sha256(windowText), windowText,
                    index == 0 ? null : windows.get(index - 1).id()));
            if (end == text.length()) break;
            int next = Math.max(start + minProgressChars, end - overlap);
            start = next;
            index++;
            if (options.maxWindowCountPerParent() > 0 && windows.size() >= options.maxWindowCountPerParent()) {
                if (end < text.length()) {
                    // 上限保护：把剩余尾部合并为最后一个尾部窗口，避免静默丢内容
                    String tailText = text.substring(start);
                    String tailId = "window:" + sha256(chunk.id() + "|" + index + "|" + start + "|" + text.length()).substring(0, 32);
                    windows.add(new RequirementGraphWindow(tailId, chunk.filename(), chunk.parentId(), chunk.sectionPath(),
                            chunk.heading(), chunk.parentOrder(), index, start, text.length(),
                            sha256(tailText), tailText, windows.get(windows.size() - 1).id()));
                }
                break;
            }
        }
        int covered = coverage(windows);
        return new Plan(List.copyOf(windows), text.length(), covered,
                text.isEmpty() ? 1.0 : Math.min(1.0, (double) covered / text.length()));
    }

    public Plan plan(ChunkRecord chunk, int maxChars) {
        return plan(chunk, maxChars, PlanOptions.legacy(DEFAULT_OVERLAP));
    }

    private int boundary(String text, int start, int targetEnd) {
        return boundary(text, start, targetEnd, true);
    }

    private int boundary(String text, int start, int targetEnd, boolean structureAware) {
        int lower = Math.min(start + 1, targetEnd);
        if (structureAware) {
            // 优先结构边界：需求编号/标题 > 表格行 > 列表项 > 段落 > 句子 > 强制截断
            int structural = structureBoundary(text, start, targetEnd,
                    new String[]{"REQ-", "需求", "第", "表", "【", "一、", "二、", "三、", "四、", "五、",
                            "六、", "七、", "八、", "九、", "十、", "1.", "2.", "3.", "4.", "5.",
                            "6.", "7.", "8.", "9.", "0.", "- ", "* ", "• "});
            if (structural > lower) return structural;
        }
        for (int index = targetEnd; index > lower; index--) {
            char value = text.charAt(index - 1);
            if (value == '\n' || value == '。' || value == '；' || value == ';' || value == '！' || value == '!') {
                return index;
            }
        }
        return targetEnd;
    }

    private int structureBoundary(String text, int start, int targetEnd, String[] markers) {
        int best = -1;
        for (String marker : markers) {
            int index = text.lastIndexOf(marker, targetEnd - marker.length());
            if (index > start && index > best) best = index;
        }
        return best;
    }

    private int coverage(List<RequirementGraphWindow> windows) {
        if (windows.isEmpty()) return 0;
        int covered = 0;
        int start = windows.get(0).startOffset();
        int end = start;
        for (RequirementGraphWindow window : windows) {
            if (window.startOffset() > end) {
                covered += end - start;
                start = window.startOffset();
            }
            end = Math.max(end, window.endOffset());
        }
        return covered + end - start;
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record Plan(List<RequirementGraphWindow> windows, int sourceChars, int coveredChars, double coverageRatio) {
        public Plan {
            windows = windows == null ? List.of() : List.copyOf(windows);
        }
        public int windowCount() { return windows.size(); }
    }

    /** 窗口规划安全参数：最小窗口、最小推进、每父块最大窗口数与结构感知边界。 */
    public record PlanOptions(int minWindowChars, int minProgressChars, int maxWindowCountPerParent,
                              int overlapChars, boolean structureAware) {
        public PlanOptions {
            if (maxWindowCountPerParent < 0) throw new IllegalArgumentException("maxWindowCountPerParent 不能为负");
        }

        public static PlanOptions legacy(int overlapChars) {
            return new PlanOptions(0, 1, 0, overlapChars, true);
        }

        public static PlanOptions safe() {
            return new PlanOptions(120, 200, 40, 400, true);
        }
    }
}
