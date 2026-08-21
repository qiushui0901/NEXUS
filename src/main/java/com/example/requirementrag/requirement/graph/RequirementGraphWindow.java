package com.example.requirementrag.requirement.graph;

import java.util.Objects;

/** A bounded, source-addressable extraction window. Offsets are end-exclusive in the parent text. */
public record RequirementGraphWindow(
        String id,
        String filename,
        String parentId,
        String sectionPath,
        String heading,
        int parentOrder,
        int windowIndex,
        int startOffset,
        int endOffset,
        String contentHash,
        String text,
        String continuationOf
) {
    public RequirementGraphWindow {
        filename = Objects.toString(filename, "");
        parentId = Objects.toString(parentId, "");
        sectionPath = Objects.toString(sectionPath, "");
        heading = Objects.toString(heading, "");
        contentHash = Objects.toString(contentHash, "");
        text = Objects.toString(text, "");
        if (startOffset < 0 || endOffset < startOffset) {
            throw new IllegalArgumentException("需求语义图窗口偏移范围无效");
        }
        if (endOffset - startOffset != text.length()) {
            throw new IllegalArgumentException("需求语义图窗口文本与偏移长度不一致");
        }
    }

    public int length() {
        return endOffset - startOffset;
    }
}
