package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.ExtractionRun;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeRelation;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.CrossSourceRelation;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.DoubtClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.UnifiedKnowledgeClaim;
import com.example.requirementrag.knowledge.multisource.CrossSourceRelationExtractor.CrossSourceExtraction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 0.9.3 Phase C：离线/发布前的关系生产与抽取运行审计。
 *
 * <p>把关系生成从查询路径迁到导入/发布任务：输入候选 Claim 与存疑，规则抽取 + 可选 LLM 确认，
 * 结果落 {@code knowledge_relation}，并记录一次 {@code knowledge_extraction_run}。查询路径只读预生成关系。
 */
@Service
public class KnowledgeRelationBuildService {

    private final MultiSourceKnowledgeStore store;
    private final CrossSourceRelationExtractor extractor;
    private final CrossSourceRelationConfirmer confirmer;
    private final MultiSourceKnowledgeProperties properties;

    @Autowired
    public KnowledgeRelationBuildService(MultiSourceKnowledgeStore store,
                                         CrossSourceRelationExtractor extractor,
                                         CrossSourceRelationConfirmer confirmer,
                                         MultiSourceKnowledgeProperties properties) {
        this.store = store;
        this.extractor = extractor;
        this.confirmer = confirmer == null ? ((source, relationType, target, evidence) ->
                new CrossSourceRelationConfirmer.Confirmation(true, "no-op")) : confirmer;
        this.properties = properties == null ? MultiSourceKnowledgeProperties.enabledDefault() : properties;
    }

    /** 构建结果：抽取运行 ID、产出的关系数、LLM 拒绝数、未解析诊断。 */
    public record BuildResult(String extractionRunId, int produced, int rejected, List<String> unresolved) {
    }

    /** 执行一次离线关系构建并记录抽取运行。 */
    public BuildResult buildRelations(String projectId, String version, String documentVersionId,
                                      List<UnifiedKnowledgeClaim> candidates, List<DoubtClaim> doubts,
                                      Map<String, String> evidenceByClaimId) {
        List<UnifiedKnowledgeClaim> safeCandidates = candidates == null ? List.of() : candidates;
        List<DoubtClaim> safeDoubts = doubts == null ? List.of() : doubts;
        String inputHash = sha256(projectId + "|" + version + "|"
                + safeCandidates.size() + "|" + safeDoubts.size() + "|"
                + safeCandidates.stream().map(UnifiedKnowledgeClaim::claimId).sorted().toList());
        String runId = "run:" + sha256(projectId + "|" + version + "|" + inputHash).substring(0, 32);
        store.startExtractionRun(new ExtractionRun(runId, projectId, documentVersionId,
                "CrossSourceRelationExtractor", "v1", null, null, inputHash, null,
                "RUNNING", null, null, null, null, null));

        CrossSourceExtraction extraction = extractor.extract(safeCandidates, safeDoubts);
        int produced = 0;
        int rejected = 0;
        List<String> relationOutput = new ArrayList<>();
        for (CrossSourceRelation relation : extraction.relations()) {
            String evidenceId = evidenceByClaimId == null ? null : evidenceByClaimId.get(relation.sourceClaimId());
            String status = "RULE_PROPOSED";
            String confirmationMethod = null;
            String confirmationReason = null;
            boolean persist = true;
            if (properties.relationLlmConfirmationEnabled()) {
                CrossSourceRelationConfirmer.ClaimRef source = ref(relation.sourceClaimId(), safeCandidates, safeDoubts);
                CrossSourceRelationConfirmer.ClaimRef target = ref(relation.targetClaimId(), safeCandidates, safeDoubts);
                if (source != null && target != null) {
                    CrossSourceRelationConfirmer.Confirmation confirmation = confirmer.confirm(
                            source, relation.type().name(), target, relation.evidenceLocation());
                    confirmationMethod = "LLM";
                    confirmationReason = confirmation.reason();
                    if (confirmation.confirmed()) {
                        status = "LLM_CONFIRMED";
                        produced++;
                    } else {
                        status = "LLM_REJECTED";
                        rejected++;
                        persist = true; // 保留拒绝记录用于审计
                    }
                } else {
                    produced++;
                }
            } else {
                produced++;
            }
            if (persist) {
                store.saveRelation(new KnowledgeRelation(
                        relation.relationId(), projectId, version,
                        relation.sourceClaimId(), relation.targetClaimId(),
                        relation.type().name(), status, null, evidenceId,
                        "RULE", confirmationMethod, confirmationReason, null, null));
                relationOutput.add(relation.relationId());
            }
        }
        String outputHash = sha256(String.join("|", relationOutput));
        store.finishExtractionRun(runId, "SUCCESS", outputHash, null, null, null,
                java.time.Instant.now().toString());
        return new BuildResult(runId, produced, rejected, List.copyOf(extraction.unresolved()));
    }

    private CrossSourceRelationConfirmer.ClaimRef ref(String claimId, List<UnifiedKnowledgeClaim> candidates,
                                                      List<DoubtClaim> doubts) {
        for (UnifiedKnowledgeClaim claim : candidates) {
            if (claim.claimId().equals(claimId)) {
                return new CrossSourceRelationConfirmer.ClaimRef(claimId, claim.sourceType().name(),
                        claim.subject() + " " + claim.predicate() + " " + claim.value());
            }
        }
        for (DoubtClaim doubt : doubts) {
            if (doubt.doubtId().equals(claimId)) {
                return new CrossSourceRelationConfirmer.ClaimRef(claimId, "DOUBT", doubt.question());
            }
        }
        return null;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}