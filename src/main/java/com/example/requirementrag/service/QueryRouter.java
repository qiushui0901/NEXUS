package com.example.requirementrag.service;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.QueryRouting;
import com.example.requirementrag.model.RagOutcome;
import com.example.requirementrag.model.RagOutcomeStatus;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/** 智能项目路由：在用户未指定 projectId 时，通过 LLM 分类将查询路由到正确项目。 */
@Service
public class QueryRouter {

    private static final String ROUTING_STAGE = "query.route";
    private final ProjectRegistry projectRegistry;
    private final ChatClient chatClient;
    private final RagProperties properties;

    public QueryRouter(ProjectRegistry projectRegistry, ChatClient chatClient, RagProperties properties) {
        this.projectRegistry = projectRegistry;
        this.chatClient = chatClient;
        this.properties = properties;
    }

    /**
     * 兼容旧调用方，只返回最终路由。
     *
     * @param query     用户问题，仅 projectId 缺失时参与 LLM 路由
     * @param projectId 用户显式指定的项目 ID，可空
     * @return 最终路由结果
     */
    public QueryRouting route(String query, String projectId) {
        return routeWithOutcome(query, projectId).data();
    }

    /**
     * 返回路由结果及自动路由回退诊断。
     *
     * @param query     用户问题，仅 projectId 缺失时参与 LLM 路由
     * @param projectId 用户显式指定的项目 ID，可空
     * @return 路由结果；自动路由失败时降级为默认项目并附带诊断码
     */
    public RagOutcome<QueryRouting> routeWithOutcome(String query, String projectId) {
        long started = System.nanoTime();
        if (hasText(projectId)) {
            return RagOutcome.of(RagOutcomeStatus.SUCCESS, explicitRouting(projectId), ROUTING_STAGE,
                    elapsedMillis(started), 1);
        }
        try {
            QueryRouting llmResult = tryLlmRouting(query);
            if (llmResult != null) {
                return RagOutcome.of(RagOutcomeStatus.SUCCESS, llmResult, ROUTING_STAGE,
                        elapsedMillis(started), 1);
            }
            return routingFallback(started, "ROUTING_INVALID_RESULT");
        }
        catch (RuntimeException exception) {
            return routingFallback(started, "ROUTING_LLM_UNAVAILABLE");
        }
    }

    private RagOutcome<QueryRouting> routingFallback(long started, String code) {
        return RagOutcome.degraded(fallbackRouting(), ROUTING_STAGE, code,
                "自动项目路由不可用，已使用默认项目", elapsedMillis(started), 1);
    }

    private QueryRouting explicitRouting(String projectId) {
        String side = projectRegistry.find(projectId)
                .map(RagProperties.ProjectConfig::side)
                .filter(this::hasText)
                .orElse("both");
        return new QueryRouting(projectId, normalizeSide(side), 1.0, "explicit");
    }

    private QueryRouting tryLlmRouting(String query) {
        if (!hasText(query)) {
            return null;
        }
        LlmRoutingResult result = chatClient.prompt()
                .system("""
                        你是项目路由分类器。根据用户问题判断它属于哪个项目（projectId）以及涉及哪一侧（side）。
                        只能返回已列出的 projectId，不得编造。side 只能是 server、client 或 both。
                        如果不确定，confidence 应较低。
                        """)
                .user("可用项目列表：\n" + formatProjects() + "\n\n用户问题：" + query)
                .options(GenerationChatOptions.forModel(properties.llm().resolvedRoutingModel()))
                .call()
                .entity(LlmRoutingResult.class);
        if (result == null || !hasText(result.projectId())) {
            return null;
        }
        Optional<RagProperties.ProjectConfig> project = projectRegistry.find(result.projectId());
        if (project.isEmpty()) {
            return null;
        }
        String side = hasText(result.side()) ? result.side() : project.get().side();
        double confidence = clampConfidence(result.confidence());
        return new QueryRouting(result.projectId(), normalizeSide(side), confidence, "llm");
    }

    private QueryRouting fallbackRouting() {
        RagProperties.ProjectConfig defaultProject = projectRegistry.defaultProject();
        return new QueryRouting(defaultProject.id(), normalizeSide(defaultProject.side()), 0.0, "fallback");
    }

    private String formatProjects() {
        return projectRegistry.all().stream()
                .map(project -> "- id=%s, name=%s, group=%s, side=%s".formatted(
                        project.id(), project.name(), project.group(), project.side()))
                .collect(Collectors.joining("\n"));
    }

    private String normalizeSide(String side) {
        if (!hasText(side)) {
            return "both";
        }
        String normalized = side.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "server", "client", "both" -> normalized;
            default -> "both";
        };
    }

    private double clampConfidence(double confidence) {
        if (Double.isNaN(confidence) || Double.isInfinite(confidence)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, confidence));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private long elapsedMillis(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    /** LLM 原始路由应答：项目 ID、side 与置信度。 */
    private record LlmRoutingResult(String projectId, String side, double confidence) {
    }
}
