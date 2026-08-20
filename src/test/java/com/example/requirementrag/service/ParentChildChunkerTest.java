package com.example.requirementrag.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ParentChildChunkerTest {

    @Test
    void createsParentsAndOverlappingChildren() {
        String text = "规则。".repeat(1_000);
        var chunks = new ParentChildChunker().split(text);
        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.get(0).children()).hasSizeGreaterThan(1);
        assertThat(chunks.get(0).children()).allMatch(child -> child.length() <= ParentChildChunker.CHILD_SIZE + 1);
    }

    @Test
    void structuredSplitGroupsByHeadingsAndKeepsSectionPath() {
        String text = """
                # 访问控制
                会话冻结规则。会话冻结后新会话立即拒绝。
                ## 项目授权撤销
                项目移除只撤销该项目授权。
                ## 恢复访问
                恢复访问必须重新执行身份验证与项目授权。
                """;
        var chunks = new ParentChildChunker().splitStructured(text);

        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).text()).contains("【章节: 访问控制】");
        assertThat(chunks.get(1).text()).contains("【章节: 访问控制 / 项目授权撤销】");
        assertThat(chunks.get(2).text()).contains("【章节: 访问控制 / 恢复访问】");
        assertThat(chunks).allMatch(parent -> !parent.children().isEmpty());
        assertThat(chunks.get(1).text()).contains("项目移除只撤销该项目授权");
        assertThat(chunks.get(0).sectionPath()).isEqualTo("访问控制");
        assertThat(chunks.get(0).heading()).isEqualTo("访问控制");
        assertThat(chunks.get(1).sectionPath()).isEqualTo("访问控制 / 项目授权撤销");
        assertThat(chunks.get(1).heading()).isEqualTo("项目授权撤销");
        assertThat(chunks.get(2).heading()).isEqualTo("恢复访问");
    }

    @Test
    void structuredSplitFallsBackToLegacyWhenNoHeading() {
        String text = "规则。规则。规则。";
        var chunker = new ParentChildChunker();
        assertThat(chunker.splitStructured(text).stream().map(ParentChildChunker.ParentChunk::text).toList())
                .isEqualTo(chunker.split(text).stream().map(ParentChildChunker.ParentChunk::text).toList());
    }
}
