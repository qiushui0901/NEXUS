package com.example.requirementrag.knowledge.multisource.vector;

import com.example.requirementrag.knowledge.multisource.vector.KnowledgeClaimVectorModels.ClaimVectorGenerationInput;
import com.example.requirementrag.knowledge.multisource.vector.KnowledgeClaimVectorModels.ClaimVectorGenerationManifest;
import com.example.requirementrag.knowledge.multisource.vector.KnowledgeClaimVectorModels.GenerationStatus;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Claim 向量投影代际 manifest 的 SQLite 存储（§6.1）。
 * <p>
 * 与 {@code MultiSourceKnowledgeStore} 共用同一数据库文件（{@code data/multi-source-knowledge.db}），
 * 新增 {@code knowledge_claim_vector_generation} + {@code knowledge_claim_vector_generation_input} 两张表。
 * Manifest 表不复用 {@code knowledge_active_version}——一个投影包含多个 documentVersion 的 Claim。
 * <p>
 * 写事务统一使用 {@code begin immediate} 防止并发构建写冲突（与语义 store 一致）。
 */
@Component
public class SQLiteKnowledgeClaimVectorStore {

    private final String databasePath;
    /** findActiveGeneration 按当前配置 schema 过滤（投影契约升级后旧 ACTIVE 代际不参与检索）。 */
    private final String projectionSchemaVersion;

    public SQLiteKnowledgeClaimVectorStore(KnowledgeClaimVectorProperties properties) {
        this.databasePath = properties.databasePath();
        this.projectionSchemaVersion = properties.projectionSchemaVersion();
        initialize();
    }

    // ===== 初始化 =====

