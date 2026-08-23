package com.example.requirementrag.knowledge.multisource.alignment;

import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeClaimRecord;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeStore;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.BuildResult;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.BusinessConcept;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.ConceptAlias;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.ConceptMember;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.CodeSymbolView;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.LoadedCode;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.TruthRole;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 业务概念与版本上下文对齐（Phase 1）。
 *
 * <p>从参数/需求/测试/存疑声明建立业务概念（canonicalKey 稳定生成），把声明作为概念成员；
 * 代码符号通过规范化名称/别名匹配挂到对应概念（truthRole = IMPLEMENTATION）。
 * 概念层是跨源对齐锚点，不替代任何来源事实。
 */
@Service
public class BusinessConceptService {

    private final MultiSourceKnowledgeStore knowledgeStore;
    private final CodeCentricAlignmentStore alignmentStore;
    private final CodeSymbolLoader codeSymbolLoader;
    private final VersionContextService versionContextService;

    public BusinessConceptService(MultiSourceKnowledgeStore knowledgeStore,
                                  CodeCentricAlignmentStore alignmentStore,
                                  CodeSymbolLoader codeSymbolLoader,
                                  VersionContextService versionContextService) {
        this.knowledgeStore = knowledgeStore;
        this.alignmentStore = alignmentStore;
        this.codeSymbolLoader = codeSymbolLoader;
        this.versionContextService = versionContextService;
    }

    /** 重建项目/版本的概念层：概念 + 别名 + 成员（先原子清理该业务版本的旧成员，避免旧 commit 残留）。 */
    public BuildResult build(String projectId, String version) {
        String contextId = versionContextService.resolve(projectId, version, "default").contextId();
        alignmentStore.deleteMembersByVersion(projectId, version);
        List<KnowledgeClaimRecord> claims = knowledgeStore.findClaimsByProjectVersion(projectId, version);
        LoadedCode loaded = codeSymbolLoader.load(projectId);

        Set<String> conceptKeys = new HashSet<>();
        Map<String, List<String>> aliasToConcept = new LinkedHashMap<>();
        int concepts = 0;
        int aliases = 0;
        int members = 0;

        for (KnowledgeClaimRecord claim : claims) {
            if (claim.sourceType() == SourceType.CODE) continue;
            ConceptKey key = conceptFor(claim);
            if (key == null || key.canonicalKey().isBlank()) continue;

            if (conceptKeys.add(key.canonicalKey())) {
                String conceptId = conceptId(projectId, key.canonicalKey());
                alignmentStore.upsertConcept(new BusinessConcept(
                        conceptId, projectId, key.canonicalKey(), key.displayName(), key.type(),
                        key.module(), null, "ACTIVE", null, Instant.now().toString()));
                concepts++;
            }
            BusinessConcept concept = alignmentStore.findConceptByKey(projectId, key.canonicalKey())
                    .orElseThrow(() -> new IllegalStateException("概念未保存: " + key.canonicalKey()));

            String aliasName = claim.subject();
            if (aliasName != null && !aliasName.isBlank()) {
                String aliasId = aliasId(projectId, concept.conceptId(), aliasName, claim.sourceType().name());
                alignmentStore.upsertAlias(new ConceptAlias(
                        aliasId, projectId, concept.conceptId(), aliasName, claim.sourceType().name(),
                        "SOURCE_NAME", 1.0, Instant.now().toString()));
                aliases++;
                aliasToConcept.computeIfAbsent(AlignmentNaming.normalize(aliasName),
                        ignored -> new ArrayList<>()).add(concept.conceptId());
            }

            alignmentStore.upsertMember(new ConceptMember(
                    memberId(projectId, concept.conceptId(), claim.sourceType().name(), claim.claimId(), version),
                    projectId, concept.conceptId(), claim.claimId(), claim.sourceType().name(),
                    truthRole(claim.sourceType()).name(), claim.claimId(), claim.subject(),
                    null, null, null, version, contextId, Instant.now().toString()));
            members++;
        }

        // 代码符号挂到匹配的业务概念（truthRole = IMPLEMENTATION）
        if (loaded.commitSha() != null) {
            for (CodeSymbolView symbol : loaded.symbols()) {
                for (String conceptId : matchConcepts(aliasToConcept, symbol.simpleName())) {
                    alignmentStore.upsertMember(new ConceptMember(
                            memberId(projectId, conceptId, "CODE", symbol.id(), version),
                            projectId, conceptId, null, "CODE",
                            TruthRole.IMPLEMENTATION.name(), symbol.id(), symbol.simpleName(),
                            symbol.projectId(), symbol.commitSha(), codeEvidenceId(symbol),
                            version, contextId, Instant.now().toString()));
                    members++;
                }
            }
        }

        return new BuildResult(concepts, aliases, members, 0, 0);
    }

