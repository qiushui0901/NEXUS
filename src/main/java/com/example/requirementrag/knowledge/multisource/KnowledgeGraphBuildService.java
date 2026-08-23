package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeClaimRecord;
import com.example.requirementrag.knowledge.multisource.KnowledgeGraphModels.KnowledgeEntity;
import com.example.requirementrag.knowledge.multisource.KnowledgeGraphModels.KnowledgeEntityRelation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 跨源总实体关系图构建服务（规则 + 代码 + 可选 LLM）：
 * 聚合 PRD/DATA/QA/CASE 的统一 Claim 为模块级实体，并按规范化名称匹配生成确定性关系；
 * 代码来源通过 {@link CodeEntitySource} 并入；LLM 通过 {@link LlmGraphExtractor} 补充语义关系。
 *
 * <p>通过 {@code KnowledgeGraphBuildConfiguration} 注册为 Spring Bean（可选装配代码源与 LLM）。
 */
public class KnowledgeGraphBuildService {

    /** 代码实体来源：从代码知识库/符号图投影为实体输入。 */
    public interface CodeEntitySource {
        List<CodeEntityInput> load(String projectId, String version);
    }

    /** LLM 跨源语义关系抽取器。 */
    public interface LlmGraphExtractor {
        List<SemanticEdge> extract(String projectId, String version, List<KnowledgeEntity> entities);
    }

    /** 代码实体输入。 */
    public record CodeEntityInput(String name, String entityType, String summary, String evidenceId) {
    }

    /** LLM 语义边。 */
    public record SemanticEdge(String sourceName, String targetName, String relationType, String reason) {
    }

    private final MultiSourceKnowledgeStore store;
    private CodeEntitySource codeEntitySource = (projectId, version) -> List.of();
    private LlmGraphExtractor llmGraphExtractor = (projectId, version, entities) -> List.of();
    private boolean llmEnabled = false;

    public KnowledgeGraphBuildService(MultiSourceKnowledgeStore store) {
        this.store = store;
    }

    /** 接入代码实体来源（可选）。 */
    public KnowledgeGraphBuildService withCodeEntitySource(CodeEntitySource source) {
        this.codeEntitySource = source == null ? (projectId, version) -> List.of() : source;
        return this;
    }

    /** 接入 LLM 语义关系抽取（可选）。 */
    public KnowledgeGraphBuildService withLlmGraphExtractor(LlmGraphExtractor extractor) {
        this.llmGraphExtractor = extractor == null ? (projectId, version, entities) -> List.of() : extractor;
        return this;
    }

    /** 开启 LLM 语义关系补充。 */
    public KnowledgeGraphBuildService withLlmEnabled(boolean enabled) {
        this.llmEnabled = enabled;
        return this;
    }

