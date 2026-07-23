package com.example.requirementrag.knowledge;

import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.HistoricalDoubt;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 历史存疑服务：从 Excel 加载旧版本存疑并格式化为 LLM 提示上下文。
 */
@Service
public class HistoricalDoubtService {

    private final RagProperties properties;
    private final ExcelKnowledgeLoader excelLoader;

    /** 注入配置与 Excel 加载器。 */
    public HistoricalDoubtService(RagProperties properties, ExcelKnowledgeLoader excelLoader) {
        this.properties = properties;
        this.excelLoader = excelLoader;
    }

    /**
     * 加载当前版本之外的所有历史存疑工作表（使用全局配置）。
     */
    public List<HistoricalDoubt> loadPriorVersions() throws IOException {
        return loadPriorVersions(
                properties.knowledge().xlsxPath(),
                properties.knowledge().version(),
                properties.knowledge().xlsxSheetPrefix());
    }

    /**
     * 加载指定 xlsx 路径下当前版本之外的所有历史存疑工作表。
     */
    public List<HistoricalDoubt> loadPriorVersions(String xlsxPathStr, String currentVersion, String prefix) throws IOException {
        if (xlsxPathStr == null || xlsxPathStr.isBlank()) {
            return List.of();
        }
        Path xlsxPath = Path.of(xlsxPathStr).toAbsolutePath().normalize();
        String resolvedPrefix = prefix != null ? prefix : "";
        String resolvedVersion = currentVersion != null ? currentVersion : "";
        List<HistoricalDoubt> doubts = new ArrayList<>();
        for (String sheetName : excelLoader.sheetNames(xlsxPath)) {
            String currentSheet = resolvedPrefix + resolvedVersion;
            if (!sheetName.startsWith(resolvedPrefix) || sheetName.equals(currentSheet)) {
                continue;
            }
            String version = sheetName.substring(resolvedPrefix.length());
            doubts.addAll(excelLoader.loadSheet(xlsxPath, sheetName).stream()
                    .map(entry -> toHistoricalDoubt(version, entry.text()))
                    .toList());
        }
        return doubts.stream()
                .sorted(Comparator.comparing(HistoricalDoubt::version).thenComparing(HistoricalDoubt::module))
                .toList();
    }

    /** 将历史存疑格式化为 LLM 提示文本，最多展示 120 条。 */
    public String formatForPrompt(List<HistoricalDoubt> doubts) {
        if (doubts.isEmpty()) {
            return "无历史存疑记录。";
        }
        StringBuilder builder = new StringBuilder();
        int limit = Math.min(doubts.size(), 120);
        for (int index = 0; index < limit; index++) {
            HistoricalDoubt doubt = doubts.get(index);
            builder.append("- [").append(doubt.version()).append("] ")
                    .append(doubt.module()).append(" / ").append(doubt.question());
            if (!doubt.answer().isBlank()) {
                builder.append(" => 已解答: ").append(doubt.answer());
            }
            builder.append('\n');
        }
        if (doubts.size() > limit) {
            builder.append("... 其余 ").append(doubts.size() - limit).append(" 条历史记录已省略\n");
        }
        return builder.toString().trim();
    }

    /** 从结构化文本解析为 HistoricalDoubt 记录。 */
    private HistoricalDoubt toHistoricalDoubt(String version, String text) {
        String module = extract(text, "模块:");
        String question = extract(text, "问题:");
        String answer = extract(text, "产品解答:");
        return new HistoricalDoubt(version, module, question, answer);
    }

    /** 从文本中按标签提取字段值。 */
    private String extract(String text, String label) {
        int start = text.indexOf(label);
        if (start < 0) {
            return "";
        }
        start += label.length();
        int end = text.indexOf('\n', start);
        return (end < 0 ? text.substring(start) : text.substring(start, end)).trim();
    }
}
