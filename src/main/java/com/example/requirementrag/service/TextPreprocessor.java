package com.example.requirementrag.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 文本预处理器，去除 HTML 噪声、重复行与分页标记，控制上下文长度。
 */
@Component
public class TextPreprocessor {

    private static final int MAX_LINE_LENGTH = 4_000;
    private static final int MAX_TOTAL_LENGTH = 120_000;
    private static final Set<String> NOISE = Set.of("目录", "返回顶部", "上一页", "下一页", "点击查看", "暂无数据");

    /**
     * 清洗原始文本：去噪、去重、截断至最大长度（兼容旧调用，仅返回清洗后文本）。
     */
    public String clean(String raw) {
        return cleanWithDiagnostics(raw).text();
    }

    /**
     * 清洗原始文本并返回截断诊断，供导入链路识别“文档过长被截断”而不静默丢尾。
     */
    public CleanResult cleanWithDiagnostics(String raw) {
        if (raw == null || raw.isBlank()) {
            return new CleanResult("", false, 0, 0);
        }

        List<String> kept = new ArrayList<>();
        String previous = null;
        int totalLength = 0;
        int considered = 0;
        boolean truncated = false;
        for (String line : raw.replace('\u00a0', ' ').split("\\R")) {
            String value = normalizeLine(line);
            if (value.isBlank() || NOISE.contains(value) || isPageMarker(value)) {
                continue;
            }
            if (value.equals(previous)) {
                continue;
            }
            if (totalLength + value.length() > MAX_TOTAL_LENGTH) {
                truncated = true;
                break;
            }
            considered++;
            kept.add(value);
            previous = value;
            totalLength += value.length() + 1;
        }
        return new CleanResult(String.join("\n", new LinkedHashSet<>(kept)), truncated,
                kept.size(), considered);
    }

    /**
     * 规范化单行：合并空白、限制行长度。
     */
    private String normalizeLine(String line) {
        StringBuilder builder = new StringBuilder(Math.min(line.length(), MAX_LINE_LENGTH));
        boolean previousSpace = false;
        for (int index = 0; index < line.length() && builder.length() < MAX_LINE_LENGTH; index++) {
            char ch = line.charAt(index);
            if (ch == ' ' || ch == '\t') {
                if (!previousSpace && !builder.isEmpty()) {
                    builder.append(' ');
                    previousSpace = true;
                }
                continue;
            }
            builder.append(ch);
            previousSpace = false;
        }
        return builder.toString().strip();
    }

    /**
     * 判断是否为「第 N 页」类分页标记。
     */
    private boolean isPageMarker(String value) {
        if (value.length() > 32) {
            return false;
        }
        // 仅匹配“第 N 页 / 第 N 页 共 M 页”这类真实分页标记，避免误删“第一章 页面加载”等正文。
        return value.matches("第\\s*[0-9０-９]+\\s*页.*");
    }

    /** 清洗结果及截断诊断。 */
    public record CleanResult(String text, boolean truncated, int keptLines, int consideredLines) {
    }
}
