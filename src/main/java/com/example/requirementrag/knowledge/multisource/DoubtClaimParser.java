package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.DoubtClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.DoubtStatus;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 需求存疑结构化解析器：从行级数据生成 DoubtClaim，保留 Sheet/行号/状态/负责人。
 */
@Component
public class DoubtClaimParser {

    /** 解析一行存疑数据。行号从 2 开始（表头占第 1 行）。 */
    public DoubtClaim parse(Map<String, String> row, String projectId, String version,
                            String sourceSheet, int rowNumber) {
        String module = text(row, "module", "模块", "子系统", "功能");
        String question = text(row, "question", "问题", "疑问", "待确认");
        String answer = text(row, "answer", "解答", "产品解答", "当前解答");
        String statusRaw = text(row, "status", "状态", "处理状态");
        String owner = text(row, "owner", "负责人", "owner");
        String severity = text(row, "severity", "严重级别", "严重程度", "优先级");
        String dueDate = text(row, "dueDate", "截止日期", "due");
        String optionsRaw = text(row, "options", "备选方案", "建议", "proposedOptions");
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("存疑第 " + rowNumber + " 行缺少问题");
        }
        DoubtStatus status = DoubtStatus.OPEN;
        if (statusRaw != null && !statusRaw.isBlank()) {
            String normalized = statusRaw.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
            try {
                status = DoubtStatus.valueOf(normalized);
            } catch (IllegalArgumentException ignored) {
                status = DoubtStatus.OPEN;
            }
        }
        List<String> options = optionsRaw == null || optionsRaw.isBlank()
                ? List.of() : Arrays.stream(optionsRaw.split("[;；]")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        String evidenceLocation = sourceSheet + "!" + (rowNumber + 1);
        String doubtId = "doubt:" + sha256(projectId + "|" + version + "|" + sourceSheet + "|" + (rowNumber + 1)).substring(0, 32);
        return new DoubtClaim(doubtId, projectId, version, module, question, answer,
                sourceSheet, rowNumber + 1, status, owner, severity, dueDate, options, evidenceLocation);
    }

    private String text(Map<String, String> row, String... keys) {
        for (String key : keys) {
            String value = row.get(key);
            if (value != null && !value.isBlank()) return value.trim();
        }
        return null;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}