    /** 查询项目全部概念（含成员与别名）。 */
    public List<BusinessConcept> concepts(String projectId) {
        return alignmentStore.findConcepts(projectId);
    }

    /** 查询某概念的全部成员与别名。 */
    public Map<String, Object> conceptDetail(String projectId, String conceptId) {
        return Map.of(
                "concept", alignmentStore.findConcepts(projectId).stream()
                        .filter(concept -> concept.conceptId().equals(conceptId)).findFirst().orElse(null),
                "members", alignmentStore.findMembers(projectId, conceptId, null),
                "aliases", alignmentStore.findAliases(projectId, conceptId));
    }

    private List<String> matchConcepts(Map<String, List<String>> aliasToConcept, String symbolName) {
        String normalized = AlignmentNaming.normalize(symbolName);
        if (normalized.isBlank()) return List.of();
        Set<String> result = new HashSet<>(aliasToConcept.getOrDefault(normalized, List.of()));
        if (normalized.length() >= 4) {
            for (Map.Entry<String, List<String>> entry : aliasToConcept.entrySet()) {
                if (result.size() >= 5) break;
                if (entry.getKey().equals(normalized)) continue;
                if (AlignmentNaming.namesRelated(entry.getKey(), symbolName)) {
                    result.addAll(entry.getValue());
                }
            }
        }
        return List.copyOf(result);
    }

    private ConceptKey conceptFor(KnowledgeClaimRecord claim) {
        String module = moduleFromFactKey(claim.factKey());
        String subject = safe(claim.subject());
        if (subject.isBlank()) return null;
        return switch (claim.sourceType()) {
            case PARAMETER_TABLE -> new ConceptKey(
                    "param:" + AlignmentNaming.keySegment(module) + "." + AlignmentNaming.keySegment(subject),
                    subject, "PARAMETER", module);
            case REQUIREMENT -> new ConceptKey(
                    "req:" + AlignmentNaming.keySegment(subject), subject, "REQUIREMENT", module);
            case TEST_CASE -> new ConceptKey(
                    "test:" + AlignmentNaming.keySegment(module.isBlank() ? subject : module),
                    subject, "TEST_MODULE", module);
            case TEST_RESULT -> new ConceptKey(
                    "obs:" + AlignmentNaming.keySegment(module.isBlank() ? subject : module),
                    subject, "OBSERVATION", module);
            case DOUBT -> new ConceptKey(
                    "doubt:" + AlignmentNaming.keySegment(module.isBlank() ? "QA存疑" : module),
                    subject, "RISK_AREA", module);
            default -> null;
        };
    }

    private TruthRole truthRole(SourceType sourceType) {
        return switch (sourceType) {
            case PARAMETER_TABLE -> TruthRole.CONFIGURATION;
            case REQUIREMENT -> TruthRole.INTENT;
            case TEST_CASE -> TruthRole.INTENT;
            case TEST_RESULT -> TruthRole.OBSERVATION;
            case DOUBT -> TruthRole.QUESTION;
            case CODE -> TruthRole.IMPLEMENTATION;
            default -> TruthRole.DERIVED;
        };
    }

    private String moduleFromFactKey(String factKey) {
        if (factKey == null) return "";
        String[] parts = factKey.split("\\|");
        return parts.length >= 3 ? safe(parts[2]) : "";
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String conceptId(String projectId, String canonicalKey) {
        return "con:" + sha256(projectId + "|" + canonicalKey).substring(0, 32);
    }

    private String aliasId(String projectId, String conceptId, String alias, String sourceType) {
        return "cal:" + sha256(projectId + "|" + conceptId + "|" + alias + "|" + sourceType).substring(0, 24);
    }

    private String memberId(String projectId, String conceptId, String sourceType, String externalId,
                                String businessVersion) {
        return "cm:" + sha256(projectId + "|" + conceptId + "|" + sourceType + "|" + externalId
                + "|" + businessVersion).substring(0, 24);
    }

    private String codeEvidenceId(CodeSymbolView symbol) {
        return "code:" + symbol.projectId() + ":" + symbol.commitSha() + ":" + symbol.id();
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

    private record ConceptKey(String canonicalKey, String displayName, String type, String module) {
    }
}