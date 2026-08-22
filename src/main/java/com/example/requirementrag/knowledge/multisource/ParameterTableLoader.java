package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.ParameterClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.ParameterValueType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 数值表解析器：识别表头别名，对数值做类型化转换，保留原始行列位置。
 *
 * <p>首期为确定性解析（不依赖 LLM），适用于“模块/参数/值/单位/范围/版本”结构的参数表。
 */
@Component
public class ParameterTableLoader {

    /** 表头别名 -> 角色。 */
    private static final Map<String, String> ROLE_ALIASES = Map.ofEntries(
            Map.entry("模块", "module"), Map.entry("子系统", "module"), Map.entry("功能", "module"),
            Map.entry("参数", "parameter"), Map.entry("指标", "parameter"), Map.entry("配置项", "parameter"),
            Map.entry("值", "value"), Map.entry("数值", "value"), Map.entry("取值", "value"),
            Map.entry("最小值", "min"), Map.entry("下限", "min"), Map.entry("min", "min"),
            Map.entry("最大值", "max"), Map.entry("上限", "max"), Map.entry("max", "max"),
            Map.entry("单位", "unit"), Map.entry("unit", "unit"),
            Map.entry("版本", "version"), Map.entry("生效版本", "version"),
            Map.entry("说明", "note"), Map.entry("备注", "note"));

    private static final Set<String> INCLUSIVE_HINTS = Set.of("含", "含边界", "inclusive", "含下限");

    /** 表头布局：角色 -> 列序号。 */
    public record TableLayout(Map<String, Integer> columnByRole, List<String> headers) {
        public String header(int column) {
            return column >= 0 && column < headers.size() ? headers.get(column) : "";
        }
    }

    /** 解析表头，识别别名角色。 */
    public TableLayout parseHeaders(List<String> headers) {
        Map<String, Integer> columnByRole = new LinkedHashMap<>();
        List<String> normalized = headers == null ? List.of() : headers;
        for (int index = 0; index < normalized.size(); index++) {
            String role = ROLE_ALIASES.get(normalized.get(index).trim().toLowerCase(Locale.ROOT));
            if (role != null) {
                columnByRole.putIfAbsent(role, index);
            }
        }
        return new TableLayout(columnByRole, normalized);
    }

    /** 解析一行并生成参数 Claim；行号从 2 开始（表头占第 1 行）。 */
    public ParameterClaim parseRow(TableLayout layout, Map<String, String> row, int rowNumber,
                                   String projectId, String version, String workbook, String sheetName) {
        String module = cell(row, layout.columnByRole, "module");
        String parameter = cell(row, layout.columnByRole, "parameter");
        if (parameter == null || parameter.isBlank()) {
            throw new IllegalArgumentException("参数表第 " + rowNumber + " 行缺少参数名");
        }
        String rawValue = cell(row, layout.columnByRole, "value");
        String unit = cell(row, layout.columnByRole, "unit");
        String minRaw = cell(row, layout.columnByRole, "min");
        String maxRaw = cell(row, layout.columnByRole, "max");
        String versionCell = cell(row, layout.columnByRole, "version");
        String effectiveVersion = versionCell == null || versionCell.isBlank() ? version : versionCell.trim();
        String factKey = factKey(projectId, effectiveVersion, module, parameter);
        BigDecimal minValue = decimal(minRaw);
        BigDecimal maxValue = decimal(maxRaw);
        ParameterValueType valueType = typeOf(rawValue);
        boolean inclusive = inclusive(layout, row);
        String normalized = normalizeValue(rawValue, valueType);
        int precision = precisionOf(rawValue, valueType);
        String columnRange = columnRange(layout);
        String evidenceLocation = sheetName + "!" + (rowNumber + 1);
        String claimId = "param:" + sha256(projectId + "|" + effectiveVersion + "|" + workbook + "|" + sheetName + "|" + (rowNumber + 1)).substring(0, 32);
        return new ParameterClaim(claimId, projectId, effectiveVersion, workbook, sheetName,
                rowNumber + 1, columnRange, module, parameter, rawValue == null ? "" : rawValue.trim(),
                normalized, unit == null ? "" : unit.trim(), minValue, maxValue, precision,
                inclusive, valueType, factKey, evidenceLocation);
    }

