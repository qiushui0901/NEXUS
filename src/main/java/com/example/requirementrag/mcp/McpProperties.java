package com.example.requirementrag.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MCP 响应边界配置（前缀 {@code app.mcp}），用于限制返回结果的数量与文本长度，
 * 防止超长响应超出 MCP 工具/资源的大小上限。
 */
@ConfigurationProperties("app.mcp")
public record McpProperties(
        boolean enabled,
        int maxResults,
        int maxSourceLines,
        int maxExcerptCharacters,
        int maxEvidence,
        int maxResponseCharacters
) {
    /**
     * 紧凑构造器：对非法（非正数）配置值回退到默认值，
     * 并保证 {@code maxResponseCharacters} 不低于 4096。
     */
    public McpProperties {
        maxResults = positive(maxResults, 20);
        maxSourceLines = positive(maxSourceLines, 200);
        maxExcerptCharacters = positive(maxExcerptCharacters, 2_000);
        maxEvidence = positive(maxEvidence, 40);
        maxResponseCharacters = Math.max(positive(maxResponseCharacters, 120_000), 4_096);
    }

    /** 正数则原样返回，否则回退到 {@code fallback}。 */
    private static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }
}
