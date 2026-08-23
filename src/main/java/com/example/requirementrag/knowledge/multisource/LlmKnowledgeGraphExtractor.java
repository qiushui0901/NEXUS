package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.knowledge.multisource.KnowledgeGraphBuildService.SemanticEdge;
import com.example.requirementrag.knowledge.multisource.KnowledgeGraphModels.KnowledgeEntity;
import com.example.requirementrag.requirement.graph.RequirementGraphProperties;
import com.example.requirementrag.service.GenerationChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * LLM 跨源总实体关系语义边抽取：把实体清单交给 LLM，返回语义关系边。
 *
 * <p>默认使用需求语义图抽取模型（`REQUIREMENT_GRAPH_EXTRACTION_MODEL`，当前 deepseek-v4-flash）。
 * 实体过多时按上限裁剪避免超长输入；任何失败/非法输出降级为空列表。
 */
@Component
public class LlmKnowledgeGraphExtractor implements KnowledgeGraphBuildService.LlmGraphExtractor {

    private static final int MAX_ENTITIES = 500;

    private final ChatClient chatClient;
    private final RequirementGraphProperties graphProperties;

    public LlmKnowledgeGraphExtractor(ChatClient chatClient, RequirementGraphProperties graphProperties) {
        this.chatClient = chatClient;
        this.graphProperties = graphProperties;
    }

    @Override
    public List<KnowledgeGraphBuildService.SemanticEdge> extract(String projectId, String version,
                                                                 List<KnowledgeEntity> entities) {
        List<KnowledgeEntity> input = entities.size() > MAX_ENTITIES
                ? entities.subList(0, MAX_ENTITIES) : entities;
        if (input.isEmpty()) {
            return List.of();
        }
        try {
            LlmEdgeResult result = chatClient.prompt()
                    .system("""
                            你是跨源实体关系抽取器。基于实体清单，找出明确的跨源语义关系（例如“英雄系统”和“ImmortalHero”配置表相关、
                            测试模块“英雄”验证功能“英雄”）。输出 JSON：{"edges":[{"source":"实体名","target":"实体名",
                            "relationType":"SUPPORTS|VERIFIES|RAISES_DOUBT|IMPLEMENTED_BY|SEMANTIC_RELATED","reason":"简短理由"}]}。
                            只输出实体清单中存在的实体名，不要编造。没有明确关系时 edges 可以为空。
                            """)
                    .user("项目：" + projectId + " 版本：" + version + "\n实体：\n" + formatEntities(input))
                    .options(GenerationChatOptions.forModel(resolveModel()))
                    .call()
                    .entity(LlmEdgeResult.class);
            if (result == null || result.edges() == null) {
                return List.of();
            }
            List<KnowledgeGraphBuildService.SemanticEdge> edges = new ArrayList<>();
            for (LlmEdge edge : result.edges()) {
                if (edge == null || edge.source() == null || edge.target() == null
                        || edge.relationType() == null || edge.relationType().isBlank()) {
                    continue;
                }
                edges.add(new KnowledgeGraphBuildService.SemanticEdge(
                        edge.source().trim(), edge.target().trim(),
                        edge.relationType().trim().toUpperCase(Locale.ROOT), edge.reason()));
            }
            return edges;
        }
        catch (RuntimeException exception) {
            return List.of();
        }
    }

    private String formatEntities(List<KnowledgeEntity> entities) {
        StringBuilder builder = new StringBuilder();
        for (KnowledgeEntity entity : entities) {
            builder.append("- ").append(entity.name()).append(" [")
                    .append(entity.sourceType()).append('/').append(entity.entityType()).append("]\n");
        }
        return builder.toString();
    }

    private String resolveModel() {
        return graphProperties.extractionModel() == null || graphProperties.extractionModel().isBlank()
                ? "deepseek-v4-flash" : graphProperties.extractionModel();
    }

    /** LLM 原始应答。 */
    private record LlmEdgeResult(List<LlmEdge> edges) {
    }

    private record LlmEdge(String source, String target, String relationType, String reason) {
    }
}