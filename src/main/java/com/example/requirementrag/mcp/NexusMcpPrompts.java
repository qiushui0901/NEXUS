package com.example.requirementrag.mcp;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * 面向 MCP 客户端（如 Codex、Cursor）的可复用、作用域安全的提示词模板：
 * 每个提示词都强制限定 projectId/version 等作用域，并规定按序调用对应工具、
 * 以工具返回的 evidenceId 作为引用，禁止跨作用域或派生文本替代原始证据。
 */
@Configuration
@ConditionalOnProperty(prefix = "app.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class NexusMcpPrompts {

    /** 注册全部提示词模板（实现需求 / 评审需求 / 评估改动影响）。 */
    @Bean
    List<McpServerFeatures.SyncPromptSpecification> nexusPrompts() {
        return List.of(
                new McpServerFeatures.SyncPromptSpecification(
                        prompt("nexus_implement_requirement", "实现某需求",
                                "基于版本化需求证据、代码检索和影响分析生成可执行开发方案。"),
                        (exchange, request) -> new McpSchema.GetPromptResult("", implementRequirement(request))),
                new McpServerFeatures.SyncPromptSpecification(
                        prompt("nexus_review_requirement", "评审某需求",
                                "检索指定版本需求并生成存疑和结构化冲突检查步骤。"),
                        (exchange, request) -> new McpSchema.GetPromptResult("", reviewRequirement(request))),
                new McpServerFeatures.SyncPromptSpecification(
                        prompt("nexus_assess_change_impact", "评估改动影响",
                                "用代码图、提交差异和版本知识评估变更影响。"),
                        (exchange, request) -> new McpSchema.GetPromptResult("", assessChangeImpact(request))));
    }

    private McpSchema.Prompt prompt(String name, String title, String description) {
        return new McpSchema.Prompt(name, title, description, List.of());
    }

    /** 生成「实现某需求」提示词：先检索需求证据，再生成开发计划并做影响分析。 */
    private List<McpSchema.PromptMessage> implementRequirement(McpSchema.GetPromptRequest request) {
        Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
        String requirement = String.valueOf(args.getOrDefault("requirement", ""));
        String projectId = String.valueOf(args.getOrDefault("projectId", ""));
        String version = String.valueOf(args.getOrDefault("version", ""));
        String text = """
                你要实现需求：%s
                严格限定 projectId=%s、version=%s。
                先调用 nexus_search_requirements 获取需求证据，再调用 nexus_development_plan。
                对计划涉及的符号调用 nexus_code_graph 与 nexus_impact_analysis；必要时用 nexus_get_source 核对。
                所有结论引用工具返回的 evidenceId。若任何结果出现 scope 不一致或降级 warning，先明确标注，
                不得用其他项目、其他版本或 Wiki 派生文本替代原始证据。
                """.formatted(requirement, projectId, version);
        return List.of(
                new McpSchema.PromptMessage(McpSchema.Role.USER, new McpSchema.TextContent(text)));
    }

    /** 生成「评审某需求」提示词：检索指定版本需求并生成存疑清单。 */
    private List<McpSchema.PromptMessage> reviewRequirement(McpSchema.GetPromptRequest request) {
        Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
        String requirement = String.valueOf(args.getOrDefault("requirement", ""));
        String projectId = String.valueOf(args.getOrDefault("projectId", ""));
        String documentId = String.valueOf(args.getOrDefault("documentId", ""));
        String version = String.valueOf(args.getOrDefault("version", ""));
        String text = """
                评审主题：%s
                严格限定 projectId=%s、documentId=%s、version=%s。
                先调用 nexus_search_requirements，再调用 nexus_review_doubts。
                若需要跨需求、代码、测试或 Wiki 比较，先把证据整理为相同 factKey 的结构化 claims，
                再调用 nexus_conflict_check。不要从自由文本猜测 factKey，也不要自动裁决冲突。
                """.formatted(requirement, projectId, documentId, version);
        return List.of(
                new McpSchema.PromptMessage(McpSchema.Role.USER, new McpSchema.TextContent(text)));
    }

    /** 生成「评估改动影响」提示词：用代码图、影响分析与版本差异评估变更。 */
    private List<McpSchema.PromptMessage> assessChangeImpact(McpSchema.GetPromptRequest request) {
        Map<String, Object> args = request.arguments() == null ? Map.of() : request.arguments();
        String change = String.valueOf(args.getOrDefault("change", ""));
        String projectId = String.valueOf(args.getOrDefault("projectId", ""));
        String version = String.valueOf(args.getOrDefault("version", ""));
        String text = """
                评估改动：%s
                严格限定 projectId=%s、version=%s。
                对符号先调用 nexus_code_graph，再调用 nexus_impact_analysis；对版本差异调用 nexus_version_diff。
                用 nexus_search_requirements 核对受影响行为的原始需求。区分确定关系、推断关系与不可用数据，
                不得把文件级 Git 差异描述成 AST 级结论。
                """.formatted(change, projectId, version);
        return List.of(
                new McpSchema.PromptMessage(McpSchema.Role.USER, new McpSchema.TextContent(text)));
    }
}
