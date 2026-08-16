package com.example.requirementrag.evolution.experience;

import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.model.RagOutcome;
import com.example.requirementrag.model.RagStageDiagnostic;
import com.example.requirementrag.model.RagWarning;
import com.example.requirementrag.retrieval.agentic.EvidenceReflector;
import com.example.requirementrag.retrieval.pipeline.RetrievalBundle;
import com.example.requirementrag.retrieval.pipeline.RetrievalRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 单次检索的进化 trace。
 * <p>
 * 由 {@code AgenticOrchestrator} 创建并逐 hop 填充，最后转换为不可变 {@link RetrievalExperience}。
 * 本类不是 Spring bean，每次请求创建一次。
 * </p>
 */
public class EvolutionTrace {

    private final String experienceId;
    private final Instant occurredAt;
    private final RetrievalRequest request;
    private final String policyVersion;
    private final String configHash;
    private final String indexVersion;
    private final String datasetVersion;
    private final List<RetrievalExperience.HopSnapshot> hopRecords = new ArrayList<>();
    private final List<RetrievalExperience.CandidateSnapshot> candidates = new ArrayList<>();
    private final List<String> executedStrategies = new ArrayList<>();

    private EvolutionTrace(RetrievalRequest request, String policyVersion, String configHash,
                           String indexVersion, String datasetVersion) {
        this.experienceId = UUID.randomUUID().toString();
        this.occurredAt = Instant.now();
        this.request = request;
        this.policyVersion = policyVersion;
        this.configHash = configHash;
        this.indexVersion = indexVersion;
        this.datasetVersion = datasetVersion;
    }

    /** 创建新 trace。 */
    public static EvolutionTrace start(RetrievalRequest request, String policyVersion,
                                       String configHash, String indexVersion, String datasetVersion) {
        return new EvolutionTrace(request, policyVersion, configHash, indexVersion, datasetVersion);
    }

    /** 记录一跳策略执行结果与反思结果。 */
    public void recordHop(int hop, String strategy, RetrievalBundle bundle,
                          EvidenceReflector.ReflectionResult reflection, long durationMs) {
        hopRecords.add(new RetrievalExperience.HopSnapshot(hop, strategy,
                reflection == null ? null : reflection.verdict().name(),
                reflection == null ? null : reflection.reasonCode(), durationMs));
        executedStrategies.add(strategy);
        if (bundle != null) {
            int rank = 1;
            for (ChunkRecord chunk : bundle.requirementEvidence()) {
                candidates.add(new RetrievalExperience.CandidateSnapshot(chunk.id(), "requirement",
                        rank, rank, null));
                rank++;
            }
            rank = 1;
            for (CodeChunk chunk : bundle.codeEvidence()) {
                candidates.add(new RetrievalExperience.CandidateSnapshot(chunk.id(), "code",
                        rank, rank, null));
                rank++;
            }
        }
    }

    /** 转换为最终经验事件。 */
    public RetrievalExperience finish(RagOutcome<RetrievalBundle> outcome, int hopCount, long latencyMs,
                                      List<RagWarning> warnings, List<RagStageDiagnostic> diagnostics,
                                      List<String> degradedStages) {
        RetrievalBundle bundle = outcome == null ? null : outcome.data();
        List<String> finalRanking = new ArrayList<>();
        List<String> evidenceIds = new ArrayList<>();
        if (bundle != null) {
            bundle.requirementEvidence().forEach(chunk -> {
                finalRanking.add(chunk.id());
                evidenceIds.add(chunk.id());
            });
            bundle.codeEvidence().forEach(chunk -> {
                finalRanking.add(chunk.id());
                evidenceIds.add(chunk.id());
            });
        }
        List<String> warningCodes = warnings == null ? List.of()
                : warnings.stream().map(RagWarning::code).toList();
        List<RetrievalExperience.StageSnapshot> stageSnapshots = diagnostics == null ? List.of()
                : diagnostics.stream().map(d -> new RetrievalExperience.StageSnapshot(
                        d.stage(), d.status() == null ? null : d.status().name(),
                        null, d.durationMs())).toList();
        String lastReflectionVerdict = hopRecords.isEmpty() ? null
                : hopRecords.get(hopRecords.size() - 1).reflectionVerdict();
        String lastReflectionReason = hopRecords.isEmpty() ? null
                : hopRecords.get(hopRecords.size() - 1).reflectionReasonCode();
        return new RetrievalExperience(
                RetrievalExperience.SCHEMA_VERSION,
                experienceId,
                occurredAt,
                request.projectId(),
                request.documentId(),
                request.version(),
                ExperienceHashing.sha256(request.query()),
                request.query(),
                request.profile() == null ? null : request.profile().name(),
                executedStrategies.isEmpty() ? null : executedStrategies.get(0),
                List.copyOf(executedStrategies),
                hopCount,
                List.copyOf(hopRecords),
                List.copyOf(candidates),
                List.copyOf(finalRanking),
                List.copyOf(evidenceIds),
                lastReflectionVerdict,
                lastReflectionReason,
                outcome == null || outcome.status() == null ? null : outcome.status().name(),
                List.copyOf(warningCodes),
                List.copyOf(stageSnapshots),
                latencyMs,
                null,
                degradedStages == null ? List.of() : List.copyOf(degradedStages),
                null,
                policyVersion,
                configHash,
                indexVersion,
                datasetVersion
        );
    }

    public String experienceId() {
        return experienceId;
    }
}
