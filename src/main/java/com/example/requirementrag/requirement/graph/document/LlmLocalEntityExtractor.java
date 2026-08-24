package com.example.requirementrag.requirement.graph.document;

import com.example.requirementrag.requirement.graph.RequirementGraphProperties;
import com.example.requirementrag.service.GenerationChatOptions;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.EntityMention;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.LocalExtraction;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.LocalRelation;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.LogicalUnit;
import com.example.requirementrag.requirement.graph.document.DocumentLevelModels.SourceAnchor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * LLM 局部实体抽取（Phase 3 局部抽取接入模型）。
 *
 * <p>输入限制为单个逻辑单元文本与结构上下文，输出 JSON 实体和局部关系；
 * 任何失败/非法输出降级为空结果（fail-open），不阻塞构建。
 */
@Component
@ConditionalOnProperty(name = "app.rag.document-level.llm-enabled", havingValue = "true")
public class LlmLocalEntityExtractor implements LocalEntityExtractor {

    private final ChatClient chatClient;
    private final RequirementGraphProperties graphProperties;

    public LlmLocalEntityExtractor(ChatClient chatClient, RequirementGraphProperties graphProperties) {
        this.chatClient = chatClient;
        this.graphProperties = graphProperties;
    }

    @Override
    public LocalExtraction extract(LogicalUnit unit, List<SourceAnchor> unitAnchors) {
        if (unit == null || unit.text() == null || unit.text().isBlank()) {
            return new LocalExtraction(List.of(), List.of());
        }
        try {
            LlmResult result = chatClient.prompt()
                    .system("""
                            你是需求文档局部实体抽取器。输入是单个逻辑单元。
                            只抽取明确出现的实体与实体间关系，实体名必须来自输入文本，不要编造。
                            输出 JSON：{"entities":[{"name":"实体名","type":"REQUIREMENT|CONCEPT|TABLE|RULE"}],
                            "relations":[{"source":"实体名","target":"实体名","type":"REFERENCES|DEPENDS_ON|DEFINES"}]}。
                            没有明确信息时 entities/relations 可为空。
                            """)
                    .user("单元类型：" + unit.unitType() + "\n单元文本：\n" + unit.text())
                    .options(GenerationChatOptions.forModel(resolveModel()))
                    .call()
                    .entity(LlmResult.class);
            if (result == null) return new LocalExtraction(List.of(), List.of());
            List<EntityMention> entities = new ArrayList<>();
            List<LocalRelation> relations = new ArrayList<>();
            if (result.entities() != null) {
                for (LlmEntity e : result.entities()) {
                    if (e == null || e.name() == null || e.name().isBlank()) continue;
                    entities.add(new EntityMention(e.name().trim(), e.type(), unit.sourceAnchorIds().isEmpty() ? null : unit.sourceAnchorIds().get(0), unit.id()));
                }
            }
            if (result.relations() != null) {
                for (LlmRelation r : result.relations()) {
                    if (r == null || blank(r.source()) || blank(r.target())) continue;
                    String source = r.source().trim();
                    String target = r.target().trim();
                    String id = "lr:" + sha256(unit.id() + "|" + source + "|" + target).substring(0, 24);
                    String anchorId = unit.sourceAnchorIds().isEmpty() ? null : unit.sourceAnchorIds().get(0);
                    relations.add(new LocalRelation(id, source, target, r.type() == null ? "RELATED_TO" : r.type().trim(), anchorId, anchorId));
                }
            }
            return new LocalExtraction(List.copyOf(entities), List.copyOf(relations));
        } catch (RuntimeException exception) {
            return new LocalExtraction(List.of(), List.of());
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String resolveModel() {
        return graphProperties.extractionModel() == null || graphProperties.extractionModel().isBlank()
                ? "deepseek-v4-flash" : graphProperties.extractionModel();
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private record LlmResult(List<LlmEntity> entities, List<LlmRelation> relations) {
    }

    private record LlmEntity(String name, String type) {
    }

    private record LlmRelation(String source, String target, String type) {
    }
}