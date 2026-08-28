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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
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
    private static final Logger log = LoggerFactory.getLogger(BusinessConceptService.class);

    /** 包含匹配仅在索引小时启用，避免 8.5k 代码符号 × 数十万别名的全量扫描。 */
    private static final int CONTAINS_MATCH_MAX_INDEX_SIZE = 2000;

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

    /** 重建项目/版本的概念层（版本级增量）：概念 + 别名 + 成员。
     * 只替换该业务版本的成员（先原子清理该业务版本的旧成员，避免旧 commit 残留），
     * 历史版本成员不受影响。仅处理已发布（PUBLISHED）Claim，DRAFT 不进入实体层。
     * 当前代码符号只挂载到最新已发布业务版本（当前实现事实，不冒充历史版本的代码）。 */
    public BuildResult build(String projectId, String version) {
        List<String> publishedVersions = knowledgeStore.findPublishedBusinessVersions(projectId);
        if (version == null || version.isBlank() || !publishedVersions.contains(version)) {
            throw new IllegalArgumentException("只能构建已发布业务版本: " + projectId + "|" + version);
        }
        boolean isLatest = version.equals(publishedVersions.get(publishedVersions.size() - 1));
        String contextId = isLatest
                ? versionContextService.resolve(projectId, version, "default").contextId()
                : versionContextService.resolveHistorical(projectId, version, "default").contextId();
        List<KnowledgeClaimRecord> claims =
                knowledgeStore.findPublishedClaimsByProjectVersionAll(projectId, version);
        VersionBuild build = buildForVersion(projectId, version, contextId, claims,
                isLatest ? codeSymbolLoader.load(projectId) : LoadedCode.empty(), true);
        alignmentStore.replaceProjectIndex(projectId,
                Map.of(version, build.members()), build.concepts(), build.aliases());
        return build.result();
    }

    /** 项目级重建：枚举全部已发布业务版本 + 当前代码符号，跨版本合并实体。
     *
     * <p>概念/别名按 {@code con:sha256(projectId|canonicalKey)} 跨版本共享；成员按版本增量 upsert。
     * 不删除任何版本的历史成员（每版本仅清理并重建该版本自身成员）。
     * 仅使用 PUBLISHED 版本（DRAFT 不进入实体层，避免未发布数据泄漏到公开读取与当前事实）；
     * 当前代码符号只挂到最新业务版本（当前实现事实，不冒充历史版本的代码）。
     */
    public BuildResult buildProject(String projectId) {
        List<String> versions = knowledgeStore.findPublishedBusinessVersions(projectId);
        if (versions.isEmpty()) {
            alignmentStore.clearProjectMembers(projectId);
            return new BuildResult(0, 0, 0, 0, 0);
        }
        LoadedCode loaded = codeSymbolLoader.load(projectId);
        String latestVersion = versions.get(versions.size() - 1);
        int concepts = 0;
        int aliases = 0;
        int members = 0;
        List<BusinessConcept> conceptBatch = new ArrayList<>();
        List<ConceptAlias> aliasBatch = new ArrayList<>();
        Map<String, List<ConceptMember>> membersByVersion = new LinkedHashMap<>();
        for (String version : versions) {
            String contextId = version.equals(latestVersion)
                    ? versionContextService.resolve(projectId, version, "default").contextId()
                    : versionContextService.resolveHistorical(projectId, version, "default").contextId();
            List<KnowledgeClaimRecord> claims =
                    knowledgeStore.findPublishedClaimsByProjectVersionAll(projectId, version);
            VersionBuild build = buildForVersion(projectId, version, contextId, claims,
                    version.equals(latestVersion) ? loaded : LoadedCode.empty(), true);
            concepts += build.result().concepts();
            aliases += build.result().aliases();
            members += build.result().members();
            conceptBatch.addAll(build.concepts());
            aliasBatch.addAll(build.aliases());
            membersByVersion.put(version, build.members());
        }
        alignmentStore.replaceWholeProjectIndex(projectId, membersByVersion, conceptBatch, aliasBatch);
        return new BuildResult(concepts, aliases, members, 0, 0);
    }

    /** 单个业务版本的成员/概念/别名重建（共享概念与别名，跨版本累积）。 */
    private VersionBuild buildForVersion(String projectId, String version, String contextId,
                                         List<KnowledgeClaimRecord> claims, LoadedCode loaded,
                                         boolean attachCode) {
        Set<String> conceptKeys = new HashSet<>();
        Map<String, List<String>> aliasToConcept = new LinkedHashMap<>();
        List<ConceptMember> memberBatch = new ArrayList<>();
        List<BusinessConcept> conceptBatch = new ArrayList<>();
        List<ConceptAlias> aliasBatch = new ArrayList<>();
        int concepts = 0;
        int aliases = 0;
        int members = 0;

        for (KnowledgeClaimRecord claim : claims) {
            if (claim.sourceType() == SourceType.CODE) continue;
            ConceptKey key = conceptFor(claim);
            if (key == null || key.canonicalKey().isBlank()) continue;

            String conceptId;
            if (conceptKeys.add(key.canonicalKey())) {
                conceptId = conceptId(projectId, key.canonicalKey());
                conceptBatch.add(new BusinessConcept(
                        conceptId, projectId, key.canonicalKey(), key.displayName(), key.type(),
                        key.module(), null, "ACTIVE", null, Instant.now().toString()));
                concepts++;
            } else {
                conceptId = conceptId(projectId, key.canonicalKey());
            }

            String aliasName = claim.subject();
            if (aliasName != null && !aliasName.isBlank()) {
                String aliasId = aliasId(projectId, conceptId, aliasName, claim.sourceType().name());
                aliasBatch.add(new ConceptAlias(
                        aliasId, projectId, conceptId, aliasName, claim.sourceType().name(),
                        "SOURCE_NAME", 1.0, Instant.now().toString(),
                        "SOURCE_EXPLICIT", "CONFIRMED", null));
                aliases++;
                aliasToConcept.computeIfAbsent(AlignmentNaming.normalize(aliasName),
                        ignored -> new ArrayList<>()).add(conceptId);
            }

            memberBatch.add(new ConceptMember(
                    memberId(projectId, conceptId, claim.sourceType().name(), claim.claimId(), version),
                    projectId, conceptId, claim.claimId(), claim.sourceType().name(),
                    truthRole(claim.sourceType()).name(), claim.claimId(), claim.subject(),
                    null, null, null, version, contextId, Instant.now().toString()));
            members++;
        }

        // 代码符号挂到匹配的业务概念（truthRole = IMPLEMENTATION）
        if (attachCode && loaded != null && loaded.commitSha() != null) {
            for (CodeSymbolView symbol : loaded.symbols()) {
                for (String conceptId : matchConcepts(aliasToConcept, symbol.simpleName())) {
                    memberBatch.add(new ConceptMember(
                            memberId(projectId, conceptId, "CODE", symbol.id(), version),
                            projectId, conceptId, null, "CODE",
                            TruthRole.IMPLEMENTATION.name(), symbol.id(), symbol.simpleName(),
                            symbol.projectId(), symbol.commitSha(), codeEvidenceId(symbol),
                            version, contextId, Instant.now().toString()));
                    members++;
                }
            }
        }

        return new VersionBuild(new BuildResult(concepts, aliases, members, 0, 0), memberBatch,
                conceptBatch, aliasBatch);
    }

    /** 发布完成后自动刷新派生实体索引；失败记录日志，不回滚已经提交的事实发布。 */
    @EventListener
    public void onDocumentVersionPublished(MultiSourceKnowledgeStore.DocumentVersionPublished event) {
        try {
            buildProject(event.projectId());
        } catch (RuntimeException exception) {
            log.error("发布后实体索引重建失败: projectId={} businessVersion={}",
                    event.projectId(), event.businessVersion(), exception);
        }
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
        if (aliasToConcept.size() > CONTAINS_MATCH_MAX_INDEX_SIZE) {
            return List.copyOf(result);
        }
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
        String subject = safe(claim.subject());
        String module = AlignmentNaming.moduleOf(claim.sourceType(), claim.factKey(), subject);
        if (subject.isBlank() && module.isBlank()) return null;
        return switch (claim.sourceType()) {
            case PARAMETER_TABLE -> new ConceptKey(
                    AlignmentNaming.conceptKey(module, subject), subject, "PARAMETER", module);
            case REQUIREMENT -> new ConceptKey(
                    AlignmentNaming.conceptKey(module, subject), subject, "REQUIREMENT", module);
            case TEST_CASE -> new ConceptKey(
                    AlignmentNaming.conceptKey(module, subject), subject, "TEST_MODULE", module);
            case TEST_RESULT -> new ConceptKey(
                    AlignmentNaming.conceptKey(module, subject), subject, "OBSERVATION", module);
            case DOUBT -> new ConceptKey(
                    AlignmentNaming.conceptKey(module.isBlank() ? "QA存疑" : module, ""),
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

    /**
     * 代码成员只有结构化符号定位，没有可回源的 KnowledgeEvidence excerpt；不能伪造 Evidence ID。
     * 代码事实会以 TRACEABLE 而非 SUPPORTED 暴露，回答层因此不会把位置误当成行为证明。
     */
    private String codeEvidenceId(CodeSymbolView symbol) {
        return null;
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

    private record VersionBuild(BuildResult result, List<ConceptMember> members,
                                 List<BusinessConcept> concepts, List<ConceptAlias> aliases) {
    }
}