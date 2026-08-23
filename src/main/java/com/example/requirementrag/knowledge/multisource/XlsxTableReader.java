package com.example.requirementrag.knowledge.multisource;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 轻量 XLSX 多 sheet 读取器：复用 ZIP + DOM，不引入 POI。
 *
 * <p>输出每个 sheet 的 {@link XlsxSheet}：headers 为第一行非空单元格，
 * rows 为后续数据行（键为列序号字符串，如 "0"、"1"，与 {@link ParameterTableLoader} 行契约一致）。
 */
public final class XlsxTableReader {

    private static final String MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    /** 单个 sheet 的解析结果。 */
    public record XlsxSheet(String sheetName, List<String> headers, List<Map<String, String>> rows) {
    }

    /** 读取 XLSX 中全部工作表（按 workbook 声明顺序）。 */
    public List<XlsxSheet> read(Path xlsxPath) throws IOException {
        if (!Files.isRegularFile(xlsxPath)) {
            throw new IOException("XLSX 文件不存在: " + xlsxPath);
        }
        try (ZipFile zipFile = new ZipFile(xlsxPath.toFile())) {
            List<String> sharedStrings = readSharedStrings(zipFile);
            List<SheetRef> sheetRefs = sheetRefs(zipFile);
            List<XlsxSheet> result = new ArrayList<>();
            for (SheetRef ref : sheetRefs) {
                Document sheet = parse(zipFile.getInputStream(zipFile.getEntry(ref.path())));
                XlsxSheet parsed = readSheet(sheet, sharedStrings, ref.name());
                if (!parsed.headers().isEmpty()) {
                    result.add(parsed);
                }
            }
            return result;
        } catch (RuntimeException exception) {
            throw new IOException("解析 XLSX 失败: " + xlsxPath.getFileName(), exception);
        }
    }

    /** 解析单个 sheet XML。 */
    private XlsxSheet readSheet(Document sheet, List<String> sharedStrings, String sheetName) {
        NodeList rows = sheet.getElementsByTagNameNS(MAIN_NS, "row");
        List<String> headers = List.of();
        List<Map<String, String>> dataRows = new ArrayList<>();
        for (int rowIndex = 0; rowIndex < rows.getLength(); rowIndex++) {
            Element row = (Element) rows.item(rowIndex);
            List<String> cells = rowCells(row, sharedStrings);
            if (isEmptyRow(cells)) {
                continue;
            }
            if (rowIndex == 0) {
                headers = List.copyOf(cells);
                continue;
            }
            Map<String, String> data = new LinkedHashMap<>();
            for (int column = 0; column < cells.size(); column++) {
                data.put(String.valueOf(column), cells.get(column));
            }
            dataRows.add(data);
        }
        return new XlsxSheet(sheetName, headers, List.copyOf(dataRows));
    }

    private boolean isEmptyRow(List<String> cells) {
        return cells.stream().allMatch(value -> value == null || value.isBlank());
    }

    private List<SheetRef> sheetRefs(ZipFile zipFile) throws IOException {
        Document workbook = parse(zipFile.getInputStream(zipFile.getEntry("xl/workbook.xml")));
        Document relationships = parse(zipFile.getInputStream(zipFile.getEntry("xl/_rels/workbook.xml.rels")));
        Map<String, String> relTargets = new HashMap<>();
        NodeList rels = relationships.getElementsByTagName("Relationship");
        for (int index = 0; index < rels.getLength(); index++) {
            Element rel = (Element) rels.item(index);
            if ("worksheet".equals(rel.getAttribute("Type").replaceFirst("^.*/", ""))) {
                relTargets.put(rel.getAttribute("Id"), rel.getAttribute("Target"));
            }
        }
        List<SheetRef> result = new ArrayList<>();
        NodeList sheets = workbook.getElementsByTagNameNS(MAIN_NS, "sheet");
        for (int index = 0; index < sheets.getLength(); index++) {
            Element sheet = (Element) sheets.item(index);
            String name = sheet.getAttribute("name");
            String relationshipId = sheet.getAttributeNS(REL_NS, "id");
            String target = relTargets.get(relationshipId);
            if (target == null) {
                continue;
            }
            String path = target.startsWith("/") ? target.substring(1) : "xl/" + target;
            result.add(new SheetRef(name, path));
        }
        return result;
    }

    private List<String> readSharedStrings(ZipFile zipFile) throws IOException {
        ZipEntry entry = zipFile.getEntry("xl/sharedStrings.xml");
        if (entry == null) {
            return List.of();
        }
        Document document = parse(zipFile.getInputStream(entry));
        NodeList items = document.getElementsByTagNameNS(MAIN_NS, "si");
        List<String> values = new ArrayList<>(items.getLength());
        for (int index = 0; index < items.getLength(); index++) {
            Element item = (Element) items.item(index);
            StringBuilder text = new StringBuilder();
            NodeList runs = item.getElementsByTagNameNS(MAIN_NS, "t");
            for (int run = 0; run < runs.getLength(); run++) {
                text.append(runs.item(run).getTextContent());
            }
            values.add(text.toString());
        }
        return values;
    }

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

    private String cellValue(Element cell, List<String> sharedStrings) {
        String type = cell.getAttribute("t");
        if ("inlineStr".equals(type)) {
            NodeList texts = cell.getElementsByTagNameNS(MAIN_NS, "t");
            return texts.getLength() == 0 ? "" : texts.item(0).getTextContent().trim();
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

    private Document parse(InputStream input) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            return factory.newDocumentBuilder().parse(input);
        } catch (Exception exception) {
            throw new IOException("XML 解析失败", exception);
        }
    }

    private record SheetRef(String name, String path) {
    }
}