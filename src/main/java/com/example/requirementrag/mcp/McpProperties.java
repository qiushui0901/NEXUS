package com.example.requirementrag.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bounded MCP response settings. */
@ConfigurationProperties("app.mcp")
public record McpProperties(
        boolean enabled,
        int maxResults,
        int maxSourceLines,
        int maxExcerptCharacters,
        int maxEvidence,
        int maxResponseCharacters
) {
    public McpProperties {
        maxResults = positive(maxResults, 20);
        maxSourceLines = positive(maxSourceLines, 200);
        maxExcerptCharacters = positive(maxExcerptCharacters, 2_000);
        maxEvidence = positive(maxEvidence, 40);
        maxResponseCharacters = Math.max(positive(maxResponseCharacters, 120_000), 4_096);
    }

    private static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }
}
