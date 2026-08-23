package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeStatus;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.TestCaseClaim;
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
 * XLSX 测试用例加载器：把「分组/模块/操作步骤/预期结果」结构的 sheet 解析为 TestCaseClaim。
 *
 * <p>确定性解析，保留文件/sheet/行号作为 Evidence；空行跳过。
 */
@Component
public class XlsxTestCaseLoader {

    private final XlsxTableReader reader;

    public XlsxTestCaseLoader() {
        this(new XlsxTableReader());
    }

    public XlsxTestCaseLoader(XlsxTableReader reader) {
        this.reader = reader;
    }

    /** 读取 XLSX 全部 sheet 并解析为测试用例 Claim。 */
    public List<TestCaseClaim> parse(Path xlsxPath, String projectId, String version) throws IOException {
        String filePath = xlsxPath.getFileName() == null ? "case.xlsx" : xlsxPath.getFileName().toString();
        List<TestCaseClaim> result = new ArrayList<>();
        for (XlsxTableReader.XlsxSheet sheet : reader.read(xlsxPath)) {
            result.addAll(parseSheet(sheet, projectId, version, filePath));
        }
        return result;
    }

    /** 解析单个 sheet 的测试用例。 */
    public List<TestCaseClaim> parseSheet(XlsxTableReader.XlsxSheet sheet, String projectId, String version,
                                          String filePath) {
        List<String> headers = sheet.headers();
        Map<String, Integer> columns = columns(headers);
        Integer groupColumn = columns.get("group");
        Integer moduleColumn = columns.get("module");
        Integer stepsColumn = columns.get("steps");
        Integer expectedColumn = columns.get("expectedResult");
        if (stepsColumn == null && expectedColumn == null) {
            return List.of();
        }
        List<TestCaseClaim> result = new ArrayList<>();
        List<Map<String, String>> rows = sheet.rows();
        for (int dataIndex = 0; dataIndex < rows.size(); dataIndex++) {
            Map<String, String> row = rows.get(dataIndex);
            int excelRow = 2 + dataIndex;
            String group = value(row, groupColumn);
            String module = value(row, moduleColumn);
            String steps = value(row, stepsColumn);
            String expected = value(row, expectedColumn);
            if (steps.isBlank() && expected.isBlank()) {
                continue;
            }
            String testCaseId = (group.isBlank() ? module : group) + "-" + sheet.sheetName() + "-" + excelRow;
            String evidenceLocation = filePath + "#" + sheet.sheetName() + "!" + excelRow;
            String claimId = "tc:" + sha256(projectId + "|" + version + "|" + filePath + "|"
                    + sheet.sheetName() + "|" + excelRow).substring(0, 32);
            result.add(new TestCaseClaim(claimId, projectId, version, testCaseId,
                    group.isBlank() ? module : group, module, "", steps, expected, "", "XLSX",
                    filePath, null, evidenceLocation, KnowledgeStatus.SUPPORTED));
        }
        return result;
    }

    private Map<String, Integer> columns(List<String> headers) {
        java.util.LinkedHashMap<String, Integer> columns = new java.util.LinkedHashMap<>();
        for (int index = 0; index < headers.size(); index++) {
            String header = headers.get(index).trim().toLowerCase(Locale.ROOT);
            String role = switch (header) {
                case "分组", "组", "group" -> "group";
                case "模块", "子系统", "功能", "module" -> "module";
                case "操作步骤", "步骤", "前置", "前置条件", "steps", "step" -> "steps";
                case "预期结果", "预期", "结果", "expectedresult", "expected" -> "expectedResult";
                default -> null;
            };
            if (role != null) {
                columns.putIfAbsent(role, index);
            }
        }
        return columns;
    }

    private String value(Map<String, String> row, Integer column) {
        if (column == null) return "";
        String value = row.get(column.toString());
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