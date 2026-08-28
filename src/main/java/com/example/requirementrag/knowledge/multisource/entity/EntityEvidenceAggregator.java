package com.example.requirementrag.knowledge.multisource.entity;

import com.example.requirementrag.code.CodeKnowledgeService;
import com.example.requirementrag.model.SourceSnippet;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeClaimRecord;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeStore;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricAlignmentStore;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.AlignmentRelation;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.BusinessConcept;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.ConceptAlias;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.ConceptMember;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.Citation;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.ConflictView;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.CurrentFacts;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.EntityView;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.FactRef;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.RelationView;
import com.example.requirementrag.knowledge.multisource.entity.EntityEvidenceModels.VersionFactBlock;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.EntityMention;
import com.example.requirementrag.knowledge.multisource.entity.EntityExtractionModels.EntityQueryPlan;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 实体证据聚合器（dev md §8.3/§8.4）：按实体聚合所有版本的需求/参数/测试/代码/关系/冲突，
 * currentFacts（代码/数值表/测试结果）与 timeline（按版本）分治，绝不混成一个数组。
 * 当前代码取 CODE 成员（含 commit），无代码索引时返回告警且不编造 commit。
 */
@Service
public class EntityEvidenceAggregator {

    /** 每个事实分区/版本块的默认条目上限，避免把全量 Claim 一把丢给前端。 */
    private static final int FACTS_PER_BLOCK_CAP = 20;
    private static final int RELATIONS_CAP = 50;

    private final MultiSourceKnowledgeStore knowledgeStore;
    private final CodeCentricAlignmentStore alignmentStore;
    private final com.example.requirementrag.knowledge.multisource.alignment.CodeSymbolLoader codeSymbolLoader;
    private final CodeKnowledgeService codeKnowledgeService;

