package com.example.requirementrag.mcp;

import com.example.requirementrag.evidence.EvidenceRef;
import com.example.requirementrag.model.SourceSnippet;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpResponsePolicyTest {

    private final McpResponsePolicy policy = new McpResponsePolicy(
            new McpProperties(true, 20, 200, 8, 40, 120_000),
            JsonMapper.builder().build());

    @Test
    void clampsLimitsLinesAndExcerpts() {
        assertEquals(20, policy.limit(99));
        assertEquals(200, policy.endLine(1, 500));
        assertEquals("12345678…", policy.bounded("123456789"));
        assertEquals("src/Main.java", policy.relativePath("src/./Main.java"));
    }

    @Test
    void validatesRequiredStringsDistinctValuesAndLineRanges() {
        assertEquals("query", policy.required(" query ", "query"));
        assertThrows(IllegalArgumentException.class, () -> policy.required(" ", "query"));
        assertThrows(IllegalArgumentException.class, () -> policy.distinct("5.1", "5.1", "versions differ"));
        assertThrows(IllegalArgumentException.class, () -> policy.endLine(0, 10));
        assertThrows(IllegalArgumentException.class, () -> policy.endLine(10, 9));
    }

    @Test
    void detectsTextCollectionAndEvidenceTruncationAtConfiguredBounds() {
        assertFalse(policy.textTruncated(null));
        assertFalse(policy.textTruncated("12345678"));
        assertTrue(policy.textTruncated("123456789"));

        assertFalse(policy.textListTruncated(null));
        assertFalse(policy.textListTruncated(Collections.nCopies(20, "value")));
        assertTrue(policy.textListTruncated(Collections.nCopies(21, "value")));
        assertTrue(policy.textListTruncated(List.of("123456789")));

        assertFalse(policy.collectionTruncated(null));
        assertFalse(policy.collectionTruncated(Collections.nCopies(20, "value")));
        assertTrue(policy.collectionTruncated(Collections.nCopies(21, "value")));

        EvidenceRef safe = evidence("excerpt");
        EvidenceRef oversized = evidence("123456789");
        assertFalse(policy.evidenceTruncated(null));
        assertFalse(policy.evidenceTruncated(Collections.nCopies(40, safe)));
        assertTrue(policy.evidenceTruncated(Collections.nCopies(41, safe)));
        assertTrue(policy.evidenceTruncated(List.of(oversized)));
        assertTrue(policy.truncated(20, 20, List.of(oversized)));
    }

    @Test
    void rejectsAbsoluteAndEscapingPaths() {
        assertThrows(IllegalArgumentException.class, () -> policy.relativePath("/tmp/secret"));
        assertThrows(IllegalArgumentException.class, () -> policy.relativePath("../secret"));
        assertThrows(IllegalArgumentException.class, () -> policy.relativePath("C:\\temp\\secret"));
        assertThrows(IllegalArgumentException.class, () -> policy.relativePath("https://internal/source"));
    }

    @Test
    void sourceProjectionNeverReturnsAbsolutePath() {
        assertThrows(IllegalArgumentException.class,
                () -> policy.source(new SourceSnippet("/tmp/Main.java", 1, 1, "secret")));
        SourceSnippet safe = policy.source(new SourceSnippet("src/Main.java", 1, 1, "123456789"));
        assertEquals("src/Main.java", safe.filePath());
        assertTrue(safe.text().endsWith("…"));
    }

    private EvidenceRef evidence(String excerpt) {
        return new EvidenceRef("requirement:1", null, "project", "5.1", "title",
                "docs/spec.md", "section", excerpt, null, null, null, null);
    }

    @Test
    void replacesOversizedPayloadWithExplicitTruncation() {
        McpResponsePolicy smallPolicy = new McpResponsePolicy(
                new McpProperties(true, 20, 200, 2_000, 40, 4_096),
                JsonMapper.builder().build());
        McpToolResponse<String> response = new McpToolResponse<>(
                new McpToolResponse.ResolvedScope("project", null, null),
                "x".repeat(8_000), java.util.List.of(), java.util.Map.of(), java.util.List.of(), false);

        McpToolResponse<String> bounded = smallPolicy.enforceTotalLimit(response);

        assertEquals(null, bounded.data());
        assertTrue(bounded.truncated());
        assertEquals("MCP_RESPONSE_TRUNCATED", bounded.warnings().get(0).code());
    }
}
