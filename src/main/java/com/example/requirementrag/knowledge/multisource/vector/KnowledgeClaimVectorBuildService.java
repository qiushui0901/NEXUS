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
 * 构建流水线：两遍流式加载已发布 Claims（第一遍仅算指纹，第二遍组合文本）→
 * 指纹去重 → recordBuildStart → 分块嵌入并逐块写入物理 collection → 校验点数 →
 * updateStatus(SUCCESS) → Qdrant alias 切换 → markActive（SQLite 权威提交在最后）。
 * <p>
 * 安全保证：
 * <ul>
 *   <li>同 scope 并发构建串行化（64 条带 striped locks）。</li>
 *   <li>写入/校验/嵌入失败时 alias 不变，旧 ACTIVE 代际保留。</li>
 *   <li>高（Review 6）：alias 切换先于 markActive——切换失败则该代际 FAILED 且保持非 ACTIVE，
 *       不再向调用方返回“成功”；只有 alias 与 SQLite 双确认后才标 ACTIVE。</li>
 *   <li>高（Review 2）：alias 与物理 collection 按 project+version 隔离，不跨 scope 泄漏或误删；
 *       投影只读取已发布(PUBLISHED)资料版本（findPublishedClaimsByProjectVersionPage）。</li>
 *   <li>高（Review 4）：markActive 失败时补偿把 alias 切回前序目标；回滚 alias 失败时补偿把
 *       SQLite 恢复到原 ACTIVE 代际——消除 Qdrant/SQLite 分叉窗口。</li>
 *   <li>高（Review 8）：两遍分页流式读取 + 分块嵌入 + 逐块写点，20 万 Claim 构建
 *       不驻留全量文本与向量（任一时刻内存仅一页 + 一块）。</li>
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
        // 高（Review 4）：SQLite 先提交、Qdrant 后切换——若 alias 切换失败，SQLite 与 Qdrant 会出现分叉窗口。
        // 补偿：把 SQLite 回滚到本次回滚前的 ACTIVE 代际，恢复双端一致（best-effort）。
        Optional<ClaimVectorGenerationManifest> previousActive = vectorStore.findActiveGeneration(projectId, businessVersion);
        Optional<ClaimVectorGenerationManifest> restored = vectorStore.rollbackTo(target.generationId());
        if (restored.isPresent() && restored.get().physicalCollection() != null) {
            try {
                qdrantStore.rollbackAlias(
                        properties.liveAlias(projectId, businessVersion), restored.get().physicalCollection());
            } catch (RuntimeException exception) {
                // 补偿：alias 切换失败 → 把 SQLite 恢复到原 ACTIVE，保持双端一致（best-effort）
                LOGGER.warn("rollbackAlias 失败，尝试补偿恢复 SQLite 到原 ACTIVE 代际", exception);
                if (previousActive.isPresent() && previousActive.get().physicalCollection() != null) {
                    try {
                        vectorStore.rollbackTo(previousActive.get().generationId());
                    } catch (RuntimeException compensationFailure) {
                        LOGGER.error("rollback 补偿失败：SQLite 与 Qdrant 可能不一致", compensationFailure);
                    }
                }
                throw new IllegalStateException("Claim 向量 alias 回滚失败: " + exception.getMessage(), exception);
            }
        }
        return restored;
    }

    private ClaimVectorGenerationManifest doBuild(String projectId, String businessVersion) {
        String embeddingModelName = embeddingModel.getClass().getName() + ":" + embeddingModel.dimensions();
        int dimension = embeddingModel.dimensions();
        String liveAlias = properties.liveAlias(projectId, businessVersion);

        // ── 第一遍流式：仅计算指纹与输入集合，不驻留全量文本（高：Review 8——两遍流式）
        // 每页只保留该页文本计算 hash 后即释放；inputs 仅含轻量元数据（claimId/documentVersionId/textHash/updatedAt）。
        List<ClaimVectorGenerationInput> inputs = new ArrayList<>();
        long totalEligible = 0;
        long offset = 0;
        while (true) {
            List<KnowledgeClaimRecord> page = knowledgeStore.findPublishedClaimsByProjectVersionPage(
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
                    inputs.add(new ClaimVectorGenerationInput("pending", claim.claimId(),
                            claim.documentVersionId(), textHash, claim.updatedAt()));
                    totalEligible++;
                }
            }
            offset += page.size();
        }
        if (totalEligible == 0) {
            throw new IllegalStateException("无可投影 Claim: projectId=" + projectId
                    + " version=" + businessVersion
                    + "（无已发布(PUBLISHED)资料版本，或所有 Claim 来源类型被排除/文本为空）");
        }

        // 2. 指纹 + 可复用代际检查——同一指纹已有 SUCCESS/ACTIVE 代际则跳过（无需第二遍）
        String fingerprint = KnowledgeClaimVectorModels.computeInputFingerprint(
                inputs, properties.projectionSchemaVersion(), properties.textComposerVersion(),
                embeddingModelName, dimension);
        Optional<ClaimVectorGenerationManifest> reusable = vectorStore.findReusableGeneration(
                projectId, businessVersion, fingerprint,
                properties.projectionSchemaVersion(), embeddingModelName);
        if (reusable.isPresent()) {
            return reusable.get();
        }

        // 3. 生成代际 ID 并 recordBuildStart
        String generationId = "cv-" + UUID.randomUUID();
        String startedAt = Instant.now().toString();
        ClaimVectorGenerationManifest manifest = new ClaimVectorGenerationManifest(
                generationId, projectId, businessVersion, fingerprint,
                properties.projectionSchemaVersion(), properties.textComposerVersion(),
                embeddingModelName, dimension, null, GenerationStatus.BUILDING,
                (int) totalEligible, 0, "[]", startedAt, null, null);
        List<ClaimVectorGenerationInput> finalInputs = inputs.stream()
                .map(i -> new ClaimVectorGenerationInput(
                        generationId, i.claimId(), i.documentVersionId(),
                        i.textHash(), i.updatedAt()))
                .toList();
        vectorStore.recordBuildStart(manifest, finalInputs);

        // 4. 第二遍流式：重读分页 → 组合文本 → 分块嵌入 → 逐块写点（内存只驻留一页 + 一块）
        //    （高：Review 8——避免 20 万 Claim 全量文本驻留）
        //    （高：Review 3——逐项校验 claimId+documentVersionId+textHash，防等量替换漂移）
        String physicalCollection = liveAlias + "-" + Instant.now().toEpochMilli();
        boolean collectionReady = false;
        long written = 0;
        int inputIndex = 0;   // 与第一遍 inputs 序列逐项对齐的游标
        offset = 0;
        while (true) {
            List<KnowledgeClaimRecord> page = knowledgeStore.findPublishedClaimsByProjectVersionPage(
                    projectId, businessVersion, STREAM_PAGE_SIZE, offset);
            if (page.isEmpty()) {
                break;
            }
            List<ComposedClaim> pageComposed = new ArrayList<>(page.size());
            for (KnowledgeClaimRecord claim : page) {
                if (!textComposer.isSourceEligible(claim.sourceType())) {
                    continue;
                }
                Optional<String> text = textComposer.compose(claim, businessVersion);
                if (text.isPresent()) {
                    String textHash = KnowledgeClaimVectorTextComposer.textHash(text.get());
                    pageComposed.add(new ComposedClaim(claim.claimId(), claim.documentVersionId(),
                            claim.sourceType(), claim.authority(), claim.status(),
                            claim.factKey(), claim.subject(), claim.predicate(), claim.valueType(),
                            claim.unit(), claim.updatedAt(), text.get(), textHash));
                }
            }
            // 高（Review 3）：逐项与第一遍 inputs 对齐校验——等量替换（如 A→C）数量不变也会被发现
            for (ComposedClaim composed : pageComposed) {
                if (inputIndex >= inputs.size()) {
                    driftAndFail(generationId, "第二遍出现第一遍不存在的 Claim", composed.claimId(),
                            composed.documentVersionId(), composed.textHash(), null, null, null);
                }
                ClaimVectorGenerationInput expected = inputs.get(inputIndex);
                if (!expected.claimId().equals(composed.claimId())
                        || !expected.documentVersionId().equals(composed.documentVersionId())
                        || !expected.textHash().equals(composed.textHash())) {
                    driftAndFail(generationId, "第二遍 Claim 与第一遍不一致（数据漂移或等量替换）",
                            composed.claimId(), composed.documentVersionId(), composed.textHash(),
                            expected.claimId(), expected.documentVersionId(), expected.textHash());
                }
                inputIndex++;
            }
            for (int start = 0; start < pageComposed.size(); start += EMBED_CHUNK_SIZE) {
                int end = Math.min(start + EMBED_CHUNK_SIZE, pageComposed.size());
                List<ComposedClaim> chunk = pageComposed.subList(start, end);
                List<String> texts = chunk.stream().map(ComposedClaim::text).toList();
                List<float[]> vectors;
                try {
                    vectors = embeddingBatcher.embedAll(texts);
                } catch (RuntimeException exception) {
                    failGeneration(generationId, WarningCode.BUILD_FAILED, "嵌入失败", exception.getMessage());
                    cleanupFailedCollection(physicalCollection, collectionReady);
                    throw new IllegalStateException("Claim 向量嵌入失败: " + exception.getMessage(), exception);
                }
                if (vectors.size() != chunk.size()) {
                    failGeneration(generationId, WarningCode.BUILD_FAILED, "嵌入数量不一致",
                            "期望 " + chunk.size() + " 实际 " + vectors.size());
                    cleanupFailedCollection(physicalCollection, collectionReady);
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
                    cleanupFailedCollection(physicalCollection, collectionReady);
                    throw new IllegalStateException("Claim 向量写入失败: " + exception.getMessage(), exception);
                }
                written += chunk.size();
            }
            offset += page.size();
        }
        if (inputIndex != inputs.size()) {
            // 高（Review 8）：两遍读取间数据不应漂移（分页含 claim_id 唯一尾排序，边界确定）；
            // 若漂移则拒绝发布，避免 manifest 计数与实际投影不一致。
            driftAndFail(generationId, "第二遍读取数量漂移", null, null, null,
                    null, null, null);
        }
        if (written != totalEligible) {
            driftAndFail(generationId, "第二遍写入数量与第一遍统计不一致", null, null, null,
                    null, null, null);
        }

        // 5. 校验物理 collection 点数
        try {
            qdrantStore.verifyPointCount(physicalCollection, (int) written);
        } catch (RuntimeException exception) {
            failGeneration(generationId, WarningCode.BUILD_FAILED, "Qdrant 写入校验失败", exception.getMessage());
            cleanupFailedCollection(physicalCollection, collectionReady);
            throw new IllegalStateException("Claim 向量写入失败: " + exception.getMessage(), exception);
        }

        // 6. updateStatus SUCCESS
        vectorStore.updateStatus(generationId, GenerationStatus.VERIFYING, (int) written, null);
        vectorStore.updateStatus(generationId, GenerationStatus.SUCCESS, (int) written, null);

        // 7. Qdrant alias 切换（高：Review 6——先于 SQLite ACTIVE；失败则 FAILED 且保持非 ACTIVE）
        String previousAliasTarget = null;
        try {
            previousAliasTarget = qdrantStore.aliasTarget(liveAlias);
        } catch (RuntimeException ignored) {
            // alias 尚不存在或不可达——切换失败补偿时按无前序目标处理
        }
        try {
            qdrantStore.switchAlias(liveAlias, physicalCollection);
        } catch (RuntimeException exception) {
            failGeneration(generationId, WarningCode.ALIAS_SWITCH_FAILED, "alias 切换失败",
                    "SQLite 代际保持 SUCCESS（未 ACTIVE），旧 ACTIVE 与旧 alias 不变: " + exception.getMessage());
            cleanupFailedCollection(physicalCollection, collectionReady);
            throw new IllegalStateException("Claim 向量 alias 切换失败: " + exception.getMessage(), exception);
        }

        // 8. SQLite 权威提交在最后——markActive 成功即发布完成（此时 alias 与 SQLite 双确认一致）
        //    高（Review 4）：若 markActive 失败，Qdrant alias 已切到新代际而 SQLite 仍旧 ACTIVE——
        //    补偿把 alias 切回前序目标，消除分叉窗口后重新抛出。
        try {
            vectorStore.markActive(generationId, physicalCollection);
        } catch (RuntimeException exception) {
            LOGGER.error("markActive 失败，补偿回滚 alias 到前序目标: {}", previousAliasTarget, exception);
            if (previousAliasTarget != null) {
                try {
                    qdrantStore.rollbackAlias(liveAlias, previousAliasTarget);
                    // 高（Review 5）：alias 已切回前序目标，本代际物理 collection 成孤儿——删除
                    cleanupFailedCollection(physicalCollection, collectionReady);
                } catch (RuntimeException compensationFailure) {
                    LOGGER.error("markActive 补偿回滚 alias 失败，SQLite 与 Qdrant 可能不一致", compensationFailure);
                }
            } else {
                // 高（Review 4）：首次构建无前序目标——删除新 alias（恢复无 alias 状态），
                // 并清理本代际物理 collection，避免残留错误 alias/孤儿集合。
                try {
                    qdrantStore.deleteAlias(liveAlias);
                    cleanupFailedCollection(physicalCollection, collectionReady);
                } catch (RuntimeException compensationFailure) {
                    LOGGER.error("markActive 补偿删除 alias 失败，SQLite 与 Qdrant 可能不一致", compensationFailure);
                }
            }
            throw new IllegalStateException("Claim 向量代际激活失败: " + exception.getMessage(), exception);
        }
        return vectorStore.findGeneration(generationId)
                .orElseThrow(() -> new IllegalStateException("代际写入后消失: " + generationId));
    }

    /**
     * 高（Review 5）：失败构建清理当前代际的半成品物理 collection（best-effort）。
     * 仅在已建集合（collectionReady）时删除；嵌入首块即失败时集合尚未创建，无需清理。
     */
    private void cleanupFailedCollection(String physicalCollection, boolean collectionReady) {
        if (!collectionReady || physicalCollection == null) {
            return;
        }
        try {
            qdrantStore.deleteCollection(physicalCollection);
            LOGGER.info("已清理失败构建的半成品物理 collection {}", physicalCollection);
        } catch (RuntimeException exception) {
            LOGGER.warn("清理失败构建的半成品物理 collection {} 失败: {}",
                    physicalCollection, exception.getMessage());
        }
    }

    /** 高（Review 3）：两遍流式逐项漂移——拒绝发布并标记 FAILED。 */
    private void driftAndFail(String generationId, String summary,
                              String actualClaimId, String actualDocVersion, String actualHash,
                              String expectedClaimId, String expectedDocVersion, String expectedHash) {
        String detail = "第一遍项: " + safe(expectedClaimId) + "|" + safe(expectedDocVersion) + "|" + safe(expectedHash)
                + "；第二遍项: " + safe(actualClaimId) + "|" + safe(actualDocVersion) + "|" + safe(actualHash);
        failGeneration(generationId, WarningCode.BUILD_FAILED, "第二遍流式漂移——" + summary, detail);
        throw new IllegalStateException("Claim 向量两遍流式漂移: " + summary);
    }

    private String safe(String value) {
        return value == null ? "" : value;
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
