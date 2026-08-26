package com.example.requirementrag.knowledge.multisource.vector;

import com.example.requirementrag.knowledge.multisource.vector.KnowledgeClaimVectorModels.ClaimVectorGenerationManifest;
import com.example.requirementrag.knowledge.multisource.vector.KnowledgeClaimVectorModels.GenerationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.example.requirementrag.knowledge.multisource.vector.ClaimVectorShadowEvaluator.ScopeStats;

/**
 * Claim 向量投影质量门——在发布前或日常运维中验证系统健康度。
 *
 * <p>检查项：</p>
 * <ol>
 *   <li>活跃代际存在且状态为 ACTIVE</li>
 *   <li>indexedPointCount == expectedPointCount（构建完整性）</li>
 *   <li>Qdrant alias 指向活跃物理 collection（alias 健康）</li>
 *   <li>物理 collection 点数与 SQLite manifest 一致（无孤儿/丢失点）</li>
 *   <li>影子评估器有足够数据（如果 shadow-query-enabled）</li>
 * </ol>
 *
 * <p>质量门不修改任何状态，只读取和报告。检查结果以 {@link QualityGateReport} 返回，
 * 含所有检查项的 pass/fail 与详细信息。全部 pass 时 {@code readyToPublish=true}。</p>
 *
 * @see KnowledgeClaimVectorBuildService
 * @see ClaimVectorShadowEvaluator
 * @since 0.9.6
 */
@Component
@ConditionalOnProperty(prefix = "app.rag.multi-source.claim-vector", name = "enabled", havingValue = "true")
public class ClaimVectorQualityGate {

    private static final Logger log = LoggerFactory.getLogger(ClaimVectorQualityGate.class);

    private final SQLiteKnowledgeClaimVectorStore sqliteStore;
    private final KnowledgeClaimVectorQdrantStore qdrantStore;
    private final ClaimVectorShadowEvaluator shadowEvaluator;
    private final KnowledgeClaimVectorProperties properties;

    /**
     * 高（Review 7）：shadowEvaluator 在 shadow-query-enabled=false 时不装配，因此此处必须 {@code @Nullable}
     * 可选注入——否则 enabled=true + shadow-query-enabled=false 的构建阶段会因缺少 Bean 导致启动失败。
     * 影子检查仅在 shadowEvaluator 非 null 且 shadowQueryEnabled 时才执行。
     */
    public ClaimVectorQualityGate(SQLiteKnowledgeClaimVectorStore sqliteStore,
                                  KnowledgeClaimVectorQdrantStore qdrantStore,
                                  @Nullable ClaimVectorShadowEvaluator shadowEvaluator,
                                  KnowledgeClaimVectorProperties properties) {
        this.sqliteStore = sqliteStore;
        this.qdrantStore = qdrantStore;
        this.shadowEvaluator = shadowEvaluator;
        this.properties = properties;
    }

    /**
     * 对指定 project + version 执行全量质量检查。
     *
     * @param projectId 项目 ID
     * @param businessVersion 业务版本
     * @return 质量门报告
     */
    public QualityGateReport check(String projectId, String businessVersion) {
        List<QualityCheck> checks = new ArrayList<>();

        // 1. 活跃代际存在且 ACTIVE
        QualityCheck activeCheck = checkActiveGeneration(projectId, businessVersion);
        checks.add(activeCheck);
        if (!activeCheck.passed()) {
            return new QualityGateReport(projectId, businessVersion, checks, false);
        }

        ClaimVectorGenerationManifest manifest = sqliteStore
                .findActiveGeneration(projectId, businessVersion).orElseThrow();

        // 2. 构建完整性：indexed == expected
        checks.add(checkPointCount(manifest));

        // 3. alias 健康度
        checks.add(checkAlias(manifest));

        // 4. 物理点数一致性
        checks.add(checkPhysicalConsistency(manifest));

        // 5. 影子评估器数据量（可选）
        if (properties.shadowQueryEnabled() && shadowEvaluator != null) {
            checks.add(checkShadowData(projectId, businessVersion));
        }

        boolean allPassed = checks.stream().allMatch(QualityCheck::passed);
        return new QualityGateReport(projectId, businessVersion, checks, allPassed);
    }

    private QualityCheck checkActiveGeneration(String projectId, String businessVersion) {
        Optional<ClaimVectorGenerationManifest> active =
                sqliteStore.findActiveGeneration(projectId, businessVersion);
        if (active.isEmpty()) {
            return QualityCheck.fail("ACTIVE_GENERATION",
                    "No active generation found for %s/%s".formatted(projectId, businessVersion));
        }
        ClaimVectorGenerationManifest m = active.get();
        if (m.status() != GenerationStatus.ACTIVE) {
            return QualityCheck.fail("ACTIVE_GENERATION",
                    "Generation %s status is %s, expected ACTIVE".formatted(
                            m.generationId(), m.status()));
        }
        return QualityCheck.pass("ACTIVE_GENERATION",
                "Generation %s is ACTIVE (%d points)".formatted(
                        m.generationId(), m.indexedPointCount()));
    }

