package com.example.requirementrag.service;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.QueryRouting;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 智能项目路由：在用户未指定 projectId 时，通过 LLM 分类将查询路由到正确项目。
 */
@Service
public class QueryRouter {

    private final ProjectRegistry projectRegistry;
    private final ChatClient chatClient;
    private final RagProperties properties;

    public QueryRouter(ProjectRegistry projectRegistry, ChatClient chatClient, RagProperties properties) {
        this.projectRegistry = projectRegistry;
        this.chatClient = chatClient;
        this.properties = properties;
    }

    /** 将用户查询路由到目标项目，按 explicit → llm → fallback 三级策略解析。 */
    public QueryRouting route(String query, String projectId) {
        if (hasText(projectId)) {
            return explicitRouting(projectId);
        }
        QueryRouting llmResult = tryLlmRouting(query);
        return llmResult != null ? llmResult : fallbackRouting();
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
        try {
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
        catch (RuntimeException ignored) {
            return null;
        }
    }

    private QueryRouting fallbackRouting() {
        RagProperties.ProjectConfig defaultProject = projectRegistry.defaultProject();
        return new QueryRouting(
                defaultProject.id(),
                normalizeSide(defaultProject.side()),
                0.0,
                "fallback");
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

    private record LlmRoutingResult(String projectId, String side, double confidence) {
    }
}