    /** 解析整个参数表：返回每行参数 Claim。 */
    public List<ParameterClaim> parse(TableLayout layout, List<Map<String, String>> rows,
                                      String projectId, String version, String workbook, String sheetName) {
        List<ParameterClaim> result = new ArrayList<>();
        if (rows == null) return result;
        for (int index = 0; index < rows.size(); index++) {
            Map<String, String> row = rows.get(index);
            if (row == null || row.isEmpty()) continue;
            result.add(parseRow(layout, row, index + 1, projectId, version, workbook, sheetName));
        }
        return result;
    }

    private String cell(Map<String, String> row, Map<String, Integer> columnByRole, String role) {
        Integer column = columnByRole.get(role);
        if (column == null) return null;
        String value = row.get(column.toString());
        return value == null ? null : value.trim();
    }

    private String columnRange(TableLayout layout) {
        if (layout.headers().isEmpty()) return "";
        return "A" + 1 + ":" + columnLetter(layout.headers().size() - 1) + 1;
    }

    private String columnLetter(int column) {
        StringBuilder builder = new StringBuilder();
        column = column + 1;
        while (column > 0) {
            int remainder = (column - 1) % 26;
            builder.insert(0, (char) ('A' + remainder));
            column = (column - 1) / 26;
        }
        return builder.toString();
    }

    private boolean inclusive(TableLayout layout, Map<String, String> row) {
        String note = cell(row, layout.columnByRole, "note");
        if (note != null) {
            for (String hint : INCLUSIVE_HINTS) if (note.toLowerCase(Locale.ROOT).contains(hint.toLowerCase(Locale.ROOT))) return true;
        }
        String header = layout.header(layout.columnByRole.getOrDefault("min", 0));
        return header.toLowerCase(Locale.ROOT).contains("含");
    }

    private ParameterValueType typeOf(String raw) {
        if (raw == null || raw.isBlank()) return ParameterValueType.TEXT;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.equals("true") || value.equals("false") || value.equals("是") || value.equals("否")) return ParameterValueType.BOOLEAN;
        if (value.endsWith("%")) return ParameterValueType.PERCENTAGE;
        if (value.matches("-?\\d+")) return ParameterValueType.INTEGER;
        if (value.matches("-?\\d+(\\.\\d+)?")) return ParameterValueType.DECIMAL;
        if (value.matches("-?\\d+\\s*(分钟|分|min|秒|s|小时|h)")) return ParameterValueType.DURATION;
        return ParameterValueType.TEXT;
    }

    private String normalizeValue(String raw, ParameterValueType type) {
        if (raw == null) return "";
        String value = raw.trim();
        if (type == ParameterValueType.PERCENTAGE && value.endsWith("%")) {
            return value.substring(0, value.length() - 1).trim();
        }
        return value;
    }

    private int precisionOf(String raw, ParameterValueType type) {
        if (raw == null) return 0;
        String value = raw.trim();
        if (type == ParameterValueType.DECIMAL || type == ParameterValueType.PERCENTAGE) {
            int dot = value.indexOf('.');
            if (dot >= 0) {
                int end = value.indexOf('%');
                String fraction = value.substring(dot + 1, end < 0 ? value.length() : end);
                return fraction.length();
            }
        }
        return 0;
    }

    private BigDecimal decimal(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String value = raw.trim().replace("%", "").replace(",", "");
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String factKey(String projectId, String version, String module, String parameter) {
        return (projectId + "|" + version + "|" + safe(module) + "|" + safe(parameter)).toLowerCase(Locale.ROOT);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
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