    public EntityEvidenceAggregator(MultiSourceKnowledgeStore knowledgeStore,
                                    CodeCentricAlignmentStore alignmentStore,
                                    com.example.requirementrag.knowledge.multisource.alignment.CodeSymbolLoader codeSymbolLoader) {
        this(knowledgeStore, alignmentStore, codeSymbolLoader, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public EntityEvidenceAggregator(MultiSourceKnowledgeStore knowledgeStore,
                                    CodeCentricAlignmentStore alignmentStore,
                                    com.example.requirementrag.knowledge.multisource.alignment.CodeSymbolLoader codeSymbolLoader,
                                    CodeKnowledgeService codeKnowledgeService) {
        this.knowledgeStore = knowledgeStore;
        this.alignmentStore = alignmentStore;
        this.codeSymbolLoader = codeSymbolLoader;
        this.codeKnowledgeService = codeKnowledgeService;
    }

    /** 聚合选项（来自请求 include* 开关、版本过滤与条目上限；includeHistory=false 时 timeline 为空）。 */
    public record Options(int factsPerBlock, boolean includeCode, boolean includeParameters,
                          boolean includeTests, boolean includeHistory) {
        public Options {
            if (factsPerBlock <= 0) {
                factsPerBlock = FACTS_PER_BLOCK_CAP;
            }
        }

        /** 兼容便捷构造：默认每块 20 条、含历史。 */
        public Options(boolean includeCode, boolean includeParameters, boolean includeTests) {
            this(FACTS_PER_BLOCK_CAP, includeCode, includeParameters, includeTests, true);
        }
    }

    /** 聚合全部已解析实体的证据视图。capVersion 为空表示不限。 */
    public List<EntityView> aggregate(String projectId, EntityQueryPlan plan,
                                      List<EntityMention> mentions, Options options) {
        List<EntityView> views = new ArrayList<>();
        List<BusinessConcept> concepts = alignmentStore.findConcepts(projectId);
        Map<String, BusinessConcept> conceptById = new HashMap<>();
        for (BusinessConcept concept : concepts) {
            conceptById.put(concept.conceptId(), concept);
        }
        String latestVersion = latestVersion(projectId);
        // 代码图是可选派生索引；不可用时保留参数/需求/测试证据，不让实体查询整体失败。
        Map<String, com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.CodeSymbolView> symbolsById =
                new HashMap<>();
        if (options.includeCode()) {
            try {
                for (var symbol : codeSymbolLoader.load(projectId).symbols()) {
                    symbolsById.putIfAbsent(symbol.id(), symbol);
                }
            } catch (RuntimeException ignored) {
                // viewFor 会把没有代码事实转换为稳定 CODE_CONTEXT_UNAVAILABLE 告警。
            }
        }
        for (EntityMention mention : mentions) {
            if (mention.entityId() == null) {
                continue;
            }
            views.add(viewFor(projectId, mention, conceptById, latestVersion, options,
                    plan.requestedVersions(), symbolsById));
        }
        return views;
    }

    private EntityView viewFor(String projectId, EntityMention mention,
                               Map<String, BusinessConcept> conceptById, String latestVersion,
                               Options options, List<String> requestedVersions,
                               Map<String, com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.CodeSymbolView> symbolsById) {
        String entityId = mention.entityId();
        BusinessConcept concept = conceptById.get(entityId);
        String canonicalName = concept == null ? mention.canonicalName() : concept.displayName();
        List<String> warnings = new ArrayList<>();

        // 成员 + 别名 + 批量水化
        List<ConceptMember> members = alignmentStore.findMembers(projectId, entityId, null);
        int perBlock = options.factsPerBlock();
        List<String> aliases = alignmentStore.findAliases(projectId, entityId).stream()
                .filter(a -> "CONFIRMED".equals(a.status()))
                .map(ConceptAlias::alias).limit(perBlock).toList();
        List<String> claimIds = members.stream()
                .map(ConceptMember::claimId)
                .filter(id -> id != null && !id.isBlank())
                .distinct().toList();
        Map<String, KnowledgeClaimRecord> claimsById = new LinkedHashMap<>();
        Map<String, List<String>> evidenceByClaim = new HashMap<>();
        if (!claimIds.isEmpty()) {
            // Claim、文档状态和 active manifest 必须由同一查询规则校验，避免状态过滤在实体层分叉。
            for (KnowledgeClaimRecord claim : knowledgeStore.findPublishedClaimsByIdsAll(projectId, claimIds)) {
                claimsById.put(claim.claimId(), claim);
            }
            evidenceByClaim.putAll(knowledgeStore.findPublishedEvidenceIdsByClaimIdsAll(projectId, claimIds));
        }
        Map<String, String> versionOfClaim = new HashMap<>(
                knowledgeStore.findPublishedClaimVersions(projectId, claimIds));
        for (ConceptMember member : members) {
            if (member.claimId() != null && member.businessVersion() != null) {
                versionOfClaim.putIfAbsent(member.claimId(), member.businessVersion());
            }
        }

        Set<String> wanted = requestedVersions == null || requestedVersions.isEmpty()
                ? null : new LinkedHashSet<>(requestedVersions);

        // 时间轴：按成员版本分组需求/参数/测试
        Map<String, VersionFactBlock> blocks = new LinkedHashMap<>();
        for (ConceptMember member : members) {
            String version = member.businessVersion();
            if (wanted != null && (version == null || !wanted.contains(version))) {
                continue;
            }
            if (member.claimId() == null) {
                continue;
            }
            KnowledgeClaimRecord claim = claimsById.get(member.claimId());
            if (claim == null) {
                continue;
            }
            VersionFactBlock current = blocks.computeIfAbsent(
                    version == null || version.isBlank() ? "unknown" : version,
                    ignored -> new VersionFactBlock(
                            version == null || version.isBlank() ? "unknown" : version,
                            new ArrayList<>(), new ArrayList<>(), new ArrayList<>()));
            appendToBlock(current, claim, evidenceByClaim.get(claim.claimId()), perBlock);
        }
        String currentVersion = currentVersion(latestVersion, wanted, blocks);

        // 当前事实分区
        List<FactRef> codeFacts = new ArrayList<>();
        List<FactRef> parameterTables = new ArrayList<>();
        List<FactRef> testResults = new ArrayList<>();
        if (options.includeCode() && currentVersion != null) {
            String loadedCommit = symbolsById.values().stream().map(symbol -> symbol.commitSha())
                    .filter(commit -> commit != null && !commit.isBlank()).findFirst().orElse(null);
            for (ConceptMember member : members) {
                if (!"CODE".equals(member.sourceType())
                        || !currentVersion.equals(member.businessVersion())
                        || !currentVersion.equals(latestVersion)
                        || (loadedCommit != null && !loadedCommit.equals(member.commitSha()))) {
                    continue;
                }
                String location = codeLocation(member, symbolsById);
                if (location == null) {
                    continue;
                }
                String excerpt = codeExcerpt(projectId, member, symbolsById);
                codeFacts.add(new FactRef(null, member.externalId(), "CODE", member.displayName(),
                        null, null, member.businessVersion(),
                        member.evidenceId() == null ? List.of() : List.of(member.evidenceId()), location,
                        null, null, excerpt));
                if (codeFacts.size() >= perBlock) break;
            }
        }
        if (currentVersion != null) {
            if (options.includeParameters()) {
                for (KnowledgeClaimRecord claim : claimsById.values()) {
                    if (!EntityEvidenceModels.isParameter(claim.sourceType())) {
                        continue;
                    }
                    String version = versionOfClaim.get(claim.claimId());
                    if (version == null || !version.equals(currentVersion)) {
                        continue;
                    }
                    parameterTables.add(factRef(claim, version, evidenceByClaim.get(claim.claimId())));
                    if (parameterTables.size() >= perBlock) {
                        break;
                    }
                }
            }
            if (options.includeTests()) {
                for (KnowledgeClaimRecord claim : claimsById.values()) {
                    if (claim.sourceType() == com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType.TEST_RESULT) {
                        String version = versionOfClaim.get(claim.claimId());
                        if (version == null || !version.equals(currentVersion)) {
                            continue;
                        }
                        testResults.add(factRef(claim, version, evidenceByClaim.get(claim.claimId())));
                        if (testResults.size() >= perBlock) {
                            break;
                        }
                    }
                }
            }
        }

        // 关系（claim 端点命中，PROPOSED 生命周期状态位保留）
        List<RelationView> relations = new ArrayList<>();
        Set<String> seenRelations = new LinkedHashSet<>();
        for (String claimId : claimsById.keySet()) {
            if (relations.size() >= RELATIONS_CAP) break;
            String claimVersion = versionOfClaim.get(claimId);
            for (AlignmentRelation relation : alignmentStore.findAlignmentRelationsForClaim(projectId, claimId)) {
                if (!seenRelations.add(relation.relationId())
                        || !claimVersionMatches(relation, claimVersion, versionOfClaim, projectId)) {
                    continue;
                }
                String relationStatus = knowledgeStore.isPublishedEvidenceForRelation(
                        projectId, relation.version(), relation.evidenceId(),
                        relation.sourceClaimId(), relation.targetClaimId())
                        ? relation.status() : "UNVERIFIED";
                relations.add(new RelationView(relation.relationId(), relation.relationType(),
                        relation.sourceClaimId(), relation.targetClaimId(), relation.matchMethod(),
                        relationStatus, relation.confidence(), relation.evidenceId()));
            }
        }

        // 确定性冲突：同 factKey 不同取值——只对**输出中包含的 Claim**（currentFacts + timeline）分组，
        // includeHistory=false 时自然收敛到当前版本，历史冲突不再泄漏进响应与 LLM Prompt
        java.util.Set<String> includedClaimIds = new LinkedHashSet<>();
        for (FactRef ref : parameterTables) {
            if (ref.claimId() != null) includedClaimIds.add(ref.claimId());
        }
        for (FactRef ref : testResults) {
            if (ref.claimId() != null) includedClaimIds.add(ref.claimId());
        }
        for (VersionFactBlock block : options.includeHistory() ? capped(blocks, perBlock)
                : List.<VersionFactBlock>of()) {
            for (FactRef ref : block.requirements()) {
                if (ref.claimId() != null) includedClaimIds.add(ref.claimId());
            }
        }
        Map<String, List<String>> valuesByFactKey = new LinkedHashMap<>();
        Map<String, String> subjectByFactKey = new HashMap<>();
        for (KnowledgeClaimRecord claim : claimsById.values()) {
            if (!includedClaimIds.contains(claim.claimId())) {
                continue;
            }
            if (claim.factKey() == null || claim.factKey().isBlank()) {
                continue;
            }
            valuesByFactKey.computeIfAbsent(claim.factKey(), ignored -> new ArrayList<>())
                    .add(safe(claim.objectValue()));
            subjectByFactKey.putIfAbsent(claim.factKey(), claim.subject());
        }
        List<ConflictView> conflicts = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : valuesByFactKey.entrySet()) {
            List<String> values = entry.getValue().stream().distinct().limit(perBlock).toList();
            if (values.size() > 1) {
                conflicts.add(new ConflictView(entry.getKey(), subjectByFactKey.get(entry.getKey()),
                        values, "CONFLICTED"));
            }
        }

        // 稳定告警
        if (options.includeCode() && codeFacts.isEmpty()) {
            warnings.add("CODE_CONTEXT_UNAVAILABLE");
        }
        if (options.includeParameters() && parameterTables.isEmpty()) {
            warnings.add("PARAMETER_TABLE_UNAVAILABLE");
        }

        // timeline：includeHistory=false 时仅保留最新已发布版本块（当前版本，非“历史版本”），
        // 既满足“不含历史”又让最新需求仍可被事实优先级评估
        List<VersionFactBlock> timeline = options.includeHistory()
                ? capped(blocks, perBlock)
                : latestBlockOnly(blocks, currentVersion, perBlock);
        return new EntityView(entityId, canonicalName, aliases,
                new CurrentFacts(codeFacts, parameterTables, testResults),
                timeline, relations, conflicts, warnings);
    }

    private boolean claimVersionMatches(AlignmentRelation relation, String claimVersion,
                                         Map<String, String> versionOfClaim, String projectId) {
        if (relation.version() == null || !relation.version().equals(claimVersion)) return false;
        Set<String> endpoints = new LinkedHashSet<>();
        if (relation.sourceClaimId() != null) endpoints.add(relation.sourceClaimId());
        if (relation.targetClaimId() != null) endpoints.add(relation.targetClaimId());
        if (endpoints.isEmpty()) return false;
        Map<String, String> endpointVersions = knowledgeStore.findPublishedClaimVersions(projectId, endpoints);
        return endpoints.stream().allMatch(id -> relation.version().equals(endpointVersions.get(id)));
    }

    private String currentVersion(String latestVersion, Set<String> wanted,
                                  Map<String, VersionFactBlock> blocks) {
        if (wanted == null) return latestVersion;
        return blocks.keySet().stream()
                .filter(wanted::contains)
                .max(EntityEvidenceAggregator::compareVersions)
                .orElse(null);
    }

    /** includeHistory=false 时仅保留请求范围内的最新已发布版本块。 */
    private List<VersionFactBlock> latestBlockOnly(Map<String, VersionFactBlock> blocks,
                                                   String latestVersion, int perBlock) {
        if (latestVersion == null) {
            return List.of();
        }
        VersionFactBlock block = blocks.get(latestVersion);
        if (block == null) {
            return List.of();
        }
        return List.of(new VersionFactBlock(block.businessVersion(),
                cap(block.requirements(), perBlock), cap(block.parameterTables(), perBlock),
                cap(block.tests(), perBlock)));
    }

    /** 版本感知取项目最新已发布业务版本（无则 null）。 */
    public String latestVersion(String projectId) {
        List<String> versions = knowledgeStore.findPublishedBusinessVersions(projectId);
        return versions.isEmpty() ? null : versions.get(versions.size() - 1);
    }

    /** 项目全部已发布业务版本（确定性聚合的覆盖范围；向量补召回须同范围）。 */
    public List<String> publishedBusinessVersions(String projectId) {
        return knowledgeStore.findPublishedBusinessVersions(projectId);
    }

    private void appendToBlock(VersionFactBlock block, KnowledgeClaimRecord claim,
                               List<String> evidenceIds, int perBlock) {
        FactRef ref = factRef(claim, block.businessVersion(), evidenceIds);
        if (EntityEvidenceModels.isRequirement(claim.sourceType())) {
            if (block.requirements().size() < perBlock) {
                block.requirements().add(ref);
            }
        } else if (EntityEvidenceModels.isParameter(claim.sourceType())) {
            if (block.parameterTables().size() < perBlock) {
                block.parameterTables().add(ref);
            }
        } else if (EntityEvidenceModels.isTest(claim.sourceType())) {
            if (block.tests().size() < perBlock) {
                block.tests().add(ref);
            }
        }
    }

    private FactRef factRef(KnowledgeClaimRecord claim, String versionOverride, List<String> evidenceIds) {
        return new FactRef(claim.claimId(), null, claim.sourceType().name(), claim.subject(),
                claim.objectValue(), claim.unit(),
                versionOverride == null ? "" : versionOverride,
                evidenceIds == null ? List.of() : evidenceIds,
                null, claim.factKey(), claim.predicate());
    }

    /** 从同一 commit 快照读取有界源码片段；读取失败时只保留可回源位置。 */
    private String codeExcerpt(String projectId, ConceptMember member,
                               Map<String, com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.CodeSymbolView> symbolsById) {
        if (codeKnowledgeService == null || member.externalId() == null) return null;
        var symbol = symbolsById.get(member.externalId());
        if (symbol == null || symbol.commitSha() == null || symbol.filePath() == null) return null;
        try {
            SourceSnippet snippet = codeKnowledgeService.sourceAtCommit(projectId, symbol.commitSha(),
                    symbol.filePath(), symbol.startLine(), Math.min(symbol.endLine(), symbol.startLine() + 80));
            return snippet.text();
        } catch (RuntimeException | java.io.IOException ignored) {
            return null;
        }
    }

    /** 代码事实定位：repository@commit:path:start-end（来自代码符号图，无符号则不编造）。 */
    private String codeLocation(ConceptMember member,
                                Map<String, com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.CodeSymbolView> symbolsById) {
        var symbol = member.externalId() == null ? null : symbolsById.get(member.externalId());
        if (symbol == null) {
            return null;
        }
        String repo = member.repositoryId() == null || member.repositoryId().isBlank()
                ? symbol.projectId() : member.repositoryId();
        String commit = member.commitSha() == null || member.commitSha().isBlank()
                ? symbol.commitSha() : member.commitSha();
        StringBuilder sb = new StringBuilder();
        if (repo != null) sb.append(repo);
        if (commit != null) sb.append('@').append(commit);
        if (symbol.filePath() != null) sb.append(':').append(symbol.filePath());
        sb.append(':').append(symbol.startLine()).append('-').append(symbol.endLine());
        return sb.toString();
    }

    private List<VersionFactBlock> capped(Map<String, VersionFactBlock> blocks, int perBlock) {
        List<VersionFactBlock> result = new ArrayList<>();
        for (VersionFactBlock block : blocks.values()) {
            result.add(new VersionFactBlock(block.businessVersion(),
                    cap(block.requirements(), perBlock), cap(block.parameterTables(), perBlock),
                    cap(block.tests(), perBlock)));
        }
        // 时间轴按数值感知版本升序展示（5.9 < 5.10），而非成员遍历序
        result.sort((left, right) -> compareVersions(left.businessVersion(), right.businessVersion()));
        return result;
    }

    private static int compareVersions(String left, String right) {
        String[] l = left.split("[.\\-]", -1);
        String[] r = right.split("[.\\-]", -1);
        int max = Math.max(l.length, r.length);
        for (int i = 0; i < max; i++) {
            String a = i < l.length ? l[i] : "0";
            String b = i < r.length ? r[i] : "0";
            try {
                int comparison = Long.compare(Long.parseLong(a), Long.parseLong(b));
                if (comparison != 0) return comparison;
            } catch (NumberFormatException ignored) {
                int comparison = a.compareTo(b);
                if (comparison != 0) return comparison;
            }
        }
        return 0;
    }

    private <T> List<T> cap(List<T> values, int perBlock) {
        return values.size() <= perBlock ? values : values.subList(0, perBlock);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    /** 汇总引用：全部事实的 claim/evidence，去重后按实体顺序输出。 */
    public List<Citation> citations(String projectId, List<EntityView> views) {
        List<Citation> citations = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (EntityView view : views) {
            for (FactRef ref : allFacts(view)) {
                String key = ref.claimId() == null
                        ? ref.sourceType() + ":" + ref.externalId()
                        : ref.sourceType() + ":" + ref.claimId();
                if (!seen.add(key)) {
                    continue;
                }
                String evidence = ref.evidenceIds().isEmpty() ? null : ref.evidenceIds().get(0);
                citations.add(new Citation(ref.claimId(), ref.sourceType(),
                        ref.businessVersion(), evidence));
            }
        }
        return citations;
    }

    private List<FactRef> allFacts(EntityView view) {
        List<FactRef> facts = new ArrayList<>();
        facts.addAll(view.currentFacts().code());
        facts.addAll(view.currentFacts().parameterTables());
        facts.addAll(view.currentFacts().testResults());
        for (VersionFactBlock block : view.timeline()) {
            facts.addAll(block.requirements());
            facts.addAll(block.parameterTables());
            facts.addAll(block.tests());
        }
        return facts;
    }
}