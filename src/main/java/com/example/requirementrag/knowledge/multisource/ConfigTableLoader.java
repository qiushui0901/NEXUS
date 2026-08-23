package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeStatus;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.ParameterClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.ParameterValueType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 通用配置表加载器：把第一行为列名的游戏/业务配置表解析为 ParameterClaim。
 *
 * <p>与 {@link ParameterTableLoader} 不同：这里没有「模块/参数/值」列约束，
 * 而是把每个 sheet 视为一张表，每一行 × 每一列都生成一条参数 Claim
 * （subject=列名，object=单元格值，module=sheet 名），并保留原始行列定位。
 */
@Component
public class ConfigTableLoader {

    private final XlsxTableReader reader;

    public ConfigTableLoader() {
        this(new XlsxTableReader());
    }

    public ConfigTableLoader(XlsxTableReader reader) {
        this.reader = reader;
    }

    /** 读取 XLSX 全部 sheet 并解析为参数 Claim。 */
    public List<ParameterClaim> parse(Path xlsxPath, String projectId, String version) throws IOException {
        String workbook = xlsxPath.getFileName() == null ? "config.xlsx" : xlsxPath.getFileName().toString();
        List<ParameterClaim> result = new ArrayList<>();
        for (XlsxTableReader.XlsxSheet sheet : reader.read(xlsxPath)) {
            result.addAll(parseSheet(sheet, projectId, version, workbook));
        }
        return result;
    }

    /** 解析单个配置表 sheet。 */
    public List<ParameterClaim> parseSheet(XlsxTableReader.XlsxSheet sheet, String projectId, String version,
                                           String workbook) {
        List<ParameterClaim> result = new ArrayList<>();
        List<String> headers = sheet.headers();
        if (headers.isEmpty()) {
            return result;
        }
        List<Map<String, String>> rows = sheet.rows();
        for (int dataIndex = 0; dataIndex < rows.size(); dataIndex++) {
            Map<String, String> row = rows.get(dataIndex);
            int excelRow = 2 + dataIndex;
            for (int column = 0; column < headers.size(); column++) {
                String header = headers.get(column);
                if (header == null || header.isBlank()) {
                    continue;
                }
                String raw = value(row, column);
                if (raw.isBlank()) {
                    continue;
                }
                String module = sheet.sheetName();
                String parameter = header.trim();
                ParameterValueType valueType = typeOf(raw);
                String normalized = normalize(raw, valueType);
                String factKey = (projectId + "|" + version + "|" + safe(module) + "|" + parameter)
                        .toLowerCase(Locale.ROOT);
                String columnLetter = columnLetter(column);
                String claimId = "param:" + sha256(projectId + "|" + version + "|" + workbook + "|"
                        + sheet.sheetName() + "|" + excelRow + "|" + column).substring(0, 32);
                String evidenceLocation = workbook + "#" + sheet.sheetName() + "!" + excelRow + ":" + columnLetter;
                result.add(new ParameterClaim(claimId, projectId, version, workbook, sheet.sheetName(),
                        excelRow, columnLetter + excelRow, module, parameter, raw, normalized, "",
                        null, null, 0, false, valueType, factKey, evidenceLocation, KnowledgeStatus.SUPPORTED));
            }
        }
        return result;
    }

    private String value(Map<String, String> row, int column) {
        String value = row.get(String.valueOf(column));
        return value == null ? "" : value.trim();
    }

    private ParameterValueType typeOf(String raw) {
        if (raw == null || raw.isBlank()) return ParameterValueType.TEXT;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.equals("true") || value.equals("false") || value.equals("是") || value.equals("否")) {
            return ParameterValueType.BOOLEAN;
        }
        if (value.endsWith("%")) return ParameterValueType.PERCENTAGE;
        if (value.matches("-?\\d+")) return ParameterValueType.INTEGER;
        if (value.matches("-?\\d+(\\.\\d+)?")) return ParameterValueType.DECIMAL;
        if (value.matches("-?\\d+\\s*(分钟|分|min|秒|s|小时|h)")) return ParameterValueType.DURATION;
        return ParameterValueType.TEXT;
    }

    private String normalize(String raw, ParameterValueType type) {
        if (raw == null) return "";
        String value = raw.trim();
        if (type == ParameterValueType.PERCENTAGE && value.endsWith("%")) {
            return value.substring(0, value.length() - 1).trim();
        }
        return value;
    }

    private String columnLetter(int column) {
        StringBuilder builder = new StringBuilder();
        int value = column + 1;
        while (value > 0) {
            int remainder = (value - 1) % 26;
            builder.insert(0, (char) ('A' + remainder));
            value = (value - 1) / 26;
        }
        return builder.toString();
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