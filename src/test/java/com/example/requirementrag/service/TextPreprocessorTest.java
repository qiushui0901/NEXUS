package com.example.requirementrag.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextPreprocessorTest {
    @Test
    void removesNoiseAndConsecutiveDuplicates() {
        String cleaned = new TextPreprocessor().clean("目录\n规则A\n规则A\n第 2 页\n返回顶部\n规则B");
        assertThat(cleaned).isEqualTo("规则A\n规则B");
    }
}
