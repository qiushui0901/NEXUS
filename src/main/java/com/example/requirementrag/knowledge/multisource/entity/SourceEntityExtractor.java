package com.example.requirementrag.knowledge.multisource.entity;

import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeClaimRecord;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeStore;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricAlignmentStore;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.AlignmentRelation;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.ConceptAlias;
import com.example.requirementrag.knowledge.multisource.alignment.VersionContextService;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.AliasOrigin;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.SourceExtractionRaw;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.SourceEntityRaw;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.SourceRelationRaw;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 来源级实体提取器（dev md §6）：确定性事实已由导入器先落库（LLM 失败不丢来源事实）；
 * 本服务仅让 LLM 提议额外别名与关系候选——别名 origin=LLM_PROPOSED/status=PROPOSED（不参与确认匹配），
 * 关系 matchMethod=LLM_PROPOSED/status=PROPOSED。**不写知识事实**（LLM 值不覆盖来源原始值）。
 */
@Service
public class SourceEntityExtractor {

    private final MultiSourceKnowledgeStore knowledgeStore;
    private final CodeCentricAlignmentStore alignmentStore;
    private final EntityExtractionProperties properties;
    private final EntityLlmAssistant llm;
    private final VersionContextService versionContextService;

    public SourceEntityExtractor(MultiSourceKnowledgeStore knowledgeStore,
                                 CodeCentricAlignmentStore alignmentStore,
                                 EntityExtractionProperties properties,
                                 EntityLlmAssistant llm) {
        this(knowledgeStore, alignmentStore, properties, llm, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SourceEntityExtractor(MultiSourceKnowledgeStore knowledgeStore,
                                 CodeCentricAlignmentStore alignmentStore,
                                 EntityExtractionProperties properties,
                                 EntityLlmAssistant llm,
                                 VersionContextService versionContextService) {
        this.knowledgeStore = knowledgeStore;
        this.alignmentStore = alignmentStore;
        this.properties = properties;
        this.llm = llm;
        this.versionContextService = versionContextService;
    }

    /** 提取结果：提议别名数 / 提议关系数 / 告警（含降级与未映射）。 */
    public record ExtractionOutcome(int proposedAliases, int proposedRelations, List<String> warnings) {
    }

    /** 对指定业务版本的有界 Claim 集执行 LLM 提议（不改变任何权威事实）。 */
    public ExtractionOutcome extract(String projectId, String businessVersion) {
        List<KnowledgeClaimRecord> claims =
                knowledgeStore.findPublishedClaimsByProjectVersionAll(projectId, businessVersion);
        int batchSize = properties.sourceBatchSize();
        if (claims.size() > batchSize) {
            claims = new ArrayList<>(claims.subList(0, batchSize));
        }
        if (claims.isEmpty()) {
            return new ExtractionOutcome(0, 0, List.of());
        }
        Set<String> inputClaimIds = new HashSet<>();
        List<String> lines = new ArrayList<>();
        for (KnowledgeClaimRecord claim : claims) {
            inputClaimIds.add(claim.claimId());
            lines.add(claim.claimId() + "|" + safe(claim.subject()) + "|"
                    + safe(claim.predicate()) + "|" + safe(claim.objectValue())
                    + "|" + moduleOf(claim));
        }

        Optional<SourceExtractionRaw> raw = llm.extractFromSource(projectId, businessVersion, lines, inputClaimIds);
        if (raw.isEmpty()) {
            return new ExtractionOutcome(0, 0,
                    List.of(llm.available() ? "ENTITY_EXTRACTION_REJECTED" : "ENTITY_LLM_UNAVAILABLE"));
        }
        return apply(projectId, businessVersion, raw.get(), inputClaimIds);
    }

    /** 校验通过后落地候选：别名 PROPOSED + 关系 PROPOSED。 */
    private ExtractionOutcome apply(String projectId, String businessVersion, SourceExtractionRaw raw,
                                    Set<String> inputClaimIds) {
        int proposedAliases = 0;
        List<String> warnings = new ArrayList<>();
        for (SourceEntityRaw entity : raw.entities()) {
            List<String> conceptIds = resolveConceptIds(projectId, entity.name(), entity.aliases());
            if (conceptIds.isEmpty()) {
                warnings.add("ENTITY_UNMAPPED:" + safe(entity.name()));
                continue;
            }
            for (String conceptId : conceptIds) {
                Set<String> existing = new HashSet<>();
                for (ConceptAlias alias : alignmentStore.findAliases(projectId, conceptId)) {
                    existing.add(alias.alias());
                }
                for (String alias : entity.aliases()) {
                    if (alias == null || alias.isBlank() || existing.contains(alias)) {
                        continue;
                    }
                    alignmentStore.upsertAlias(new ConceptAlias(
                            aliasId(projectId, conceptId, alias), projectId, conceptId, alias, "LLM",
                            "LLM_PROPOSED", entity.confidence(), Instant.now().toString(),
                            AliasOrigin.LLM_PROPOSED.name(), "PROPOSED",
                            evidenceOf(projectId, conceptId)));
                    existing.add(alias);
                    proposedAliases++;
                }
            }
        }

        int proposedRelations = proposeRelations(projectId, businessVersion, raw, warnings, inputClaimIds);
        return new ExtractionOutcome(proposedAliases, proposedRelations, warnings);
    }

    /** 关系候选：两端解析到**当前业务版本**的 Claim 成员后保存 PROPOSED 关系（evidence 取源端第一条）。 */
    private int proposeRelations(String projectId, String businessVersion, SourceExtractionRaw raw,
                                 List<String> warnings, Set<String> inputClaimIds) {
        int count = 0;
        for (SourceRelationRaw relation : raw.relations()) {
            Optional<String> sourceClaim = memberClaimFor(projectId, businessVersion, relation.sourceEntityName());
            Optional<String> targetClaim = memberClaimFor(projectId, businessVersion, relation.targetName());
            // 硬校验：两端必须属于当前输入版本（关系版本/Evidence/端点事实必须一致）
            if (sourceClaim.isEmpty() || targetClaim.isEmpty()
                    || !inputClaimIds.contains(sourceClaim.get())
                    || !inputClaimIds.contains(targetClaim.get())) {
                warnings.add("RELATION_UNMAPPED:" + safe(relation.sourceEntityName())
                        + "->" + safe(relation.targetName()));
                continue;
            }
            String evidenceId = evidenceOfClaim(projectId, sourceClaim.get());
            String contextId = versionContextId(projectId, businessVersion);
            String relationId = relationId(projectId, businessVersion, sourceClaim.get(), targetClaim.get(),
                    relation.relationType());
            alignmentStore.saveAlignmentRelation(new AlignmentRelation(
                    relationId, projectId, businessVersion,
                    contextId,
                    sourceClaim.get(), null, "CLAIM",
                    targetClaim.get(), null, "CLAIM",
                    relation.relationType(), "LLM_PROPOSED", "PROPOSED",
                    relation.confidence(), evidenceId,
                    contextId, contextId,
                    "LLM 提议关系: " + safe(relation.sourceEntityName()) + " " + relation.relationType()
                            + " " + safe(relation.targetName()),
                    null, null));
            count++;
        }
        return count;
    }

    /** 解析实体名到概念：已确认别名 → 成员名。 */
    private List<String> resolveConceptIds(String projectId, String name, List<String> aliases) {
        Set<String> ids = new LinkedHashSet<>();
        if (name != null && !name.isBlank()) {
            ids.addAll(alignmentStore.findConceptIdsByAlias(projectId, name));
        }
        if (aliases != null) {
            for (String alias : aliases) {
                if (alias != null && !alias.isBlank()) {
                    ids.addAll(alignmentStore.findConceptIdsByAlias(projectId, alias));
                }
            }
        }
        return List.copyOf(ids);
    }

    /** 概念下**指定业务版本**内第一个带 claimId 的成员作为关系端点（不跨版本取成员）。 */
    private Optional<String> memberClaimFor(String projectId, String businessVersion, String entityName) {
        if (entityName == null || entityName.isBlank()) return Optional.empty();
        for (String conceptId : alignmentStore.findConceptIdsByAlias(projectId, entityName)) {
            for (var member : alignmentStore.findMembers(projectId, conceptId, businessVersion)) {
                if (member.claimId() != null && !member.claimId().isBlank()) {
                    return Optional.of(member.claimId());
                }
            }
        }
        return Optional.empty();
    }

    private String evidenceOfClaim(String projectId, String claimId) {
        return knowledgeStore.findPublishedEvidenceIdForClaim(projectId, claimId)
                .orElse(null);
    }

    private String evidenceOf(String projectId, String conceptId) {
        var members = alignmentStore.findMembers(projectId, conceptId, null);
        for (var member : members) {
            if (member.evidenceId() != null && !member.evidenceId().isBlank()) {
                return member.evidenceId();
            }
        }
        return null;
    }

    private String versionContextId(String projectId, String businessVersion) {
        if (versionContextService != null) {
            return versionContextService.resolve(projectId, businessVersion, "default").contextId();
        }
        // Compatibility-only constructor is used by isolated unit tests; production always resolves a persisted context.
        return "vc:" + projectId + ":" + businessVersion;
    }

    private String moduleOf(KnowledgeClaimRecord claim) {
        String factKey = claim.factKey();
        if (factKey == null) return "";
        String[] parts = factKey.split("\\|");
        return parts.length >= 3 ? parts[2] : "";
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String aliasId(String projectId, String conceptId, String alias) {
        return "calp:" + sha256(projectId + "|" + conceptId + "|" + alias).substring(0, 24);
    }

    private String relationId(String projectId, String version, String source, String target, String type) {
        return "relp:" + sha256(projectId + "|" + version + "|" + source + "|" + target + "|" + type)
                .substring(0, 24);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(
                    digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}