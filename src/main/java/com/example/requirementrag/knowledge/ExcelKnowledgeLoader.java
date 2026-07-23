package com.example.requirementrag.knowledge;

import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.KnowledgeEntry;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Excel（XLSX）知识加载器，直接解析 OOXML 结构读取工作表数据。
 */
@Component
public class ExcelKnowledgeLoader {

    private static final String MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String OFFICE_REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String PKG_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships";

    private final RagProperties properties;

    /** 注入 RAG 配置。 */
    public ExcelKnowledgeLoader(RagProperties properties) {
        this.properties = properties;
    }

    /** 加载当前版本对应工作表的存疑条目。 */
    public List<KnowledgeEntry> load(Path xlsxPath) throws IOException {
        String sheetName = properties.knowledge().xlsxSheetPrefix() + properties.knowledge().version();
        return loadSheet(xlsxPath, sheetName);
    }

    /** 列出 XLSX 中所有工作表名称。 */
    public List<String> sheetNames(Path xlsxPath) throws IOException {
        if (!Files.isRegularFile(xlsxPath)) {
            throw new IOException("XLSX 知识库不存在: " + xlsxPath);
        }
        try (ZipFile zipFile = new ZipFile(xlsxPath.toFile())) {
            Document workbook = parse(zipFile.getInputStream(zipFile.getEntry("xl/workbook.xml")));
            NodeList sheets = workbook.getElementsByTagNameNS(MAIN_NS, "sheet");
            List<String> names = new ArrayList<>(sheets.getLength());
            for (int index = 0; index < sheets.getLength(); index++) {
                names.add(((Element) sheets.item(index)).getAttribute("name"));
            }
            return names;
        }
    }

    /** 加载指定工作表的存疑条目。 */
    public List<KnowledgeEntry> loadSheet(Path xlsxPath, String sheetName) throws IOException {
        if (!Files.isRegularFile(xlsxPath)) {
            throw new IOException("XLSX 知识库不存在: " + xlsxPath);
        }

        try (ZipFile zipFile = new ZipFile(xlsxPath.toFile())) {
            String sheetPath = resolveSheetPath(zipFile, sheetName);
            List<String> sharedStrings = readSharedStrings(zipFile);
            Document sheet = parse(zipFile.getInputStream(zipFile.getEntry(sheetPath)));
            return readRows(sheet, sharedStrings, sheetName);
        }
        catch (IOException exception) {
            throw exception;
        }
        catch (RuntimeException exception) {
            throw new IOException("解析 XLSX 失败: " + exception.getMessage(), exception);
        }
    }

    /** 根据工作表名解析其在 ZIP 包内的 XML 路径。 */
    private String resolveSheetPath(ZipFile zipFile, String sheetName) throws IOException {
        Document workbook = parse(zipFile.getInputStream(zipFile.getEntry("xl/workbook.xml")));
        Document relationships = parse(zipFile.getInputStream(zipFile.getEntry("xl/_rels/workbook.xml.rels")));
        Map<String, String> relTargets = relationshipTargets(relationships, "worksheet");

        NodeList sheets = workbook.getElementsByTagNameNS(MAIN_NS, "sheet");
        for (int index = 0; index < sheets.getLength(); index++) {
            Element sheet = (Element) sheets.item(index);
            if (!sheetName.equals(sheet.getAttribute("name"))) {
                continue;
            }
            String relationshipId = sheet.getAttributeNS(OFFICE_REL_NS, "id");
            String target = relTargets.get(relationshipId);
            if (target == null) {
                break;
            }
            return target.startsWith("/") ? target.substring(1) : "xl/" + target;
        }
        throw new IOException("未找到工作表: " + sheetName);
    }

    /** 读取共享字符串表。 */
    private List<String> readSharedStrings(ZipFile zipFile) throws IOException {
        ZipEntry entry = zipFile.getEntry("xl/sharedStrings.xml");
        if (entry == null) {
            return List.of();
        }
        Document document = parse(zipFile.getInputStream(entry));
        NodeList items = document.getElementsByTagNameNS(MAIN_NS, "si");
        List<String> values = new ArrayList<>(items.getLength());
        for (int index = 0; index < items.getLength(); index++) {
            values.add(textContent(items.item(index)));
        }
        return values;
    }

