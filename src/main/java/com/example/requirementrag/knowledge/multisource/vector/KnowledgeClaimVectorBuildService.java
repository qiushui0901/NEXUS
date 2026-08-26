package com.example.requirementrag.knowledge.multisource.vector;

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
 * 构建流水线：加载 Claims → 文本组合 → 指纹去重 → recordBuildStart → 批量嵌入 →
 * 写入物理 collection → 校验点数 → updateStatus(SUCCESS) → markActive(SQLite 权威提交) →
 * switchAlias(Qdrant 跟随 SQLite)。
 * <p>
 * 安全保证：
 * <ul>
 *   <li>同 scope 并发构建串行化（64 条带 striped locks）。</li>
 *   <li>写入/校验失败时 alias 不变，旧 ACTIVE 代际保留。</li>
 *   <li>SQLite 是权威——markActive 先于 Qdrant alias 切换。</li>
 *   <li>Qdrant alias 切换失败时 SQLite 已 ACTIVE，reconciliation 可修复。</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(prefix = "app.rag.multi-source.claim-vector", name = "build-enabled",
        havingValue = "true", matchIfMissing = false)
public class KnowledgeClaimVectorBuildService {

    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(KnowledgeClaimVectorBuildService.class);
    private static final int BUILD_LOCK_STRIPES = 64;
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
     * 回滚到上一个 RETIRED 代际（如新代际发现质量问题）。
     * SQLite rollbackTo 恢复 RETIRED→ACTIVE，Qdrant rollbackAlias 切回旧物理 collection。
     */
    public Optional<ClaimVectorGenerationManifest> rollback(String projectId, String businessVersion) {
        List<ClaimVectorGenerationManifest> retired = vectorStore.listRetiredForRollback(projectId, businessVersion);
        if (retired.isEmpty()) {
            return Optional.empty();
        }
        ClaimVectorGenerationManifest target = retired.get(retired.size() - 1);
        Optional<ClaimVectorGenerationManifest> restored = vectorStore.rollbackTo(target.generationId());
        if (restored.isPresent() && restored.get().physicalCollection() != null) {
            qdrantStore.rollbackAlias(properties.alias(), restored.get().physicalCollection());
        }
        return restored;
    }

    /** 查询当前 ACTIVE 代际。 */
    public Optional<ClaimVectorGenerationManifest> findActive(String projectId, String businessVersion) {
        return vectorStore.findActiveGeneration(projectId, businessVersion);
    }

    // ── 内部 ─────────────────────────────────────────────────────────────

    private ClaimVectorGenerationManifest doBuild(String projectId, String businessVersion) {
        String embeddingModelName = embeddingModel.getClass().getName() + ":" + embeddingModel.dimensions();
        int dimension = embeddingModel.dimensions();

        // 1. 加载 Claims 并组合文本
        List<KnowledgeClaimRecord> claims = knowledgeStore.findClaimsByProjectVersion(projectId, businessVersion);
        List<ComposedClaim> composed = new ArrayList<>();
        for (KnowledgeClaimRecord claim : claims) {
            if (!textComposer.isSourceEligible(claim.sourceType())) {
                continue;
            }
            Optional<String> text = textComposer.compose(claim, businessVersion);
            if (text.isPresent()) {
                composed.add(new ComposedClaim(claim, text.get(),
                        KnowledgeClaimVectorTextComposer.textHash(text.get())));
            }
        }
        if (composed.isEmpty()) {
            throw new IllegalStateException("无可投影 Claim: projectId=" + projectId
                    + " version=" + businessVersion + "（所有 Claim 来源类型被排除或文本为空）");
        }

        // 2. 构建输入集合 + 指纹
        List<ClaimVectorGenerationInput> inputs = composed.stream()
                .map(c -> new ClaimVectorGenerationInput(
                        "pending", c.claim.claimId(), c.claim.documentVersionId(),
                        c.textHash, c.claim.updatedAt()))
                .toList();
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

        // 5. 批量嵌入
        List<String> texts = composed.stream().map(c -> c.text).toList();
        List<float[]> vectors;
        try {
            vectors = embeddingBatcher.embedAll(texts);
        } catch (RuntimeException exception) {
            failGeneration(generationId, WarningCode.BUILD_FAILED, "嵌入失败", exception.getMessage());
            throw new IllegalStateException("Claim 向量嵌入失败: " + exception.getMessage(), exception);
        }
        if (vectors.size() != composed.size()) {
            failGeneration(generationId, WarningCode.BUILD_FAILED, "嵌入数量不一致",
                    "期望 " + composed.size() + " 实际 " + vectors.size());
            throw new IllegalStateException("Claim 向量嵌入数量不一致");
        }

        // 6. 构建 Qdrant 点列表
        List<KnowledgeClaimVectorPoint> points = new ArrayList<>(composed.size());
        for (int i = 0; i < composed.size(); i++) {
            ComposedClaim c = composed.get(i);
            points.add(new KnowledgeClaimVectorPoint(
                    projectId, businessVersion, c.claim.claimId(),
                    c.claim.documentVersionId(), c.claim.sourceType(), c.claim.authority(),
                    c.claim.status(), c.claim.factKey(), c.claim.subject(), c.claim.predicate(),
                    c.claim.valueType(), c.claim.unit(), List.of(),
                    generationId, properties.projectionSchemaVersion(),
                    embeddingModelName, c.textHash));
        }

        // 7. 写入物理 collection + 校验
        String physicalCollection = properties.alias() + "-" + Instant.now().toEpochMilli();
        try {
            qdrantStore.publishPhysicalCollection(physicalCollection, points, vectors, dimension);
        } catch (RuntimeException exception) {
            failGeneration(generationId, WarningCode.BUILD_FAILED, "Qdrant 写入校验失败", exception.getMessage());
            throw new IllegalStateException("Claim 向量写入失败: " + exception.getMessage(), exception);
        }

        // 8. updateStatus SUCCESS + markActive（SQLite 权威提交）
        vectorStore.updateStatus(generationId, GenerationStatus.VERIFYING, 0, null);
        vectorStore.updateStatus(generationId, GenerationStatus.SUCCESS, points.size(), null);
        vectorStore.markActive(generationId, physicalCollection);

        // 9. Qdrant alias 切换（跟随 SQLite 权威）
        try {
            qdrantStore.switchAlias(properties.alias(), physicalCollection);
        } catch (RuntimeException exception) {
            // SQLite 已 ACTIVE 但 Qdrant alias 未切换——reconciliation 需修复。
            LOGGER.warn("Qdrant alias 切换失败 (generationId={}): {} — SQLite 已 ACTIVE, "
                    + "Qdrant 仍指向旧 collection, 需 reconciliation", generationId, exception.getMessage());
        }

        // 10. 返回最终 manifest
        return vectorStore.findGeneration(generationId)
                .orElseThrow(() -> new IllegalStateException("代际写入后消失: " + generationId));
    }

    private void failGeneration(String generationId, String warningCode, String summary, String detail) {
        String safe = (detail == null ? "" : detail).replace("\\", "\\\\").replace("\"", "\\\"");
        String warningsJson = "[{\"code\":\"" + warningCode + "\",\"message\":\"" + summary + ": " + safe + "\"}]";
        vectorStore.updateStatus(generationId, GenerationStatus.FAILED, 0, warningsJson);
    }

    private record ComposedClaim(KnowledgeClaimRecord claim, String text, String textHash) {}
}
