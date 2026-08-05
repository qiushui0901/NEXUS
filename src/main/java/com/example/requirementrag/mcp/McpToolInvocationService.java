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

/**
 * MCP 工具调用的统一横切服务：在真实调用前完成身份认证、权限校验与项目解析，
 * 调用后统一做响应体大小约束、审计日志与指标上报；
 * 对预期内的依赖不可用（{@link McpDependencyUnavailableException}）降级为带警告的响应。
 */
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

    /**
     * 执行一次受保护的 MCP 工具调用：认证用户 → 校验权限 → 解析生效项目 →
     * 执行动作（经响应体上限约束）→ 记录审计日志与指标。
     * 依赖不可用时返回 DEGRADED 降级响应；其余异常记录后原样抛出。
     *
     * @param tool               工具名（用于日志与指标标签）
     * @param request            MCP 同步请求上下文，用于取回已认证用户
     * @param requestedProjectId 客户端请求的项目 ID，可为 null（走默认项目）
     * @param version            目标业务版本，可为 null
     * @param permission         调用所需的最小权限
     * @param action             实际业务动作，入参为解析后的生效项目 ID
     * @param <T>                响应 data 类型
     * @return 受约束后的工具响应（成功或降级）
     * @throws com.example.requirementrag.security.UnauthenticatedException 未认证或未携带用户上下文时抛出
     */
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

    /** 从 MCP 传输上下文取回认证用户；请求/上下文缺失或类型不符时按未认证处理。 */
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

    /** 以 INFO 级别输出一次工具调用的审计日志。 */
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
