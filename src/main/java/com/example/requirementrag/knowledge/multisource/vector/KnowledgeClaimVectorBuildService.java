package com.example.requirementrag.knowledge.multisource.vector;

import com.example.requirementrag.conflict.KnowledgeConflictModels.Authority;
import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeClaimRecord;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeStore;
import com.example.requirementrag.knowledge.multisource.vector.KnowledgeClaimVectorModels.ClaimVectorGenerationInput;
import com.example.requirementrag.knowledge.multisource.vector.KnowledgeClaimVectorModels.ClaimVectorGenerationManifest;
import com.example.requirementrag.knowledge.multisource.vector.KnowledgeClaimVectorModels.GenerationStatus;
import com.example.requirementrag.knowledge.multisource.vector.KnowledgeClaimVectorModels.KnowledgeClaimVectorPoint;
import com.example.requirementrag.knowledge.multisource.vector.KnowledgeClaimVectorModels.WarningCode;
import com.example.requirementrag.retrieval.EmbeddingBatcher;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Claim 向量构建服务（§10 Phase B）：编排 SQLite 权威存储 + Qdrant 投影发布的完整流水线。
 * <p>
 * 构建流水线：流式加载 Claims → 文本组合 → 指纹去重 → recordBuildStart →
 * 分块嵌入并逐块写入物理 collection → 校验点数 → updateStatus(SUCCESS) →
 * Qdrant alias 切换 → markActive（SQLite 权威提交在最后）。
 * <p>
 * 安全保证：
 * <ul>
 *   <li>同 scope 并发构建串行化（64 条带 striped locks）。</li>
 *   <li>写入/校验/嵌入失败时 alias 不变，旧 ACTIVE 代际保留。</li>
 *   <li>高（Review 6）：alias 切换先于 markActive——切换失败则该代际 FAILED 且保持非 ACTIVE，
 *       不再向调用方返回“成功”；只有 alias 与 SQLite 双确认后才标 ACTIVE。</li>
 *   <li>高（Review 2）：alias 与物理 collection 按 project+version 隔离，不跨 scope 泄漏或误删。</li>
 *   <li>高（Review 8）：按页流式读取 + 分块嵌入 + 逐块写点，20 万 Claim 构建不驻留全量向量。</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(prefix = "app.rag.multi-source.claim-vector",
        name = {"enabled", "build-enabled"}, havingValue = "true", matchIfMissing = false)
public class KnowledgeClaimVectorBuildService {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(KnowledgeClaimVectorBuildService.class);
    private static final int BUILD_LOCK_STRIPES = 64;
    /** 高（Review 8）：SQLite 流式读取页大小——每页仅驻留该页 Claim。 */
    private static final int STREAM_PAGE_SIZE = 500;
    /** 高（Review 8）：嵌入分块大小——每块向量/点立即写入后即释放，不累积。 */
    private static final int EMBED_CHUNK_SIZE = 64;
    private final Object[] buildLocks = new Object[BUILD_LOCK_STRIPES];
    {
        for (int i = 0; i < BUILD_LOCK_STRIPES; i++) {
            buildLocks[i] = new Object();
        }
    }

    private final MultiSourceKnowledgeStore knowledgeStore;
    private final SQLiteKnowledgeClaimVectorStore vectorStore;
    private final KnowledgeClaimVectorQdrantStore qdrantStore;
    private final KnowledgeClaimVectorTextComposer textComposer;
    private final EmbeddingBatcher embeddingBatcher;
    private final EmbeddingModel embeddingModel;
    private final KnowledgeClaimVectorProperties properties;

    public KnowledgeClaimVectorBuildService(MultiSourceKnowledgeStore knowledgeStore,
                                            SQLiteKnowledgeClaimVectorStore vectorStore,
                                            KnowledgeClaimVectorQdrantStore qdrantStore,
                                            KnowledgeClaimVectorTextComposer textComposer,
                                            EmbeddingBatcher embeddingBatcher,
                                            EmbeddingModel embeddingModel,
                                            KnowledgeClaimVectorProperties properties) {
        this.knowledgeStore = knowledgeStore;
        this.vectorStore = vectorStore;
        this.qdrantStore = qdrantStore;
        this.textComposer = textComposer;
        this.embeddingBatcher = embeddingBatcher;
        this.embeddingModel = embeddingModel;
        this.properties = properties;
    }

