package com.example.requirementrag.requirement.graph.document;

import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.DocumentStructureNode;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.SourceAnchor;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.StructureNodeType;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档结构抽取（Phase 2）：基于文本行的确定性结构推断。
 *
 * <p>识别标题、编号化需求、表格行与列表项，并为每个节点建立不可变 SourceAnchor。
 * 解析器升级为真实 DOCX/PDF 结构时，本类退化为兜底（记录来源质量）。
 */
@Component
public class DocumentStructureExtractor {

    private static final Pattern HEADING_PATTERN = Pattern.compile(
            "^(#+\\s*|第[一二三四五六七八九十百]+[章节条款]\\s*|\\s*\\d+(\\.\\d+)*[ .、．]\\s*.*$)");
    private static final Pattern REQUIREMENT_PATTERN = Pattern.compile("\\bREQ-\\d+\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIST_PATTERN = Pattern.compile("^\\s*[-*•]\\s+");
    private static final Pattern NUMBERED_LIST_PATTERN = Pattern.compile("^\\s*\\d+[.、．]\\s+");

    public StructureExtraction extract(String documentId, String requirementVersion, String documentRevision,
                                       String documentText) {
        if (documentText == null) {
            documentText = "";
        }
        List<SourceAnchor> anchors = new ArrayList<>();
        List<DocumentStructureNode> nodes = new ArrayList<>();
        String rootId = nodeId(documentId, requirementVersion, -1, StructureNodeType.DOCUMENT, "document");
        anchors.add(anchor(documentId, documentRevision, 0, 0, "RAW_CHAR_RANGE", ""));
        nodes.add(new DocumentStructureNode(rootId, documentId, requirementVersion, StructureNodeType.DOCUMENT,
                "", "document", null, -1, anchors.get(0).id()));

        String currentSection = rootId;
        int lineStart = 0;
        int order = 0;
        String[] lines = documentText.split("\\n", -1);
        for (String line : lines) {
            int lineEnd = Math.min(documentText.length(), lineStart + line.length());
            String trimmed = line.trim();
            if (trimmed.isBlank()) {
                lineStart = lineEnd + 1;
                continue;
            }
            StructureNodeType type = classify(trimmed);
            boolean isHeading = type == StructureNodeType.SECTION
                    && (HEADING_PATTERN.matcher(trimmed).matches() || startsWithChapterMarker(trimmed));
            String parent = isHeading ? rootId : currentSection;
            if (isHeading) {
                currentSection = nodeId(documentId, requirementVersion, order, type, trimmed);
            }
            String numberPath = isHeading ? headingNumber(trimmed) : requirementNumber(trimmed);
            SourceAnchor anchor = anchor(documentId, documentRevision, lineStart, lineEnd, "RAW_CHAR_RANGE", line);
            anchors.add(anchor);
            nodes.add(new DocumentStructureNode(
                    nodeId(documentId, requirementVersion, order, type, trimmed),
                    documentId, requirementVersion, type, numberPath, trimmed, parent, order, anchor.id()));
            order++;
            lineStart = lineEnd + 1;
        }
        return new StructureExtraction(List.copyOf(nodes), List.copyOf(anchors), rootId);
    }

    private StructureNodeType classify(String line) {
        if (REQUIREMENT_PATTERN.matcher(line).find()) {
            return StructureNodeType.REQUIREMENT;
        }
        if (line.indexOf('|') >= 0 && line.split("\\|").length >= 3) {
            return StructureNodeType.TABLE_ROW;
        }
        if (LIST_PATTERN.matcher(line).find()) {
            return StructureNodeType.LIST;
        }
        return StructureNodeType.SECTION;
    }

    private boolean startsWithChapterMarker(String line) {
        return line.startsWith("#") || line.startsWith("第");
    }

    private String headingNumber(String line) {
        if (line.startsWith("#")) return "";
        Matcher matcher = Pattern.compile("^(\\d+(?:\\.\\d+)*|第[一二三四五六七八九十百]+[章节条款])").matcher(line);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String requirementNumber(String line) {
        Matcher matcher = REQUIREMENT_PATTERN.matcher(line);
        return matcher.find() ? matcher.group() : "";
    }

    private SourceAnchor anchor(String documentId, String documentRevision, int start, int end,
                                String type, String text) {
        String id = "sa:" + sha256(documentId + "|" + documentRevision + "|" + start + "|" + end).substring(0, 24);
        return new SourceAnchor(id, documentId, documentRevision, type, start, end,
                String.valueOf(text.hashCode()), text);
    }

    private String nodeId(String documentId, String version, int order, StructureNodeType type, String title) {
        String key = documentId + "|" + version + "|" + order + "|" + type + "|"
                + title.toLowerCase(Locale.ROOT);
        return "ds:" + sha256(key).substring(0, 24);
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record StructureExtraction(List<DocumentStructureNode> nodes, List<SourceAnchor> anchors, String rootId) {
        public StructureExtraction {
            nodes = nodes == null ? List.of() : List.copyOf(nodes);
            anchors = anchors == null ? List.of() : List.copyOf(anchors);
        }
    }
}