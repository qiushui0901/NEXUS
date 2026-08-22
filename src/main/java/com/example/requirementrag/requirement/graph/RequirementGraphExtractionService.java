package com.example.requirementrag.requirement.graph;

import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.EntityType;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.ExtractedEntity;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.ExtractedRelation;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.ExtractionInput;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.ExtractionResult;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.RelationType;
import com.example.requirementrag.service.GenerationChatOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 借鉴 LightRAG 的实体/关系抽取，但要求每个结果回指当前需求父块原文证据。 */
@Service
@ConditionalOnProperty(prefix = "app.rag.requirement-graph", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public class RequirementGraphExtractionService {
    private static final Logger log = LoggerFactory.getLogger(RequirementGraphExtractionService.class);
    private static final String SYSTEM_PROMPT = """
            你是需求知识抽取器。只从给定需求正文中抽取实体和关系，不补充常识，不推断未写出的事实。
            实体类型只能使用：%s。
            关系类型只能使用：%s。
            每个实体和关系至少提供一个 evidenceQuotes，且必须是输入正文中的连续原文子串。
            confidence 必须是 0 到 1 的数字。只返回 JSON，不要 Markdown，不要解释。
            JSON 结构：
            {"entities":[{"localId":"e1","type":"MODULE","name":"...","aliases":[],"description":"...","evidenceQuotes":["原文"],"confidence":0.9}],
             "relations":[{"sourceLocalId":"e1","type":"AFFECTS_MODULE","targetLocalId":"e2","statement":"...","evidenceQuotes":["原文"],"confidence":0.8}],
             "uncertainties":[]}
            """.formatted(enumNames(EntityType.values()), enumNames(RelationType.values()));

    private final ChatClient chatClient;
    private final RagProperties ragProperties;
    private final RequirementGraphProperties properties;

    public RequirementGraphExtractionService(ChatClient chatClient, RagProperties ragProperties,
                                             RequirementGraphProperties properties) {
        this.chatClient = chatClient;
        this.ragProperties = ragProperties;
        this.properties = properties;
    }

    /** 返回本次构建实际使用的模型，写入快照用于审计。 */
    public String resolvedModel() {
        if (properties.extractionModel() != null && !properties.extractionModel().isBlank()) {
            return properties.extractionModel();
        }
        return ragProperties.llm() == null ? "configured-default"
                : ragProperties.llm().resolvedDevelopmentPlanModel();
    }

    /** 调用 LLM 并执行服务端 Schema/证据校验；不接受模型生成的 evidenceId。 */
    public ExtractionResult extract(ExtractionInput input) {
        if (input == null || input.text() == null || input.text().isBlank()) {
            return new ExtractionResult(List.of(), List.of(), List.of("EMPTY_SOURCE"));
        }
        if (!properties.extractionEnabled()) {
            return new ExtractionResult(List.of(), List.of(), List.of("EXTRACTION_DISABLED"));
        }
        String model = resolvedModel();
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("需求语义图未配置抽取模型");
        }
        try {
            ExtractionResult result = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(userPrompt(input))
                    .options(GenerationChatOptions.forModel(model))
                    .call()
                    .entity(ExtractionResult.class);
            return validate(input, result);
        } catch (RequirementGraphException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.warn("Requirement graph extraction failed source={} parent={}: {}",
                    input.filename(), input.parentId(), exception.getClass().getSimpleName());
            throw new RequirementGraphException("GRAPH_MODEL_UNAVAILABLE", "需求语义图模型暂时不可用", exception);
        }
    }

    /** 可被离线测试复用的确定性输出校验。 */
    public ExtractionResult validate(ExtractionInput input, ExtractionResult result) {
        if (result == null) throw new RequirementGraphException("GRAPH_SCHEMA_INVALID", "需求语义图抽取结果为空");
        if (result.entities().size() > properties.maxEntitiesPerChunk()) {
            throw new RequirementGraphException("GRAPH_SCHEMA_INVALID", "需求语义图实体数量超过上限");
        }
        if (result.relations().size() > properties.maxRelationsPerChunk()) {
            throw new RequirementGraphException("GRAPH_SCHEMA_INVALID", "需求语义图关系数量超过上限");
        }
        Map<String, ExtractedEntity> entities = new LinkedHashMap<>();
        List<ExtractedEntity> normalizedEntities = new ArrayList<>();
        for (ExtractedEntity raw : result.entities()) {
            if (raw == null || raw.localId() == null || raw.localId().isBlank()
                    || raw.name() == null || raw.name().isBlank()) {
                throw new RequirementGraphException("GRAPH_SCHEMA_INVALID", "需求语义图实体缺少 localId 或 name");
            }
            EntityType type = parseEntityType(raw.type());
            double confidence = confidence(raw.confidence());
            List<String> quotes = evidenceQuotes(input.text(), raw.evidenceQuotes());
            ExtractedEntity normalized = new ExtractedEntity(raw.localId().trim(), type.name(),
                    raw.name().trim(), cleanStrings(raw.aliases()),
                    bounded(raw.description(), 1_000), quotes, confidence);
            if (entities.putIfAbsent(normalized.localId(), normalized) != null) {
                throw new RequirementGraphException("GRAPH_SCHEMA_INVALID", "需求语义图实体 localId 重复: " + normalized.localId());
            }
            normalizedEntities.add(normalized);
        }
        List<ExtractedRelation> normalizedRelations = new ArrayList<>();
        Set<String> seenRelations = new HashSet<>();
        for (ExtractedRelation raw : result.relations()) {
            if (raw == null || raw.sourceLocalId() == null || raw.targetLocalId() == null
                    || raw.sourceLocalId().isBlank() || raw.targetLocalId().isBlank()
                    || raw.statement() == null || raw.statement().isBlank()) {
                throw new RequirementGraphException("GRAPH_SCHEMA_INVALID", "需求语义图关系字段不完整");
            }
            if (!entities.containsKey(raw.sourceLocalId()) || !entities.containsKey(raw.targetLocalId())) {
                throw new RequirementGraphException("GRAPH_SCHEMA_INVALID", "需求语义图关系引用不存在的实体");
            }
            RelationType type = parseRelationType(raw.type());
            String relationKey = raw.sourceLocalId().trim() + "|" + type + "|" + raw.targetLocalId().trim();
            if (!seenRelations.add(relationKey)) {
                throw new RequirementGraphException("GRAPH_SCHEMA_INVALID", "需求语义图存在重复关系");
            }
            List<String> quotes = evidenceQuotes(input.text(), raw.evidenceQuotes());
            if (raw.sourceLocalId().equals(raw.targetLocalId())) {
                throw new RequirementGraphException("GRAPH_SCHEMA_INVALID", "需求语义图关系不能是自环");
            }
            normalizedRelations.add(new ExtractedRelation(raw.sourceLocalId().trim(), type.name(),
                    raw.targetLocalId().trim(), bounded(raw.statement(), 1_000),
                    quotes, confidence(raw.confidence()),
                    bounded(raw.condition(), 500), bounded(raw.scenario(), 500)));
        }
        return new ExtractionResult(normalizedEntities, normalizedRelations,
                cleanStrings(result.uncertainties()));
    }

    private String userPrompt(ExtractionInput input) {
        return """
                来源文件：%s
                父块：%s
                父块序号：%d
                章节路径：%s
                标题：%s
                内容哈希：%s
                需求正文：
                ---
                %s
                ---
                """.formatted(safe(input.filename()), safe(input.parentId()), input.parentOrder(),
                safe(input.sectionPath()), safe(input.heading()), safe(input.contentHash()), input.text());
    }

    private List<String> evidenceQuotes(String text, List<String> quotes) {
        List<String> valid = cleanStrings(quotes).stream()
                .filter(text::contains)
                .map(value -> bounded(value, 500))
                .distinct()
                .limit(3)
                .toList();
        if (valid.isEmpty()) throw new RequirementGraphException("GRAPH_EVIDENCE_INVALID", "需求语义图结果缺少可回查原文证据");
        return valid;
    }

    private EntityType parseEntityType(String value) {
        try {
            return EntityType.valueOf(normalizeEnum(value));
        } catch (RuntimeException exception) {
            throw new RequirementGraphException("GRAPH_SCHEMA_INVALID", "未知需求实体类型: " + value, exception);
        }
    }

    private RelationType parseRelationType(String value) {
        try {
            return RelationType.valueOf(normalizeEnum(value));
        } catch (RuntimeException exception) {
            throw new RequirementGraphException("GRAPH_SCHEMA_INVALID", "未知需求关系类型: " + value, exception);
        }
    }

    private String normalizeEnum(String value) {
        return safe(value).toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    private double confidence(double value) {
        if (Double.isNaN(value) || value < 0 || value > 1) {
            throw new RequirementGraphException("GRAPH_SCHEMA_INVALID", "需求语义图 confidence 必须在 0 到 1 之间");
        }
        return value;
    }

    private List<String> cleanStrings(List<String> values) {
        if (values == null) return List.of();
        return values.stream().filter(value -> value != null && !value.isBlank())
                .map(String::trim).distinct().limit(10).toList();
    }

    private String bounded(String value, int limit) {
        String normalized = safe(value);
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String enumNames(Enum<?>[] values) {
        return java.util.Arrays.stream(values).map(Enum::name).reduce((left, right) -> left + ", " + right).orElse("");
    }
}
