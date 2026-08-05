package com.example.requirementrag.mcp;

/**
 * 内部标记异常：用于把预期的 MCP 依赖不可用（如索引服务暂时离线）转换为安全的降级响应信封，
 * 而不是直接抛给 MCP 客户端。仅包内使用。
 */
final class McpDependencyUnavailableException extends RuntimeException {
    /**
     * 以底层异常为原因构造。
     *
     * @param cause 实际触发依赖不可用的底层异常
     */
    McpDependencyUnavailableException(Throwable cause) {
        super(cause);
    }
}
