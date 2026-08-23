package com.example.requirementrag.requirement.graph.document;

import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.DocumentStructureNode;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.SourceAnchor;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.StructureNodeType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentStructureExtractorTest {

    private final DocumentStructureExtractor extractor = new DocumentStructureExtractor();

    @Test
    void extractsHeadingsRequirementsTableRowsAndAnchors() {
        String text = """
                # 第一章 总则

                REQ-001 系统应当支持火球冷却配置。
                REQ-002 系统应当引用 REQ-001 的冷却时间。

                | 字段 | 默认值 | 单位 |
                | 冷却 | 12 | 秒 |

                - 列表项一
                - 列表项二
                """;

        DocumentStructureExtractor.StructureExtraction result = extractor.extract(
                "doc-1", "5.1", "rev-1", text);

        List<DocumentStructureNode> nodes = result.nodes();
        assertThat(nodes).extracting(DocumentStructureNode::nodeType)
                .contains(StructureNodeType.DOCUMENT, StructureNodeType.SECTION,
                        StructureNodeType.REQUIREMENT, StructureNodeType.TABLE_ROW, StructureNodeType.LIST);
        assertThat(nodes).filteredOn(n -> n.nodeType() == StructureNodeType.REQUIREMENT)
                .extracting(DocumentStructureNode::numberPath)
                .contains("REQ-001", "REQ-002");
        assertThat(result.anchors()).extracting(SourceAnchor::originalText).contains("REQ-001 系统应当支持火球冷却配置。");
        assertThat(result.anchors()).allSatisfy(a -> {
            assertThat(a.endOffset()).isGreaterThanOrEqualTo(a.startOffset());
        });
    }
}