    /** 构建并持久化项目/版本的总实体关系图，返回实体数与关系数。 */
    public GraphBuildResult build(String projectId, String version) {
        store.deleteGraph(projectId, version);
        List<KnowledgeClaimRecord> claims = store.findClaimsByProjectVersion(projectId, version);
        Map<String, String> documentNames = documentNames(claims);
        Map<String, KnowledgeEntity> entities = new LinkedHashMap<>();

        // REQUIREMENT 实体：按 subject（PRD 功能名）
        for (KnowledgeClaimRecord claim : claims) {
            if (claim.sourceType() != SourceType.REQUIREMENT) continue;
            String name = safe(claim.subject());
            if (name.isBlank()) continue;
            String key = entityKey(projectId, version, "REQUIREMENT", name);
            entities.computeIfAbsent(key, ignored -> new KnowledgeEntity(
                    key, projectId, version, name, normalize(name), "FEATURE",
                    SourceType.REQUIREMENT, claim.objectValue() == null ? claim.subject() : claim.objectValue(),
                    firstEvidence(claim), List.of(claim.claimId()), null, null));
        }

        // PARAMETER_TABLE 实体：优先按文件/资料名（sheet 常为 Sheet1，不可靠）
        for (KnowledgeClaimRecord claim : claims) {
            if (claim.sourceType() != SourceType.PARAMETER_TABLE) continue;
            addModuleEntity(entities, projectId, version, claim, "CONFIG_TABLE", SourceType.PARAMETER_TABLE,
                    documentNames.get(claim.documentVersionId()));
        }

        // TEST_CASE 实体：按 factKey module，缺失回退资料名
        for (KnowledgeClaimRecord claim : claims) {
            if (claim.sourceType() != SourceType.TEST_CASE) continue;
            addModuleEntity(entities, projectId, version, claim, "TEST_MODULE", SourceType.TEST_CASE,
                    documentNames.get(claim.documentVersionId()));
        }

        // DOUBT 实体：按 factKey 的 module，缺失统一归入 QA存疑
        for (KnowledgeClaimRecord claim : claims) {
            if (claim.sourceType() != SourceType.DOUBT) continue;
            String moduleFromFact = moduleFromFactKey(claim.factKey());
            String name = moduleFromFact.isBlank() ? "QA存疑" : moduleFromFact;
            String key = entityKey(projectId, version, "DOUBT", name);
            KnowledgeEntity existing = entities.get(key);
            if (existing == null) {
                entities.put(key, new KnowledgeEntity(key, projectId, version, name, normalize(name),
                        "RISK_AREA", SourceType.DOUBT, claim.objectValue() == null ? claim.subject() : claim.objectValue(),
                        firstEvidence(claim), List.of(claim.claimId()), null, null));
            } else {
                entities.put(key, merge(existing, claim.claimId()));
            }
        }

        // CODE 实体
        for (CodeEntityInput code : codeEntitySource.load(projectId, version)) {
            String key = entityKey(projectId, version, "CODE", code.name());
            entities.put(key, new KnowledgeEntity(key, projectId, version, code.name(), normalize(code.name()),
                    code.entityType(), SourceType.CODE, code.summary(), code.evidenceId(), List.of(), null, null));
        }

        List<KnowledgeEntity> entityList = List.copyOf(entities.values());
        for (KnowledgeEntity entity : entityList) {
            store.saveEntity(entity);
        }

        List<KnowledgeEntityRelation> relations = ruleRelations(projectId, version, entityList);

        if (llmEnabled) {
            for (SemanticEdge edge : llmGraphExtractor.extract(projectId, version, entityList)) {
                KnowledgeEntity source = findByName(entities, edge.sourceName());
                KnowledgeEntity target = findByName(entities, edge.targetName());
                if (source == null || target == null || source.entityId().equals(target.entityId())) {
                    continue;
                }
                String relationId = "grel:" + sha256(projectId + "|" + version + "|"
                        + source.entityId() + "|" + target.entityId() + "|" + edge.relationType()).substring(0, 32);
                relations.add(new KnowledgeEntityRelation(relationId, projectId, version,
                        source.entityId(), target.entityId(), edge.relationType(), "LLM_CONFIRMED", 0.8,
                        "LLM", List.of(), null, null));
            }
        }

        for (KnowledgeEntityRelation relation : relations) {
            store.saveEntityRelation(relation);
        }
        return new GraphBuildResult(entityList.size(), relations.size());
    }

    private void addModuleEntity(Map<String, KnowledgeEntity> entities, String projectId, String version,
                                 KnowledgeClaimRecord claim, String entityType, SourceType sourceType,
                                 String documentName) {
        String moduleFromFact = moduleFromFactKey(claim.factKey());
        String fallback = !safe(documentName).isBlank() ? documentName
                : (sourceType == SourceType.TEST_CASE ? "测试用例"
                : sourceType == SourceType.PARAMETER_TABLE ? "配置表" : "未知");
        // 配置表优先使用文件/资料名（sheet 常为 Sheet1 等通用名）；测试模块保留 factKey module
        String name;
        if (sourceType == SourceType.PARAMETER_TABLE && !safe(documentName).isBlank()) {
            name = documentName;
        } else {
            name = moduleFromFact.isBlank() ? fallback : moduleFromFact;
        }
        if (name.isBlank()) return;
        String key = entityKey(projectId, version, sourceType.name(), name);
        KnowledgeEntity existing = entities.get(key);
        if (existing == null) {
            entities.put(key, new KnowledgeEntity(key, projectId, version, name, normalize(name),
                    entityType, sourceType, claim.objectValue() == null ? name : claim.objectValue(),
                    firstEvidence(claim), List.of(claim.claimId()), null, null));
        } else {
            entities.put(key, merge(existing, claim.claimId()));
        }
    }