    /**
     * 构建并发布指定项目+版本的 Claim 向量投影。
     * 同 scope 并发构建串行化；可复用代际跳过重复构建。
     *
     * @return ACTIVE 代际 manifest
     */
    public ClaimVectorGenerationManifest build(String projectId, String businessVersion) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId 不能为空");
        }
        if (businessVersion == null || businessVersion.isBlank()) {
            throw new IllegalArgumentException("businessVersion 不能为空");
        }
        int stripe = Math.floorMod(
                (projectId + "\u0000" + businessVersion).hashCode(), BUILD_LOCK_STRIPES);
        synchronized (buildLocks[stripe]) {
            return doBuild(projectId, businessVersion);
        }
    }

    /**
     * 回滚到最近退役（上一）代际（如新代际发现质量问题）。
     * 高（Review 9）：listRetiredForRollback 按 published_at desc，索引 0 才是最近退役代际。
     * SQLite rollbackTo 恢复 RETIRED→ACTIVE，Qdrant rollbackAlias 切回旧物理 collection。
     */
    public Optional<ClaimVectorGenerationManifest> rollback(String projectId, String businessVersion) {
        List<ClaimVectorGenerationManifest> retired = vectorStore.listRetiredForRollback(projectId, businessVersion);
        if (retired.isEmpty()) {
            return Optional.empty();
        }
        ClaimVectorGenerationManifest target = retired.get(0);
        return restoreGeneration(projectId, businessVersion, target);
    }

    /**
     * 回滚到指定代际（运维手册 rollback-to 端点；高：Review 10——按手册需能按指定代际回滚）。
     *
     * @param generationId 目标代际（须属于该 scope 且处于 RETIRED）
     */
    public Optional<ClaimVectorGenerationManifest> rollbackTo(String projectId, String businessVersion,
                                                              String generationId) {
        if (generationId == null || generationId.isBlank()) {
            return Optional.empty();
        }
        Optional<ClaimVectorGenerationManifest> target = vectorStore.findGeneration(generationId);
        if (target.isEmpty()) {
            return Optional.empty();
        }
        ClaimVectorGenerationManifest manifest = target.get();
        if (!projectId.equals(manifest.projectId()) || !businessVersion.equals(manifest.businessVersion())) {
            LOGGER.warn("rollbackTo scope mismatch: target gen {} belongs to {}/{} not {}/{}",
                    generationId, manifest.projectId(), manifest.businessVersion(), projectId, businessVersion);
            return Optional.empty();
        }
        if (manifest.status() != GenerationStatus.RETIRED) {
            LOGGER.warn("rollbackTo target {} status {} (expected RETIRED)", generationId, manifest.status());
            return Optional.empty();
        }
        return restoreGeneration(projectId, businessVersion, manifest);
    }

    /** 查询当前 ACTIVE 代际。 */
    public Optional<ClaimVectorGenerationManifest> findActive(String projectId, String businessVersion) {
        return vectorStore.findActiveGeneration(projectId, businessVersion);
    }

    // ── 内部 ─────────────────────────────────────────────────────────────

    private Optional<ClaimVectorGenerationManifest> restoreGeneration(String projectId, String businessVersion,
                                                                       ClaimVectorGenerationManifest target) {
        Optional<ClaimVectorGenerationManifest> restored = vectorStore.rollbackTo(target.generationId());
        if (restored.isPresent() && restored.get().physicalCollection() != null) {
            qdrantStore.rollbackAlias(
                    properties.liveAlias(projectId, businessVersion), restored.get().physicalCollection());
        }
        return restored;
    }

    private ClaimVectorGenerationManifest doBuild(String projectId, String businessVersion) {
        String embeddingModelName = embeddingModel.getClass().getName() + ":" + embeddingModel.dimensions();
        int dimension = embeddingModel.dimensions();
        String liveAlias = properties.liveAlias(projectId, businessVersion);

        // 1. 流式加载 Claims 并组合文本（高：Review 3——版本已绑定；Review 8——分页不驻留全量）
        List<ClaimVectorGenerationInput> inputs = new ArrayList<>();
        List<ComposedClaim> composed = new ArrayList<>();
        long offset = 0;
        while (true) {
            List<KnowledgeClaimRecord> page = knowledgeStore.findClaimsByProjectVersionPage(
                    projectId, businessVersion, STREAM_PAGE_SIZE, offset);
            if (page.isEmpty()) {
                break;
            }
            for (KnowledgeClaimRecord claim : page) {
                if (!textComposer.isSourceEligible(claim.sourceType())) {
                    continue;
                }
                Optional<String> text = textComposer.compose(claim, businessVersion);
                if (text.isPresent()) {
                    String textHash = KnowledgeClaimVectorTextComposer.textHash(text.get());
                    composed.add(new ComposedClaim(claim.claimId(), claim.documentVersionId(),
                            claim.sourceType(), claim.authority(), claim.status(),
                            claim.factKey(), claim.subject(), claim.predicate(), claim.valueType(),
                            claim.unit(), claim.updatedAt(), text.get(), textHash));
                }
            }
            offset += page.size();
        }
        if (composed.isEmpty()) {
            throw new IllegalStateException("无可投影 Claim: projectId=" + projectId
                    + " version=" + businessVersion + "（所有 Claim 来源类型被排除或文本为空）");
        }

        // 2. 构建输入集合 + 指纹
        for (ComposedClaim c : composed) {
            inputs.add(new ClaimVectorGenerationInput("pending", c.claimId(), c.documentVersionId(),
                    c.textHash(), c.updatedAt()));
        }
        String fingerprint = KnowledgeClaimVectorModels.computeInputFingerprint(
                inputs, properties.projectionSchemaVersion(), properties.textComposerVersion(),
                embeddingModelName, dimension);

        // 3. 可复用代际检查——同一指纹已有 SUCCESS/ACTIVE 代际则跳过
        Optional<ClaimVectorGenerationManifest> reusable = vectorStore.findReusableGeneration(
                projectId, businessVersion, fingerprint,
                properties.projectionSchemaVersion(), embeddingModelName);
        if (reusable.isPresent()) {
            return reusable.get();
        }

        // 4. 生成代际 ID 并 recordBuildStart
        String generationId = "cv-" + UUID.randomUUID();
        String startedAt = Instant.now().toString();
        ClaimVectorGenerationManifest manifest = new ClaimVectorGenerationManifest(
                generationId, projectId, businessVersion, fingerprint,
                properties.projectionSchemaVersion(), properties.textComposerVersion(),
                embeddingModelName, dimension, null, GenerationStatus.BUILDING,
                composed.size(), 0, "[]", startedAt, null, null);
        List<ClaimVectorGenerationInput> finalInputs = inputs.stream()
                .map(i -> new ClaimVectorGenerationInput(
                        generationId, i.claimId(), i.documentVersionId(),
                        i.textHash(), i.updatedAt()))
                .toList();
        vectorStore.recordBuildStart(manifest, finalInputs);

        // 5. 流式嵌入 + 逐块写入物理 collection（不驻留全部 vector/point）
        String physicalCollection = liveAlias + "-" + Instant.now().toEpochMilli();
        boolean collectionReady = false;
        int written = 0;
        for (int start = 0; start < composed.size(); start += EMBED_CHUNK_SIZE) {
            int end = Math.min(start + EMBED_CHUNK_SIZE, composed.size());
            List<ComposedClaim> chunk = composed.subList(start, end);
            List<String> texts = chunk.stream().map(ComposedClaim::text).toList();
            List<float[]> vectors;
            try {
                vectors = embeddingBatcher.embedAll(texts);
            } catch (RuntimeException exception) {
                failGeneration(generationId, WarningCode.BUILD_FAILED, "嵌入失败", exception.getMessage());
                throw new IllegalStateException("Claim 向量嵌入失败: " + exception.getMessage(), exception);
            }
            if (vectors.size() != chunk.size()) {
                failGeneration(generationId, WarningCode.BUILD_FAILED, "嵌入数量不一致",
                        "期望 " + chunk.size() + " 实际 " + vectors.size());
                throw new IllegalStateException("Claim 向量嵌入数量不一致");
            }
            List<KnowledgeClaimVectorPoint> points = new ArrayList<>(chunk.size());
            for (int i = 0; i < chunk.size(); i++) {
                ComposedClaim c = chunk.get(i);
                points.add(new KnowledgeClaimVectorPoint(
                        projectId, businessVersion, c.claimId(), c.documentVersionId(),
                        c.sourceType(), c.authority(), c.status(), c.factKey(), c.subject(),
                        c.predicate(), c.valueType(), c.unit(), List.of(),
                        generationId, properties.projectionSchemaVersion(), embeddingModelName,
                        c.textHash()));
            }
            // 惰性建集合：首个分块嵌入成功后才建——嵌入阶段失败不留空集合
            if (!collectionReady) {
                qdrantStore.createCollectionIfAbsent(physicalCollection, dimension);
                collectionReady = true;
            }
            try {
                qdrantStore.appendPoints(physicalCollection, points, vectors);
            } catch (RuntimeException exception) {
                failGeneration(generationId, WarningCode.BUILD_FAILED, "Qdrant 写入失败", exception.getMessage());
                throw new IllegalStateException("Claim 向量写入失败: " + exception.getMessage(), exception);
            }
            written += chunk.size();
        }

        // 6. 校验物理 collection 点数
        try {
            qdrantStore.verifyPointCount(physicalCollection, written);
        } catch (RuntimeException exception) {
            failGeneration(generationId, WarningCode.BUILD_FAILED, "Qdrant 写入校验失败", exception.getMessage());
            throw new IllegalStateException("Claim 向量写入失败: " + exception.getMessage(), exception);
        }

        // 7. updateStatus SUCCESS
        vectorStore.updateStatus(generationId, GenerationStatus.VERIFYING, written, null);
        vectorStore.updateStatus(generationId, GenerationStatus.SUCCESS, written, null);

        // 8. Qdrant alias 切换（高：Review 6——先于 SQLite ACTIVE；失败则 FAILED 且保持非 ACTIVE）
        try {
            qdrantStore.switchAlias(liveAlias, physicalCollection);
        } catch (RuntimeException exception) {
            failGeneration(generationId, WarningCode.ALIAS_SWITCH_FAILED, "alias 切换失败",
                    "SQLite 代际保持 SUCCESS（未 ACTIVE），旧 ACTIVE 与旧 alias 不变: " + exception.getMessage());
            throw new IllegalStateException("Claim 向量 alias 切换失败: " + exception.getMessage(), exception);
        }

        // 9. SQLite 权威提交在最后——markActive 成功即发布完成（此时 alias 与 SQLite 双确认一致）
        vectorStore.markActive(generationId, physicalCollection);
        return vectorStore.findGeneration(generationId)
                .orElseThrow(() -> new IllegalStateException("代际写入后消失: " + generationId));
    }

    private void failGeneration(String generationId, String warningCode, String summary, String detail) {
        String safe = (detail == null ? "" : detail).replace("\\", "\\\\").replace("\"", "\\\"");
        String warningsJson = "[{\"code\":\"" + warningCode + "\",\"message\":\"" + summary + ": " + safe + "\"}]";
        vectorStore.updateStatus(generationId, GenerationStatus.FAILED, 0, warningsJson);
    }

    /** 流式组合过程中的轻量 Claim 投影（不持有完整 KnowledgeClaimRecord，控制 20 万规模内存）。 */
    private record ComposedClaim(String claimId, String documentVersionId,
                                 SourceType sourceType, Authority authority, String status,
                                 String factKey, String subject, String predicate, String valueType,
                                 String unit, String updatedAt, String text, String textHash) {}
}