    private QualityCheck checkPointCount(ClaimVectorGenerationManifest manifest) {
        if (manifest.indexedPointCount() != manifest.expectedPointCount()) {
            return QualityCheck.fail("POINT_COUNT",
                    "indexed %d != expected %d".formatted(
                            manifest.indexedPointCount(), manifest.expectedPointCount()));
        }
        return QualityCheck.pass("POINT_COUNT",
                "%d points indexed as expected".formatted(manifest.indexedPointCount()));
    }

    private QualityCheck checkAlias(ClaimVectorGenerationManifest manifest) {
        // 高（Review 2）：alias 按 project+version 隔离，检查本 scope 的 live alias
        String alias = properties.liveAlias(manifest.projectId(), manifest.businessVersion());
        String expectedPhysical = manifest.physicalCollection();
        String actualTarget = qdrantStore.aliasTarget(alias);
        if (actualTarget == null || actualTarget.isBlank()) {
            return QualityCheck.fail("ALIAS_HEALTH",
                    "Alias '%s' does not point to any collection".formatted(alias));
        }
        if (!actualTarget.equals(expectedPhysical)) {
            return QualityCheck.fail("ALIAS_HEALTH",
                    "Alias '%s' -> '%s', expected '%s'".formatted(
                            alias, actualTarget, expectedPhysical));
        }
        return QualityCheck.pass("ALIAS_HEALTH",
                "Alias '%s' -> '%s' (matches active generation)".formatted(
                        alias, actualTarget));
    }

    private QualityCheck checkPhysicalConsistency(ClaimVectorGenerationManifest manifest) {
        long physicalCount = qdrantStore.countPointsIfAvailable(manifest.physicalCollection());
        if (physicalCount < 0) {
            return QualityCheck.fail("PHYSICAL_CONSISTENCY",
                    "Cannot count points in '%s' (Qdrant unavailable)".formatted(
                            manifest.physicalCollection()));
        }
        if (physicalCount != manifest.indexedPointCount()) {
            return QualityCheck.fail("PHYSICAL_CONSISTENCY",
                    "Physical collection has %d points, SQLite manifest says %d".formatted(
                            physicalCount, manifest.indexedPointCount()));
        }
        return QualityCheck.pass("PHYSICAL_CONSISTENCY",
                "Physical collection '%s' has %d points (matches manifest)".formatted(
                        manifest.physicalCollection(), physicalCount));
    }

    private QualityCheck checkShadowData(String projectId, String businessVersion) {
        ScopeStats stats = shadowEvaluator.scopeMetric(projectId, businessVersion);
        if (stats == null) {
            return QualityCheck.fail("SHADOW_DATA",
                    "No shadow queries recorded for %s/%s".formatted(projectId, businessVersion));
        }
        if (stats.queryCount() < 20) {
            return QualityCheck.fail("SHADOW_DATA",
                    "Only %d shadow queries (need ≥20 for publishIfReady)".formatted(stats.queryCount()));
        }
        double overlapRate = stats.totalVectorHits() > 0
                ? (double) stats.totalOverlap() / stats.totalVectorHits() : 0.0;
        return QualityCheck.pass("SHADOW_DATA",
                "%d shadow queries, %d total vector hits, %.1f%% overlap, %.1f%% recall contribution".formatted(
                        stats.queryCount(), stats.totalVectorHits(),
                        overlapRate * 100, stats.vectorRecallContributionRate() * 100));
    }

    /**
     * 质量门报告。
     *
     * @param projectId 项目 ID
     * @param businessVersion 业务版本
     * @param checks 各检查项结果
     * @param readyToPublish 全部通过时为 true
     */
    public record QualityGateReport(String projectId, String businessVersion,
                                    List<QualityCheck> checks, boolean readyToPublish) {
        public long passedCount() { return checks.stream().filter(QualityCheck::passed).count(); }
        public long failedCount() { return checks.stream().filter(c -> !c.passed()).count(); }
    }

    /**
     * 单项检查结果。
     *
     * @param name 检查项名称
     * @param passed 是否通过
     * @param detail 详细信息
     */
    public record QualityCheck(String name, boolean passed, String detail) {
        static QualityCheck pass(String name, String detail) { return new QualityCheck(name, true, detail); }
        static QualityCheck fail(String name, String detail) { return new QualityCheck(name, false, detail); }
    }
}
