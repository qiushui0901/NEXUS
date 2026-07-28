package com.example.requirementrag.mcp;

import com.example.requirementrag.model.SourceSnippet;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals("MCP_RESPONSE_TRUNCATED", bounded.warnings().getFirst().code());
    }
}
