package com.example.requirementrag.mcp;

import com.example.requirementrag.model.Permission;
import com.example.requirementrag.model.UserContext;
import com.example.requirementrag.security.ProjectAuthorizationService;
import com.example.requirementrag.security.UnauthenticatedException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.function.Function;

/** Applies shared authorization, audit logging, and metrics around MCP tool calls. */
@Service
public class McpToolInvocationService {

    private static final Logger log = LoggerFactory.getLogger(McpToolInvocationService.class);

    private final ProjectAuthorizationService authorizationService;
    private final MeterRegistry meterRegistry;
    private final McpResponsePolicy responsePolicy;

    public McpToolInvocationService(ProjectAuthorizationService authorizationService, MeterRegistry meterRegistry,
                                    McpResponsePolicy responsePolicy) {
        this.authorizationService = authorizationService;
        this.meterRegistry = meterRegistry;
        this.responsePolicy = responsePolicy;
    }

    public <T> McpToolResponse<T> invoke(String tool, org.springframework.ai.mcp.annotation.context.McpSyncRequestContext request,
                                         String requestedProjectId, String version, Permission permission,
                                         Function<String, McpToolResponse<T>> action) {
        UserContext user = authenticatedUser(request);
        authorizationService.requirePermission(user, permission);
        String projectId = authorizationService.requireProjectAccess(user, requestedProjectId);
        Timer.Sample sample = Timer.start(meterRegistry);
        long startedAt = System.nanoTime();
        try {
            McpToolResponse<T> response = responsePolicy.enforceTotalLimit(action.apply(projectId));
            String status = response.warnings().isEmpty() ? "SUCCESS" : "DEGRADED";
            record(tool, user, projectId, version, status, elapsedMillis(startedAt), response.warnings().size());
            meterRegistry.counter("nexus.mcp.tool.calls", "tool", tool, "status", status,
                    "role", user.role().name()).increment();
            response.warnings().forEach(warning -> meterRegistry.counter("nexus.mcp.tool.warnings",
                    "tool", tool, "code", warning.code()).increment());
            sample.stop(meterRegistry.timer("nexus.mcp.tool.duration", "tool", tool, "status", status));
            return response;
        }
        catch (McpDependencyUnavailableException exception) {
            String code = tool.toUpperCase(java.util.Locale.ROOT) + "_UNAVAILABLE";
            McpToolResponse<T> response = responsePolicy.enforceTotalLimit(new McpToolResponse<>(
                    new McpToolResponse.ResolvedScope(projectId, version, null), null, java.util.List.of(),
                    java.util.Map.of("status", "DEGRADED"),
                    java.util.List.of(new com.example.requirementrag.model.RagWarning(
                            "mcp", code, "Tool dependency is temporarily unavailable", 0)), false));
            record(tool, user, projectId, version, "DEGRADED", elapsedMillis(startedAt), response.warnings().size());
            meterRegistry.counter("nexus.mcp.tool.calls", "tool", tool, "status", "DEGRADED",
                    "role", user.role().name()).increment();
            sample.stop(meterRegistry.timer("nexus.mcp.tool.duration", "tool", tool, "status", "DEGRADED"));
            return response;
        }
        catch (RuntimeException exception) {
            record(tool, user, projectId, version, "FAILED", elapsedMillis(startedAt), 0);
            meterRegistry.counter("nexus.mcp.tool.calls", "tool", tool, "status", "FAILED",
                    "role", user.role().name()).increment();
            sample.stop(meterRegistry.timer("nexus.mcp.tool.duration", "tool", tool, "status", "FAILED"));
            log.warn("MCP tool failed tool={} actor={} project={} version={} exceptionType={}",
                    tool, user.username(), projectId, safe(version), exception.getClass().getSimpleName());
            throw exception;
        }
    }

    private UserContext authenticatedUser(
            org.springframework.ai.mcp.annotation.context.McpSyncRequestContext request) {
        if (request == null || request.transportContext() == null) {
            throw new UnauthenticatedException();
        }
        Object value = request.transportContext().get(McpTransportConfiguration.USER_CONTEXT_KEY);
        if (value instanceof UserContext user) {
            return user;
        }
        throw new UnauthenticatedException();
    }

    private void record(String tool, UserContext user, String projectId, String version,
                        String status, long durationMs, int warningCount) {
        log.info("MCP tool={} actor={} role={} project={} version={} durationMs={} status={} warningCount={}",
                tool, user.username(), user.role(), projectId, safe(version), durationMs, status, warningCount);
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private String safe(String value) {
        return Objects.toString(value, "");
    }
}
