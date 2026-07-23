package com.example.requirementrag.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ParentChildChunkerTest {

    @Test
    void createsParentsAndOverlappingChildren() {
        String text = "规则。".repeat(1_000);
        var chunks = new ParentChildChunker().split(text);
        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.getFirst().children()).hasSizeGreaterThan(1);
        assertThat(chunks.getFirst().children()).allMatch(child -> child.length() <= ParentChildChunker.CHILD_SIZE + 1);
    }
}
