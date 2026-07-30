package com.example.requirementrag.mcp;

/** Internal marker used to turn expected MCP dependency outages into a safe degraded envelope. */
final class McpDependencyUnavailableException extends RuntimeException {
    McpDependencyUnavailableException(Throwable cause) {
        super(cause);
    }
}