    private void initialize() {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA busy_timeout=5000");
            statement.executeUpdate("""
                    create table if not exists knowledge_claim_vector_generation(
                      generation_id text primary key,
                      project_id text not null,
                      business_version text not null,
                      input_fingerprint text not null,
                      projection_schema_version text not null,
                      text_composer_version text not null,
                      embedding_model text not null,
                      embedding_dimension integer not null,
                      physical_collection text,
                      status text not null,
                      expected_point_count integer not null,
                      indexed_point_count integer not null,
                      warnings_json text not null default '[]',
                      started_at text not null,
                      finished_at text,
                      published_at text,
                      build_scope text not null default 'ACTIVE_DOC',
                      unique(project_id, business_version, input_fingerprint,
                             projection_schema_version, embedding_model)
                    )
                    """);
            // 轻量迁移：老库（0.9.6 及更早）已建表但无 build_scope 列 → ALTER 补列，默认 ACTIVE_DOC
            try (ResultSet columns = statement.executeQuery(
                    "PRAGMA table_info(knowledge_claim_vector_generation)")) {
                boolean hasBuildScope = false;
                while (columns.next()) {
                    if ("build_scope".equals(columns.getString("name"))) {
                        hasBuildScope = true;
                        break;
                    }
                }
                if (!hasBuildScope) {
                    statement.executeUpdate("alter table knowledge_claim_vector_generation"
                            + " add column build_scope text not null default 'ACTIVE_DOC'");
                }
            }
            statement.executeUpdate("""
                    create table if not exists knowledge_claim_vector_generation_input(
                      generation_id text not null,
                      claim_id text not null,
                      document_version_id text not null,
                      text_hash text not null,
                      updated_at text,
                      primary key(generation_id, claim_id),
                      foreign key(generation_id) references knowledge_claim_vector_generation(generation_id)
                    )
                    """);
            statement.executeUpdate("create index if not exists idx_claim_vector_gen_scope"
                    + " on knowledge_claim_vector_generation(project_id, business_version, status)");
            statement.executeUpdate("create index if not exists idx_claim_vector_gen_input_claim"
                    + " on knowledge_claim_vector_generation_input(claim_id)");
        } catch (SQLException exception) {
            throw new IllegalStateException("无法初始化 Claim 向量投影 manifest 表", exception);
        }
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
        }
        return connection;
    }

    // ===== 代际 CRUD =====

    /**
     * 记录构建开始：插入 BUILDING 代际 + 批量保存输入集合。
     * 单事务 {@code begin immediate} 保证输入集合与代际记录原子可见。
     */
    public void recordBuildStart(ClaimVectorGenerationManifest manifest,
                                 List<ClaimVectorGenerationInput> inputs) {
        try (Connection connection = open()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("begin immediate");
            }
            try (PreparedStatement insert = connection.prepareStatement("""
                    insert into knowledge_claim_vector_generation(
                      generation_id, project_id, business_version, input_fingerprint,
                      projection_schema_version, text_composer_version, embedding_model,
                      embedding_dimension, physical_collection, status,
                      expected_point_count, indexed_point_count, warnings_json,
                      started_at, finished_at, published_at, build_scope
                    ) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """)) {
                insert.setString(1, manifest.generationId());
                insert.setString(2, manifest.projectId());
                insert.setString(3, manifest.businessVersion());
                insert.setString(4, manifest.inputFingerprint());
                insert.setString(5, manifest.projectionSchemaVersion());
                insert.setString(6, manifest.textComposerVersion());
                insert.setString(7, manifest.embeddingModel());
                insert.setInt(8, manifest.embeddingDimension());
                insert.setString(9, manifest.physicalCollection());
                insert.setString(10, manifest.status().name());
                insert.setInt(11, manifest.expectedPointCount());
                insert.setInt(12, manifest.indexedPointCount());
                insert.setString(13, manifest.warningsJson());
                insert.setString(14, manifest.startedAt());
                insert.setString(15, manifest.finishedAt());
                insert.setString(16, manifest.publishedAt());
                insert.setString(17, manifest.buildScope());
                insert.executeUpdate();
            }
            saveInputs(connection, manifest.generationId(), inputs);
            try (Statement commit = connection.createStatement()) {
                commit.execute("commit");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("记录 Claim 向量代际构建失败", exception);
        }
    }

    /**
     * 高（Review 9）：同一构建意图（scope+指纹+schema+模型）重试时，删除不可复用的旧代际
     * （FAILED / BUILDING 残留 / SUCCESS 但无物理集合的旧 bug 残留），
     * 使 recordBuildStart 不受 unique(project_id, business_version, input_fingerprint, ...) 约束阻塞。
     * ACTIVE 代际不受影响（可复用路径不会走到重试）。
     */
    public void deleteSupersededGenerations(String projectId, String businessVersion, String inputFingerprint,
                                            String projectionSchemaVersion, String embeddingModel) {
        try (Connection connection = open()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("begin immediate");
            }
            try (PreparedStatement inputs = connection.prepareStatement("""
                    delete from knowledge_claim_vector_generation_input
                    where generation_id in (
                      select generation_id from knowledge_claim_vector_generation
                      where project_id=? and business_version=? and input_fingerprint=?
                        and projection_schema_version=? and embedding_model=?
                        and status != 'ACTIVE'
                    )
                    """)) {
                inputs.setString(1, projectId);
                inputs.setString(2, businessVersion);
                inputs.setString(3, inputFingerprint);
                inputs.setString(4, projectionSchemaVersion);
                inputs.setString(5, embeddingModel);
                inputs.executeUpdate();
            }
            try (PreparedStatement generations = connection.prepareStatement("""
                    delete from knowledge_claim_vector_generation
                    where project_id=? and business_version=? and input_fingerprint=?
                      and projection_schema_version=? and embedding_model=?
                      and status != 'ACTIVE'
                    """)) {
                generations.setString(1, projectId);
                generations.setString(2, businessVersion);
                generations.setString(3, inputFingerprint);
                generations.setString(4, projectionSchemaVersion);
                generations.setString(5, embeddingModel);
                generations.executeUpdate();
            }
            try (Statement commit = connection.createStatement()) {
                commit.execute("commit");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("清理被取代的 Claim 向量代际失败", exception);
        }
    }

    /**
     * 更新代际状态（VERIFYING/SUCCESS/FAILED）及关联字段。
     * 不修改 generation_id/project_id/business_version/input_fingerprint——这些不可变。
     */
    public void updateStatus(String generationId, GenerationStatus status,
                             int indexedPointCount, String warningsJson) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     update knowledge_claim_vector_generation
                     set status=?, indexed_point_count=?, warnings_json=?,
                         finished_at=coalesce(finished_at, ?)
                     where generation_id=?
                     """)) {
            statement.setString(1, status.name());
            statement.setInt(2, indexedPointCount);
            statement.setString(3, warningsJson == null || warningsJson.isBlank() ? "[]" : warningsJson);
            statement.setString(4, Instant.now().toString());
            statement.setString(5, generationId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("更新代际状态失败 generationId=" + generationId, exception);
        }
    }

    /**
     * 标记代际为 ACTIVE 并退役前一代际（§6.3 步骤 8）。
     * 单事务保证同一 scope 下至多一个 ACTIVE。
     *
     * @return 被退役的前一代际（可能为空）；用于物理集合保留/清理决策
     */
    public Optional<ClaimVectorGenerationManifest> markActive(String generationId, String physicalCollection) {
        try (Connection connection = open()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("begin immediate");
            }
            // 查找要激活的代际
            ClaimVectorGenerationManifest target = findGeneration(connection, generationId);
            if (target == null) {
                throw new IllegalStateException("代际不存在: " + generationId);
            }
            // 退役同一 scope 的旧 ACTIVE
            Optional<ClaimVectorGenerationManifest> previous = Optional.empty();
            try (PreparedStatement retire = connection.prepareStatement("""
                    update knowledge_claim_vector_generation
                    set status='RETIRED'
                    where project_id=? and business_version=? and status='ACTIVE' and generation_id<>?
                    """)) {
                retire.setString(1, target.projectId());
                retire.setString(2, target.businessVersion());
                retire.setString(3, generationId);
                int retired = retire.executeUpdate();
                if (retired > 0) {
                    previous = findPreviousActive(connection, target.projectId(), target.businessVersion(), generationId);
                }
            }
            // 激活新代际
            try (PreparedStatement activate = connection.prepareStatement("""
                    update knowledge_claim_vector_generation
                    set status='ACTIVE', physical_collection=?, published_at=?
                    where generation_id=?
                    """)) {
                activate.setString(1, physicalCollection);
                activate.setString(2, Instant.now().toString());
                activate.setString(3, generationId);
                activate.executeUpdate();
            }
            try (Statement commit = connection.createStatement()) {
                commit.execute("commit");
            }
            return previous;
        } catch (SQLException exception) {
            throw new IllegalStateException("激活代际失败 generationId=" + generationId, exception);
        }
    }

    /**
     * 回滚：将指定 RETIRED 代际重新标记为 ACTIVE，将当前 ACTIVE 标记为 RETIRED。
     */
    public Optional<ClaimVectorGenerationManifest> rollbackTo(String generationId) {
        try (Connection connection = open()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("begin immediate");
            }
            ClaimVectorGenerationManifest target = findGeneration(connection, generationId);
            if (target == null) {
                throw new IllegalStateException("代际不存在: " + generationId);
            }
            // 当前 ACTIVE → RETIRED
            try (PreparedStatement retire = connection.prepareStatement("""
                    update knowledge_claim_vector_generation
                    set status='RETIRED'
                    where project_id=? and business_version=? and status='ACTIVE'
                    """)) {
                retire.setString(1, target.projectId());
                retire.setString(2, target.businessVersion());
                retire.executeUpdate();
            }
            // 目标 RETIRED → ACTIVE
            try (PreparedStatement activate = connection.prepareStatement("""
                    update knowledge_claim_vector_generation
                    set status='ACTIVE'
                    where generation_id=?
                    """)) {
                activate.setString(1, generationId);
                activate.executeUpdate();
            }
            try (Statement commit = connection.createStatement()) {
                commit.execute("commit");
            }
            return Optional.ofNullable(findGeneration(connection, generationId));
        } catch (SQLException exception) {
            throw new IllegalStateException("回滚代际失败 generationId=" + generationId, exception);
        }
    }

    // ===== 查询 =====

    /** 查找指定 scope 的当前 ACTIVE 代际。 */
    public Optional<ClaimVectorGenerationManifest> findActiveGeneration(String projectId, String businessVersion) {
        // High：只认可当前配置 schema 的 ACTIVE 代际——投影契约（schema）升级后，旧 schema 的 ACTIVE
        // 代际（如 v1 点 ID 算法产物）不得继续服务检索（fail-close，由调用方按无代际处理并告警）。
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     select * from knowledge_claim_vector_generation
                     where project_id=? and business_version=? and status='ACTIVE'
                       and projection_schema_version=?
                     """)) {
            statement.setString(1, projectId);
            statement.setString(2, businessVersion);
            statement.setString(3, projectionSchemaVersion);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(manifest(rows)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("查询 ACTIVE 代际失败", exception);
        }
    }

    /** 按 ID 查找代际。 */
    public Optional<ClaimVectorGenerationManifest> findGeneration(String generationId) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     select * from knowledge_claim_vector_generation where generation_id=?
                     """)) {
            statement.setString(1, generationId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(manifest(rows)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("查询代际失败 generationId=" + generationId, exception);
        }
    }

    /** 查找指定 scope 的最近一次代际（任何状态，按 started_at 降序）。 */
    public Optional<ClaimVectorGenerationManifest> findLatestGeneration(String projectId, String businessVersion) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     select * from knowledge_claim_vector_generation
                     where project_id=? and business_version=?
                     order by started_at desc limit 1
                     """)) {
            statement.setString(1, projectId);
            statement.setString(2, businessVersion);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(manifest(rows)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("查询最近代际失败", exception);
        }
    }

    /** 列出可回滚的 RETIRED 代际（按 published_at 降序）。 */
    public List<ClaimVectorGenerationManifest> listRetiredForRollback(String projectId, String businessVersion) {
        List<ClaimVectorGenerationManifest> result = new ArrayList<>();
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     select * from knowledge_claim_vector_generation
                     where project_id=? and business_version=? and status='RETIRED'
                     order by published_at desc
                     """)) {
            statement.setString(1, projectId);
            statement.setString(2, businessVersion);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(manifest(rows));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("列出 RETIRED 代际失败", exception);
        }
        return result;
    }

    /**
     * 中（第七批 Review 4）：按 scope 保留最近 keepRetired 个 RETIRED 代际，删除更早的（连带 input 行）。
     * 与 Qdrant 侧 retainPhysicalCollections 清理窗口对齐——被删集合对应的代际不再可回滚，
     * SQLite 行同步清理，避免 generation_input 无界增长与 rollbackTo 打到已删集合。
     */
    public void pruneRetiredGenerations(String projectId, String businessVersion, int keepRetired) {
        List<String> doomed = new ArrayList<>();
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     select generation_id from knowledge_claim_vector_generation
                     where project_id=? and business_version=? and status='RETIRED'
                     order by published_at desc
                     limit -1 offset ?
                     """)) {
            statement.setString(1, projectId);
            statement.setString(2, businessVersion);
            statement.setInt(3, Math.max(0, keepRetired));
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    doomed.add(rows.getString(1));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("列出待清理 RETIRED 代际失败", exception);
        }
        if (doomed.isEmpty()) {
            return;
        }
        try (Connection connection = open()) {
            try (Statement begin = connection.createStatement()) {
                begin.execute("begin immediate");
            }
            try (PreparedStatement inputs = connection.prepareStatement(
                    "delete from knowledge_claim_vector_generation_input where generation_id=?")) {
                for (String generationId : doomed) {
                    inputs.setString(1, generationId);
                    inputs.addBatch();
                }
                inputs.executeBatch();
            }
            try (PreparedStatement generations = connection.prepareStatement(
                    "delete from knowledge_claim_vector_generation where generation_id=?")) {
                for (String generationId : doomed) {
                    generations.setString(1, generationId);
                    generations.addBatch();
                }
                generations.executeBatch();
            }
            try (Statement commit = connection.createStatement()) {
                commit.execute("commit");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("清理超期 RETIRED 代际失败", exception);
        }
    }

    /** 查找代际的输入集合（用于 active 代际限定可见 Claim）。 */
    public List<ClaimVectorGenerationInput> findGenerationInputs(String generationId) {
        List<ClaimVectorGenerationInput> result = new ArrayList<>();
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     select generation_id, claim_id, document_version_id, text_hash, updated_at
                     from knowledge_claim_vector_generation_input
                     where generation_id=?
                     """)) {
            statement.setString(1, generationId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new ClaimVectorGenerationInput(
                            rows.getString("generation_id"),
                            rows.getString("claim_id"),
                            rows.getString("document_version_id"),
                            rows.getString("text_hash"),
                            rows.getString("updated_at")));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("查询代际输入集合失败 generationId=" + generationId, exception);
        }
        return result;
    }

    /**
     * 检查同一 scope + fingerprint 的已成功代际是否存在（用于跳过重复构建）。
     * 仅当上一代际为 SUCCESS/ACTIVE 且物理集合存在时才跳过。
     */
    public Optional<ClaimVectorGenerationManifest> findReusableGeneration(
            String projectId, String businessVersion, String inputFingerprint,
            String projectionSchemaVersion, String embeddingModel) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     select * from knowledge_claim_vector_generation
                     where project_id=? and business_version=? and input_fingerprint=?
                       and projection_schema_version=? and embedding_model=?
                       and status in ('SUCCESS', 'ACTIVE')
                       and physical_collection is not null
                     order by started_at desc limit 1
                     """)) {
            statement.setString(1, projectId);
            statement.setString(2, businessVersion);
            statement.setString(3, inputFingerprint);
            statement.setString(4, projectionSchemaVersion);
            statement.setString(5, embeddingModel);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(manifest(rows)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("查询可复用代际失败", exception);
        }
    }

    // ===== 内部方法 =====

    private void saveInputs(Connection connection, String generationId,
                            List<ClaimVectorGenerationInput> inputs) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert or replace into knowledge_claim_vector_generation_input(
                  generation_id, claim_id, document_version_id, text_hash, updated_at
                ) values (?,?,?,?,?)
                """)) {
            for (ClaimVectorGenerationInput input : inputs) {
                statement.setString(1, generationId);
                statement.setString(2, input.claimId());
                statement.setString(3, input.documentVersionId());
                statement.setString(4, input.textHash());
                statement.setString(5, input.updatedAt());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private Optional<ClaimVectorGenerationManifest> findPreviousActive(
            Connection connection, String projectId, String businessVersion,
            String excludeGenerationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select * from knowledge_claim_vector_generation
                where project_id=? and business_version=? and status='RETIRED'
                  and generation_id<>?
                order by published_at desc limit 1
                """)) {
            statement.setString(1, projectId);
            statement.setString(2, businessVersion);
            statement.setString(3, excludeGenerationId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(manifest(rows)) : Optional.empty();
            }
        }
    }

    private ClaimVectorGenerationManifest findGeneration(
            Connection connection, String generationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select * from knowledge_claim_vector_generation where generation_id=?
                """)) {
            statement.setString(1, generationId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? manifest(rows) : null;
            }
        }
    }

    private ClaimVectorGenerationManifest manifest(ResultSet rows) throws SQLException {
        return new ClaimVectorGenerationManifest(
                rows.getString("generation_id"),
                rows.getString("project_id"),
                rows.getString("business_version"),
                rows.getString("input_fingerprint"),
                rows.getString("projection_schema_version"),
                rows.getString("text_composer_version"),
                rows.getString("embedding_model"),
                rows.getInt("embedding_dimension"),
                rows.getString("physical_collection"),
                GenerationStatus.valueOf(rows.getString("status")),
                rows.getInt("expected_point_count"),
                rows.getInt("indexed_point_count"),
                rows.getString("warnings_json"),
                rows.getString("started_at"),
                rows.getString("finished_at"),
                rows.getString("published_at"),
                rows.getString("build_scope"));
    }
}