    private Map<String, String> documentNames(List<KnowledgeClaimRecord> claims) {
        Map<String, String> names = new LinkedHashMap<>();
        for (KnowledgeClaimRecord claim : claims) {
            String dv = claim.documentVersionId();
            if (dv == null || dv.isBlank() || names.containsKey(dv)) continue;
            store.findDocumentVersionById(dv)
                    .flatMap(version -> store.findDocumentById(version.documentId()))
                    .ifPresent(document -> names.put(dv, document.logicalName()));
        }
        return names;
    }

    private KnowledgeEntity merge(KnowledgeEntity existing, String claimId) {
        List<String> ids = new ArrayList<>(existing.sourceClaimIds());
        if (!ids.contains(claimId)) ids.add(claimId);
        return new KnowledgeEntity(existing.entityId(), existing.projectId(), existing.version(),
                existing.name(), existing.normalizedName(), existing.entityType(), existing.sourceType(),
                existing.summary(), existing.evidenceId() == null ? "" : existing.evidenceId(),
                ids, existing.createdAt(), java.time.Instant.now().toString());
    }

    private List<KnowledgeEntityRelation> ruleRelations(String projectId, String version,
                                                        List<KnowledgeEntity> entities) {
        List<KnowledgeEntityRelation> relations = new ArrayList<>();
        List<KnowledgeEntity> requirements = entities.stream()
                .filter(e -> e.sourceType() == SourceType.REQUIREMENT).toList();
        for (KnowledgeEntity target : requirements) {
            for (KnowledgeEntity source : entities) {
                if (source.entityId().equals(target.entityId())) continue;
                String type = switch (source.sourceType()) {
                    case PARAMETER_TABLE -> "SUPPORTS";
                    case TEST_CASE -> "VERIFIES";
                    case DOUBT -> "RAISES_DOUBT";
                    case CODE -> "IMPLEMENTED_BY";
                    default -> null;
                };
                if (type == null || !namesRelated(source.normalizedName(), target.normalizedName())) {
                    continue;
                }
                String relationId = "grel:" + sha256(projectId + "|" + version + "|"
                        + source.entityId() + "|" + target.entityId() + "|" + type).substring(0, 32);
                relations.add(new KnowledgeEntityRelation(relationId, projectId, version,
                        source.entityId(), target.entityId(), type, "RULE_PROPOSED", 0.7,
                        "RULE", List.of(), null, null));
            }
        }
        return relations;
    }

    /** 代码命名里常见的通用词，不做跨源关系匹配（避免包路径/参数类名噪声）。 */
    private static final Set<String> CODE_STOPWORDS = Set.of(
            "index", "param", "params", "request", "response", "req", "resp",
            "config", "util", "utils", "helper", "constants", "enum", "exception",
            "test", "tests", "info", "data", "model", "dto", "vo", "entity");

    private boolean namesRelated(String left, String right) {
        if (left.isBlank() || right.isBlank()) return false;
        if (left.equals(right)) return true;
        if (!(left.contains(right) || right.contains(left))) return false;
        String shorter = left.length() <= right.length() ? left : right;
        return !CODE_STOPWORDS.contains(shorter);
    }

    private KnowledgeEntity findByName(Map<String, KnowledgeEntity> entities, String name) {
        String normalized = normalize(name);
        for (KnowledgeEntity entity : entities.values()) {
            if (entity.normalizedName().equals(normalized) || entity.name().equals(name)) {
                return entity;
            }
        }
        return null;
    }

    private String moduleFromFactKey(String factKey) {
        if (factKey == null) return "";
        String[] parts = factKey.split("\\|");
        return parts.length >= 3 ? parts[2] : "";
    }

    private String firstEvidence(KnowledgeClaimRecord claim) {
        List<String> evidence = store.findEvidenceIdsByClaimId(claim.claimId());
        return evidence.isEmpty() ? "" : evidence.get(0);
    }

    private String entityKey(String projectId, String version, String source, String name) {
        return "ent:" + sha256(projectId + "|" + version + "|" + source + "|" + normalize(name)).substring(0, 32);
    }

    private String normalize(String value) {
        return safe(value).toLowerCase(Locale.ROOT).replaceAll("[\\s|｜:：（）()\\[\\]【】、，,。.;；/\\\\]+", "");
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String sha256(String value) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    /** 构建结果汇总。 */
    public record GraphBuildResult(int entities, int relations) {
    }
}