    /** 将工作表行解析为 KnowledgeEntry 列表。 */
    private List<KnowledgeEntry> readRows(Document sheet, List<String> sharedStrings, String sheetName) {
        List<KnowledgeEntry> entries = new ArrayList<>();
        NodeList rows = sheet.getElementsByTagNameNS(MAIN_NS, "row");
        for (int rowIndex = 0; rowIndex < rows.getLength(); rowIndex++) {
            Element row = (Element) rows.item(rowIndex);
            List<String> cells = rowCells(row, sharedStrings);
            if (cells.isEmpty()) {
                continue;
            }
            String module = valueAt(cells, 0);
            String question = valueAt(cells, 1);
            String answer = valueAt(cells, 2);
            if (rowIndex == 0 && module.contains("模块")) {
                continue;
            }
            if (question.isBlank() && answer.isBlank()) {
                continue;
            }
            String text = """
                    来源: 历史存疑整理
                    模块: %s
                    问题: %s
                    产品解答: %s
                    """.formatted(module, question, answer).trim();
            entries.add(new KnowledgeEntry("xlsx/" + sheetName + "#" + (rowIndex + 1), text));
        }
        return entries;
    }

    /** 提取一行中各列单元格值并按列序排列。 */
    private List<String> rowCells(Element row, List<String> sharedStrings) {
        NodeList cells = row.getElementsByTagNameNS(MAIN_NS, "c");
        int maxColumn = -1;
        Map<Integer, String> values = new HashMap<>();
        for (int index = 0; index < cells.getLength(); index++) {
            Element cell = (Element) cells.item(index);
            int column = columnIndex(cell.getAttribute("r"));
            values.put(column, cellValue(cell, sharedStrings));
            maxColumn = Math.max(maxColumn, column);
        }
        List<String> ordered = new ArrayList<>();
        for (int column = 0; column <= maxColumn; column++) {
            ordered.add(values.getOrDefault(column, ""));
        }
        return ordered;
    }

    /** 解析单个单元格的值（支持共享字符串与内联字符串）。 */
    private String cellValue(Element cell, List<String> sharedStrings) {
        String type = cell.getAttribute("t");
        if ("inlineStr".equals(type)) {
            NodeList texts = cell.getElementsByTagNameNS(MAIN_NS, "t");
            if (texts.getLength() == 0) {
                return "";
            }
            return texts.item(0).getTextContent().trim();
        }
        NodeList values = cell.getElementsByTagNameNS(MAIN_NS, "v");
        if (values.getLength() == 0) {
            return "";
        }
        String raw = values.item(0).getTextContent().trim();
        if ("s".equals(type)) {
            int sharedIndex = Integer.parseInt(raw);
            return sharedIndex >= 0 && sharedIndex < sharedStrings.size() ? sharedStrings.get(sharedIndex) : "";
        }
        return raw;
    }

    /** 将 Excel 列引用（如 A1）转换为零基列索引。 */
    private int columnIndex(String reference) {
        int column = 0;
        for (int index = 0; index < reference.length(); index++) {
            char ch = reference.charAt(index);
            if (Character.isDigit(ch)) {
                break;
            }
            column = column * 26 + (ch - 'A' + 1);
        }
        return Math.max(column - 1, 0);
    }

    /** 安全获取列表中指定索引的值。 */
    private String valueAt(List<String> cells, int index) {
        return index < cells.size() ? cells.get(index) : "";
    }

    /** 解析关系 XML，提取指定类型的 Id → Target 映射。 */
    private Map<String, String> relationshipTargets(Document relationships, String typeSuffix) {
        Map<String, String> targets = new HashMap<>();
        NodeList nodes = relationships.getElementsByTagNameNS(PKG_REL_NS, "Relationship");
        for (int index = 0; index < nodes.getLength(); index++) {
            Element relationship = (Element) nodes.item(index);
            if (!relationship.getAttribute("Type").endsWith("/" + typeSuffix)) {
                continue;
            }
            targets.put(relationship.getAttribute("Id"), relationship.getAttribute("Target"));
        }
        return targets;
    }

    /** 提取 XML 节点内所有文本节点的拼接内容。 */
    private String textContent(Node node) {
        NodeList texts = ((Element) node).getElementsByTagNameNS(MAIN_NS, "t");
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < texts.getLength(); index++) {
            builder.append(texts.item(index).getTextContent());
        }
        return builder.toString().trim();
    }

    /** 安全解析 XML 输入流为 DOM 文档。 */
    private Document parse(InputStream input) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(input);
        }
        catch (Exception exception) {
            throw new IllegalStateException("解析 XML 失败", exception);
        }
    }
}
