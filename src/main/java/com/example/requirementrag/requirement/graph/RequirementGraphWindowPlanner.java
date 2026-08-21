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
        if (chunk == null || chunk.parentText() == null || chunk.parentText().isBlank()) {
            return new Plan(List.of(), 0, 0, 1.0);
        }
        if (maxChars < 1_000) throw new IllegalArgumentException("需求语义图窗口上限过小");
        String text = chunk.parentText();
        int overlap = Math.max(0, Math.min(overlapChars <= 0 ? DEFAULT_OVERLAP : overlapChars, maxChars - 1));
        List<RequirementGraphWindow> windows = new ArrayList<>();
        int start = 0;
        int index = 0;
        while (start < text.length()) {
            int targetEnd = Math.min(text.length(), start + maxChars);
            int end = targetEnd == text.length() ? targetEnd : boundary(text, start, targetEnd);
            if (end <= start) end = targetEnd;
            String windowText = text.substring(start, end);
            String id = "window:" + sha256(chunk.id() + "|" + index + "|" + start + "|" + end).substring(0, 32);
            windows.add(new RequirementGraphWindow(id, chunk.filename(), chunk.parentId(), chunk.sectionPath(),
                    chunk.heading(), chunk.parentOrder(), index, start, end, sha256(windowText), windowText,
                    index == 0 ? null : windows.get(index - 1).id()));
            if (end == text.length()) break;
            int next = Math.max(start + 1, end - overlap);
            start = next;
            index++;
        }
        int covered = coverage(windows);
        return new Plan(List.copyOf(windows), text.length(), covered,
                text.isEmpty() ? 1.0 : Math.min(1.0, (double) covered / text.length()));
    }

    public Plan plan(ChunkRecord chunk, int maxChars) {
        return plan(chunk, maxChars, DEFAULT_OVERLAP);
    }

    private int boundary(String text, int start, int targetEnd) {
        int lower = Math.min(start + 1, targetEnd);
        for (int index = targetEnd; index > lower; index--) {
            char value = text.charAt(index - 1);
            if (value == '\n' || value == '。' || value == '；' || value == ';' || value == '！' || value == '!') {
                return index;
            }
        }
        return targetEnd;
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
}
