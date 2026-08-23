package com.example.requirementrag.requirement.graph.document;

import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.DocumentStructureNode;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.LogicalUnit;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.SourceAnchor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 逻辑单元规划（Phase 2）：尽量不拆散一个需求、验收条件集合、表格或术语条目。
 */
@Component
public class LogicalUnitPlanner {

    private static final Pattern REQ_REFERENCE = Pattern.compile("REQ-\\d+", Pattern.CASE_INSENSITIVE);

    public List<LogicalUnit> plan(String documentId, String documentRevision,
                                  DocumentStructureExtractor.StructureExtraction extraction) {
        Map<String, SourceAnchor> anchorsById = new LinkedHashMap<>();
        for (SourceAnchor anchor : extraction.anchors()) anchorsById.put(anchor.id(), anchor);
        List<DocumentStructureNode> nodes = new ArrayList<>(extraction.nodes());
        nodes.sort(Comparator.comparingInt(DocumentStructureNode::order));

        List<LogicalUnit> units = new ArrayList<>();
        int unitIndex = 0;
        for (List<DocumentStructureNode> group : groupNodes(nodes)) {
            if (group.isEmpty()) continue;
            List<String> nodeIds = group.stream().map(DocumentStructureNode::id).toList();
            List<String> anchorIds = group.stream().map(DocumentStructureNode::sourceAnchorId).toList();
            StringBuilder text = new StringBuilder();
            List<String> refs = new ArrayList<>();
            for (String anchorId : anchorIds) {
                SourceAnchor anchor = anchorsById.get(anchorId);
                if (anchor == null) continue;
                if (!text.isEmpty()) text.append('\n');
                text.append(anchor.originalText());
                Matcher matcher = REQ_REFERENCE.matcher(anchor.originalText());
                while (matcher.find()) {
                    String ref = matcher.group().toUpperCase(Locale.ROOT);
                    if (!refs.contains(ref)) refs.add(ref);
                }
            }
            String unitType = group.get(0).nodeType() == DocumentLevelModels.StructureNodeType.REQUIREMENT
                    ? "REQUIREMENT"
                    : group.get(0).nodeType() == DocumentLevelModels.StructureNodeType.TABLE_ROW ? "TABLE" : "LIST";
            String id = "lu:" + sha256(documentId + "|" + documentRevision + "|" + unitIndex).substring(0, 24);
            units.add(new LogicalUnit(id, documentId, documentRevision, unitType,
                    nodeIds, anchorIds, text.toString(), "", List.copyOf(refs)));
            unitIndex++;
        }
        return List.copyOf(units);
    }

    /** 按 REQUIREMENT 单行 / 连续 TABLE_ROW/LIST 分组。 */
    private List<List<DocumentStructureNode>> groupNodes(List<DocumentStructureNode> nodes) {
        List<List<DocumentStructureNode>> groups = new ArrayList<>();
        List<DocumentStructureNode> current = new ArrayList<>();
        DocumentLevelModels.StructureNodeType currentType = null;
        for (DocumentStructureNode node : nodes) {
            if (node.order() < 0) continue;
            if (node.nodeType() == DocumentLevelModels.StructureNodeType.REQUIREMENT) {
                if (!current.isEmpty()) groups.add(current);
                current = new ArrayList<>();
                current.add(node);
                groups.add(current);
                current = new ArrayList<>();
                currentType = null;
            } else if (node.nodeType() == DocumentLevelModels.StructureNodeType.TABLE_ROW
                    || node.nodeType() == DocumentLevelModels.StructureNodeType.LIST) {
                if (currentType != node.nodeType()) {
                    if (!current.isEmpty()) groups.add(current);
                    current = new ArrayList<>();
                    currentType = node.nodeType();
                }
                current.add(node);
            } else {
                if (!current.isEmpty()) groups.add(current);
                current = new ArrayList<>();
                currentType = null;
            }
        }
        if (!current.isEmpty()) groups.add(current);
        return groups;
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}