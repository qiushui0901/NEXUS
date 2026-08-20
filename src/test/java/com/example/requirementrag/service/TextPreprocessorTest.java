package com.example.requirementrag.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextPreprocessorTest {
    @Test
    void removesNoiseAndConsecutiveDuplicates() {
        String cleaned = new TextPreprocessor().clean("目录\n规则A\n规则A\n第 2 页\n返回顶部\n规则B");
        assertThat(cleaned).isEqualTo("规则A\n规则B");
    }

    @Test
    void reportsTruncationWhenDocumentExceedsMaxTotalLength() {
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < 40_000; i++) {
            raw.append("规则内容").append(i).append('\n');
        }
        TextPreprocessor.CleanResult result = new TextPreprocessor().cleanWithDiagnostics(raw.toString());

        assertThat(result.truncated()).isTrue();
        assertThat(result.text().length()).isLessThan(raw.length());
        assertThat(result.text()).isNotBlank();
    }
}
