package com.example.requirementrag.mcp;

import org.springframework.ai.mcp.annotation.McpArg;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 面向 MCP 客户端（如 Codex、Cursor）的可复用、作用域安全的提示词模板：
 * 每个提示词都强制限定 projectId/version 等作用域，并规定按序调用对应工具、
 * 以工具返回的 evidenceId 作为引用，禁止跨作用域或派生文本替代原始证据。
 */
@Component
@ConditionalOnProperty(prefix = "app.mcp", name = "enabled", havingValue = "true", matchIfMissing = true)
public class NexusMcpPrompts {

    /**
     * 生成「实现某需求」提示词：指导先检索需求证据，再生成开发计划，
     * 并对相关符号做代码图遍历与影响分析，所有结论必须引用证据 ID。
     *
     * @param requirement 要实现的需求描述
     * @param projectId   NEXUS 项目 ID
     * @param version     需求版本
     * @return 拼接好的提示词文本
     */
    @McpPrompt(name = "nexus_implement_requirement", title = "实现某需求",
            description = "基于版本化需求证据、代码检索和影响分析生成可执行开发方案。")
    public String implementRequirement(
            @McpArg(name = "requirement", description = "要实现的需求", required = true) String requirement,
            @McpArg(name = "projectId", description = "NEXUS 项目 ID", required = true) String projectId,
            @McpArg(name = "version", description = "需求版本", required = true) String version) {
        return """
                你要实现需求：%s
                严格限定 projectId=%s、version=%s。
                先调用 nexus_search_requirements 获取需求证据，再调用 nexus_development_plan。
                对计划涉及的符号调用 nexus_code_graph 与 nexus_impact_analysis；必要时用 nexus_get_source 核对。
                所有结论引用工具返回的 evidenceId。若任何结果出现 scope 不一致或降级 warning，先明确标注，
                不得用其他项目、其他版本或 Wiki 派生文本替代原始证据。
                """.formatted(requirement, projectId, version);
    }

    /**
     * 生成「评审某需求」提示词：检索指定版本需求并生成存疑清单；
     * 跨需求/代码/测试/Wiki 比较时要求整理为同一 factKey 的结构化 claims，禁止自由文本猜测。
     *
     * @param requirement 评审主题或模块
     * @param projectId   NEXUS 项目 ID
     * @param documentId  需求文档 ID
     * @param version     需求版本
     * @return 拼接好的提示词文本
     */
    @McpPrompt(name = "nexus_review_requirement", title = "评审某需求",
            description = "检索指定版本需求并生成存疑和结构化冲突检查步骤。")
    public String reviewRequirement(
            @McpArg(name = "requirement", description = "评审主题或模块", required = true) String requirement,
            @McpArg(name = "projectId", description = "NEXUS 项目 ID", required = true) String projectId,
            @McpArg(name = "documentId", description = "需求文档 ID", required = true) String documentId,
            @McpArg(name = "version", description = "需求版本", required = true) String version) {
        return """
                评审主题：%s
                严格限定 projectId=%s、documentId=%s、version=%s。
                先调用 nexus_search_requirements，再调用 nexus_review_doubts。
                若需要跨需求、代码、测试或 Wiki 比较，先把证据整理为相同 factKey 的结构化 claims，
                再调用 nexus_conflict_check。不要从自由文本猜测 factKey，也不要自动裁决冲突。
                """.formatted(requirement, projectId, documentId, version);
    }

    /**
     * 生成「评估改动影响」提示词：用代码图、影响分析与版本差异评估变更，
     * 并用需求检索核对受影响行为的原始需求，区分确定/推断关系与不可用数据。
     *
     * @param change    符号或改动说明
     * @param projectId NEXUS 项目 ID
     * @param version   目标业务版本
     * @return 拼接好的提示词文本
     */
    @McpPrompt(name = "nexus_assess_change_impact", title = "评估改动影响",
            description = "用代码图、提交差异和版本知识评估变更影响。")
    public String assessChangeImpact(
            @McpArg(name = "change", description = "符号或改动说明", required = true) String change,
            @McpArg(name = "projectId", description = "NEXUS 项目 ID", required = true) String projectId,
            @McpArg(name = "version", description = "目标业务版本", required = true) String version) {
        return """
                评估改动：%s
                严格限定 projectId=%s、version=%s。
                对符号先调用 nexus_code_graph，再调用 nexus_impact_analysis；对版本差异调用 nexus_version_diff。
                用 nexus_search_requirements 核对受影响行为的原始需求。区分确定关系、推断关系与不可用数据，
                不得把文件级 Git 差异描述成 AST 级结论。
                """.formatted(change, projectId, version);
    }
}
