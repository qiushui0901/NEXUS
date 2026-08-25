package com.example.requirementrag.requirement.semantic;

import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.ClaimStatus;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.ExtractionStatus;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticAnnotationRecord;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticAnnotationResult;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticBuildRecord;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticBuildStatus;
import com.example.requirementrag.requirement.semantic.RequirementSemanticModels.SemanticErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
 * SQLite 持久化语义标注：按（项目、文档、需求版本、Chunk、内容哈希、模型、Prompt、Schema）
 * 幂等写入；Prompt/Schema 版本变化生成新记录而非覆盖旧结果，失败记录可独立重试。
 */
@Repository
@ConditionalOnProperty(prefix = "app.rag.requirement-semantic", name = "enabled",
        havingValue = "true", matchIfMissing = false)
public class SQLiteRequirementSemanticStore {
    private final ObjectMapper objectMapper;
    private final String jdbcUrl;

    public SQLiteRequirementSemanticStore(ObjectMapper objectMapper, RequirementSemanticProperties properties) {
        this.objectMapper = objectMapper;
        try {
            Path database = Path.of(properties.databasePath()).toAbsolutePath().normalize();
            if (database.getParent() != null) Files.createDirectories(database.getParent());
            this.jdbcUrl = "jdbc:sqlite:" + database;
            initialize();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to initialize requirement semantic directory", exception);
        }
    }

    /** 幂等键：内容 + 模型 + Prompt + Schema 版本共同决定一条标注身份。 */
    public static String annotationId(String projectId, String documentId, String requirementVersion,
                                      String sourceChunkId, String contentHash, String model,
                                      String promptVersion, String schemaVersion) {
        return "semantic:" + sha256(String.join("|", safe(projectId), safe(documentId), safe(requirementVersion),
                safe(sourceChunkId), safe(contentHash), safe(model), safe(promptVersion),
                safe(schemaVersion))).substring(0, 32);
    }

    /** 构建代际 ID：同一输入集（含 sourceRevision）确定性生成，重复构建幂等覆盖。 */
    public static String buildId(String projectId, String documentId, String requirementVersion,
                                 String sourceRevision, String model, String promptVersion,
                                 String schemaVersion) {
        return "build:" + sha256(String.join("|", safe(projectId), safe(documentId), safe(requirementVersion),
                safe(sourceRevision), safe(model), safe(promptVersion), safe(schemaVersion))).substring(0, 32);
    }

    @PostConstruct
    public final void initialize() {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("PRAGMA foreign_keys=ON");
            statement.executeUpdate("""
                    create table if not exists requirement_semantic_annotation(
                      annotation_id text primary key,
                      project_id text not null,
                      document_id text not null,
                      requirement_version text not null,
                      source_revision text not null,
                      source_chunk_id text not null,
                      parent_id text,
                      window_id text,
                      window_index integer not null default 0,
                      start_offset integer not null default 0,
                      end_offset integer not null default 0,
                      source_file text not null,
                      parent_order integer not null default 0,
                      content_hash text not null,
                      raw_text text not null,
                      semantic_summary text,
                      semantic_text text,
                      result_json text not null default '{}',
                      model text not null,
                      prompt_version text not null,
                      schema_version text not null,
                      extraction_status text not null,
                      claim_status text not null default 'CANDIDATE',
                      confidence real,
                      attempt_count integer not null default 0,
                      model_calls integer not null default 0,
                      latency_ms integer not null default 0,
                      token_estimate integer not null default 0,
                      error_code text,
                      created_at text not null,
                      updated_at text not null,
                      unique(project_id, document_id, requirement_version, source_chunk_id,
                             content_hash, model, prompt_version, schema_version)
                    )
                    """);
            // 旧库迁移：Phase 1 首版没有窗口坐标列，逐列补齐。
            addColumnIfMissing(statement, "requirement_semantic_annotation", "window_index",
                    "integer not null default 0");
            addColumnIfMissing(statement, "requirement_semantic_annotation", "start_offset",
                    "integer not null default 0");
            addColumnIfMissing(statement, "requirement_semantic_annotation", "end_offset",
                    "integer not null default 0");
            statement.executeUpdate("create index if not exists idx_req_semantic_scope"
                    + " on requirement_semantic_annotation(project_id, document_id, requirement_version, extraction_status)");
            statement.executeUpdate("""
                    create table if not exists requirement_semantic_entity(
                      annotation_id text not null,
                      entity_index integer not null,
                      entity_name text not null,
                      entity_type text,
                      aliases_json text not null,
                      certainty text not null,
                      evidence_quote text,
                      primary key(annotation_id, entity_index),
                      foreign key(annotation_id) references requirement_semantic_annotation(annotation_id) on delete cascade
                    )
                    """);
            statement.executeUpdate("""
                    create table if not exists requirement_semantic_condition(
                      annotation_id text not null,
                      condition_index integer not null,
                      subject text,
                      field_name text,
                      operator text,
                      value text,
                      unit text,
                      value_type text,
                      logical_group text,
                      certainty text not null,
                      evidence_quote text,
                      primary key(annotation_id, condition_index),
                      foreign key(annotation_id) references requirement_semantic_annotation(annotation_id) on delete cascade
                    )
                    """);
            statement.executeUpdate("""
                    create table if not exists requirement_semantic_event(
                      annotation_id text not null,
                      event_index integer not null,
                      subject text,
                      event_name text not null,
                      object_name text,
                      result text,
                      condition_text text,
                      certainty text not null,
                      evidence_quote text,
                      primary key(annotation_id, event_index),
                      foreign key(annotation_id) references requirement_semantic_annotation(annotation_id) on delete cascade
                    )
                    """);
            statement.executeUpdate("""
                    create table if not exists requirement_semantic_numeric_fact(
                      annotation_id text not null,
                      numeric_index integer not null,
                      subject text,
                      field_name text,
                      value text not null,
                      normalized_value real,
                      unit text,
                      normalized_unit text,
                      operator text,
                      certainty text not null,
                      evidence_quote text,
                      primary key(annotation_id, numeric_index),
                      foreign key(annotation_id) references requirement_semantic_annotation(annotation_id) on delete cascade
                    )
                    """);
            statement.executeUpdate("""
                    create table if not exists requirement_semantic_question(
                      annotation_id text not null,
                      question_index integer not null,
                      question_text text not null,
                      question_type text,
                      primary key(annotation_id, question_index),
                      foreign key(annotation_id) references requirement_semantic_annotation(annotation_id) on delete cascade
                    )
                    """);
            statement.executeUpdate("""
                    create table if not exists requirement_semantic_build(
                      build_id text primary key,
                      project_id text not null,
                      document_id text not null,
                      requirement_version text not null,
                      source_revision text not null,
                      model text not null,
                      prompt_version text not null,
                      schema_version text not null,
                      build_status text not null,
                      total_chunks integer not null default 0,
                      skipped_chunks integer not null default 0,
                      completed_chunks integer not null default 0,
                      failed_chunks integer not null default 0,
                      warnings_json text not null default '[]',
                      started_at text,
                      finished_at text,
                      active integer not null default 0
                    )
                    """);
            statement.executeUpdate("create index if not exists idx_req_semantic_build_scope"
                    + " on requirement_semantic_build(project_id, document_id, requirement_version, active)");
            statement.executeUpdate("""
                    create table if not exists requirement_semantic_build_input(
                      build_id text not null,
                      source_chunk_id text not null,
                      window_id text,
                      content_hash text not null,
                      primary key(build_id, source_chunk_id, window_id, content_hash),
                      foreign key(build_id) references requirement_semantic_build(build_id) on delete cascade
                    )
                    """);
            statement.executeUpdate("create index if not exists idx_req_semantic_build_input_scope"
                    + " on requirement_semantic_build_input(build_id)");
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to initialize requirement semantic store", exception);
        }
    }

    /** 旧库补列：重复执行时忽略 duplicate column 错误，与项目其他 SQLite store 迁移方式一致。 */
    private void addColumnIfMissing(Statement statement, String table, String column,
                                    String definition) throws SQLException {
        try {
            statement.executeUpdate("alter table " + table + " add column " + column + " " + definition);
        } catch (SQLException exception) {
            if (!exception.getMessage().toLowerCase().contains("duplicate column")) throw exception;
        }
    }

    /** 幂等保存：同一幂等键重复写入替换旧记录，子表随同重建，全程单事务。 */
    public void save(SemanticAnnotationRecord record) {
        if (record == null) throw new IllegalArgumentException("语义标注记录不能为空");
        Instant now = Instant.now();
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                deleteChildren(connection, record.annotationId());
                try (PreparedStatement statement = connection.prepareStatement("""
                        insert or replace into requirement_semantic_annotation(
                          annotation_id, project_id, document_id, requirement_version, source_revision,
                          source_chunk_id, parent_id, window_id, window_index, start_offset, end_offset,
                          source_file, parent_order, content_hash, raw_text, semantic_summary, semantic_text,
                          result_json, model, prompt_version, schema_version, extraction_status, claim_status,
                          confidence, attempt_count, model_calls, latency_ms, token_estimate,
                          error_code, created_at, updated_at)
                        values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                        """)) {
                    bindAnnotation(statement, record, now);
                    statement.executeUpdate();
                }
                insertChildren(connection, record);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to save requirement semantic annotation", exception);
        }
    }

    /** 查询同一幂等键下任意状态的既有记录（重试时用于累加 attempt）。 */
    public Optional<SemanticAnnotationRecord> findExisting(String projectId, String documentId,
                                                           String requirementVersion, String sourceChunkId,
                                                           String contentHash, String model,
                                                           String promptVersion, String schemaVersion) {
        String sql = """
                select * from requirement_semantic_annotation
                where project_id=? and document_id=? and requirement_version=?
                  and source_chunk_id=? and content_hash=? and model=?
                  and prompt_version=? and schema_version=?
                """;
        List<String> values = List.of(safe(projectId), safe(documentId), safe(requirementVersion),
                safe(sourceChunkId), safe(contentHash), safe(model), safe(promptVersion), safe(schemaVersion));
        return queryOne(sql, values);
    }

    public boolean existsSuccessful(String projectId, String documentId, String requirementVersion,
                                    String sourceChunkId, String contentHash, String model,
                                    String promptVersion, String schemaVersion) {
        return findExisting(projectId, documentId, requirementVersion, sourceChunkId, contentHash,
                model, promptVersion, schemaVersion)
                .map(record -> record.extractionStatus() == ExtractionStatus.SUCCEEDED)
                .orElse(false);
    }

    /** 按项目 / 文档 / 版本列出标注，可按抽取状态过滤。 */
    public List<SemanticAnnotationRecord> list(String projectId, String documentId, String requirementVersion,
                                               ExtractionStatus status, int limit, int offset) {
        StringBuilder sql = new StringBuilder(
                "select * from requirement_semantic_annotation where project_id=? and document_id=?"
                        + " and requirement_version=?");
        List<String> values = new ArrayList<>(List.of(safe(projectId), safe(documentId), safe(requirementVersion)));
        if (status != null) {
            sql.append(" and extraction_status=?");
            values.add(status.name());
        }
        sql.append(" order by source_file, parent_order, window_index, start_offset, source_chunk_id limit ? offset ?");
        return queryList(sql.toString(), values, Math.max(1, limit), Math.max(0, offset));
    }

    /** 当前 active 构建的 sourceRevision：查询必须绑定它，避免混入旧 revision / 旧 Prompt 的结果。 */
    public Optional<String> activeSourceRevision(String projectId, String documentId,
                                                  String requirementVersion) {
        String sql = """
                select source_revision from requirement_semantic_build
                where project_id=? and document_id=? and requirement_version=? and active=1
                order by finished_at desc limit 1
                """;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, safe(projectId));
            statement.setString(2, safe(documentId));
            statement.setString(3, safe(requirementVersion));
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.ofNullable(rows.getString(1)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to query active requirement semantic revision", exception);
        }
    }

    /** 列出 active 构建下可直接消费的成功标注（通过当前构建输入集合过滤，未完成构建的旧记录不可见）。 */
    public List<SemanticAnnotationRecord> listActive(String projectId, String documentId,
                                                      String requirementVersion, int limit, int offset) {
        String sql = """
                select a.* from requirement_semantic_annotation a
                join requirement_semantic_build b
                  on a.project_id=b.project_id and a.document_id=b.document_id
                 and a.requirement_version=b.requirement_version
                 and a.model=b.model and a.prompt_version=b.prompt_version and a.schema_version=b.schema_version
                join requirement_semantic_build_input i
                  on i.build_id=b.build_id
                 and i.source_chunk_id=a.source_chunk_id
                 and i.content_hash=a.content_hash
                 and coalesce(i.window_id,'')=coalesce(a.window_id,'')
                where a.project_id=? and a.document_id=? and a.requirement_version=?
                  and b.active=1 and b.build_status='SUCCESS' and a.extraction_status='SUCCEEDED'
                order by a.source_file, a.parent_order, a.window_index, a.start_offset, a.source_chunk_id
                limit ? offset ?
                """;
        return queryList(sql, List.of(safe(projectId), safe(documentId), safe(requirementVersion)),
                Math.max(1, limit), Math.max(0, offset));
    }

    /**
     * 列出项目+版本下所有 active 构建的成功标注（跨文档），供多源检索适配器消费。
     * 与构建代际表按（项目、文档、版本、source_revision、模型、Prompt、Schema）对齐连接，
     * 并且必须关联当前构建的输入集合（source_chunk_id + content_hash + window_id），
     * 防止已删除/过期窗口的旧记录在 revision 未变化时被重新暴露。
     */
    /** 兼容旧签名：不按 query 过滤。 */
    public List<SemanticAnnotationRecord> listActiveByProjectVersion(String projectId,
                                                                     String requirementVersion, int limit) {
        return listActiveByProjectVersion(projectId, requirementVersion, limit, "");
    }

    /**
     * 列出项目+版本下所有 active 构建的成功标注（跨文档），供多源检索适配器消费。
     * 与构建代际表按（项目、文档、版本、source_revision、模型、Prompt、Schema）对齐连接，
     * 并且必须关联当前构建的输入集合（source_chunk_id + content_hash + window_id），
     * 防止已删除/过期窗口的旧记录在 revision 未变化时被重新暴露。
     * query 非空时按语义字段（summary/text/result_json）做词项 LIKE 过滤，避免固定截断漏掉后段相关候选。
     */
    public List<SemanticAnnotationRecord> listActiveByProjectVersion(String projectId,
                                                                     String requirementVersion, int limit,
                                                                     String query) {
        List<String> terms = likeTerms(query);
        StringBuilder sql = new StringBuilder("""
                select a.* from requirement_semantic_annotation a
                join requirement_semantic_build b
                  on a.project_id=b.project_id and a.document_id=b.document_id
                 and a.requirement_version=b.requirement_version
                 and a.model=b.model and a.prompt_version=b.prompt_version and a.schema_version=b.schema_version
                join requirement_semantic_build_input i
                  on i.build_id=b.build_id
                 and i.source_chunk_id=a.source_chunk_id
                 and i.content_hash=a.content_hash
                 and coalesce(i.window_id,'')=coalesce(a.window_id,'')
                where a.project_id=? and a.requirement_version=? and b.active=1
                  and b.build_status='SUCCESS'
                  and a.extraction_status='SUCCEEDED'
                """);
        List<String> params = new ArrayList<>(List.of(safe(projectId), safe(requirementVersion)));
        if (!terms.isEmpty()) {
            sql.append(" and (");
            for (int index = 0; index < terms.size(); index++) {
                if (index > 0) sql.append(" and ");
                sql.append("(a.semantic_summary like ? or a.semantic_text like ? or a.result_json like ?)");
                String like = "%" + terms.get(index) + "%";
                params.add(like);
                params.add(like);
                params.add(like);
            }
            sql.append(')');
        }
        sql.append(" order by a.source_file, a.parent_order, a.window_index, a.start_offset, a.source_chunk_id limit ?");
        params.add(Integer.toString(Math.max(1, limit)));
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int index = 0; index < params.size(); index++) {
                statement.setString(index + 1, params.get(index));
            }
            try (ResultSet rows = statement.executeQuery()) {
                List<SemanticAnnotationRecord> records = new ArrayList<>();
                while (rows.next()) records.add(record(rows));
                return List.copyOf(records);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to list active requirement semantic annotations", exception);
        }
    }

    /** 查询分词：按空白/标点切分，保留长度 >= 2 的词项，用于语义字段 LIKE 过滤。 */
    private List<String> likeTerms(String query) {
        if (query == null || query.isBlank()) return List.of();
        java.util.LinkedHashSet<String> terms = new java.util.LinkedHashSet<>();
        for (String term : query.toLowerCase(java.util.Locale.ROOT)
                .split("[\\s,，。；;？?！!：:、/()（）]+")) {
            if (term.length() >= 2) terms.add(term);
        }
        return List.copyOf(terms);
    }

    /** 最近一次构建记录（任意状态），供构建状态查询。 */
    public Optional<SemanticBuildRecord> latestBuild(String projectId, String documentId,
                                                     String requirementVersion) {
        String sql = """
                select * from requirement_semantic_build
                where project_id=? and document_id=? and requirement_version=?
                order by finished_at desc, started_at desc limit 1
                """;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, safe(projectId));
            statement.setString(2, safe(documentId));
            statement.setString(3, safe(requirementVersion));
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(buildRecord(rows)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to query latest requirement semantic build", exception);
        }
    }

    /** 保存构建记录；active 构建先取消同范围内其他 active 标记，同一事务内完成切换。 */
    public void saveBuild(SemanticBuildRecord record) {
        if (record == null) throw new IllegalArgumentException("语义构建记录不能为空");
        Instant now = Instant.now();
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                if (record.active()) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            update requirement_semantic_build set active=0
                            where project_id=? and document_id=? and requirement_version=? and build_id != ?
                            """)) {
                        statement.setString(1, safe(record.projectId()));
                        statement.setString(2, safe(record.documentId()));
                        statement.setString(3, safe(record.requirementVersion()));
                        statement.setString(4, safe(record.buildId()));
                        statement.executeUpdate();
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        insert or replace into requirement_semantic_build(
                          build_id, project_id, document_id, requirement_version, source_revision,
                          model, prompt_version, schema_version, build_status, total_chunks,
                          skipped_chunks, completed_chunks, failed_chunks, warnings_json,
                          started_at, finished_at, active)
                        values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                        """)) {
                    statement.setString(1, record.buildId());
                    statement.setString(2, record.projectId());
                    statement.setString(3, record.documentId());
                    statement.setString(4, record.requirementVersion());
                    statement.setString(5, record.sourceRevision());
                    statement.setString(6, record.model());
                    statement.setString(7, record.promptVersion());
                    statement.setString(8, record.schemaVersion());
                    statement.setString(9, record.buildStatus().name());
                    statement.setInt(10, record.totalChunks());
                    statement.setInt(11, record.skippedChunks());
                    statement.setInt(12, record.completedChunks());
                    statement.setInt(13, record.failedChunks());
                    statement.setString(14, json(record.warnings()));
                    statement.setString(15, record.startedAt() == null ? null : record.startedAt().toString());
                    statement.setString(16, record.finishedAt() == null ? null : record.finishedAt().toString());
                    statement.setInt(17, record.active() ? 1 : 0);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to save requirement semantic build", exception);
        }
    }

    /** 保存当前构建的输入集合（多窗口/多 Chunk），供 active 查询严格限制可见标注。 */
    public void saveBuildInputs(String buildId, List<RequirementSemanticModels.SemanticBuildInput> inputs) {
        if (buildId == null || buildId.isBlank() || inputs == null || inputs.isEmpty()) return;
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement("""
                        insert or replace into requirement_semantic_build_input(
                          build_id, source_chunk_id, window_id, content_hash)
                        values(?,?,?,?)
                        """)) {
                    for (RequirementSemanticModels.SemanticBuildInput input : inputs) {
                        statement.setString(1, buildId);
                        statement.setString(2, safe(input.sourceChunkId()));
                        statement.setString(3, safe(input.windowId()));
                        statement.setString(4, safe(input.contentHash()));
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to save requirement semantic build inputs", exception);
        }
    }

    public Optional<SemanticBuildRecord> findBuild(String buildId) {
        String sql = "select * from requirement_semantic_build where build_id=?";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, safe(buildId));
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(buildRecord(rows)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to query requirement semantic build", exception);
        }
    }

    private SemanticBuildRecord buildRecord(ResultSet row) throws SQLException {
        String warningsJson = row.getString("warnings_json");
        List<String> warnings;
        try {
            warnings = warningsJson == null || warningsJson.isBlank()
                    ? List.of()
                    : objectMapper.readValue(warningsJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("需求语义构建 warnings JSON 损坏", exception);
        }
        return new SemanticBuildRecord(
                row.getString("build_id"),
                row.getString("project_id"),
                row.getString("document_id"),
                row.getString("requirement_version"),
                row.getString("source_revision"),
                row.getString("model"),
                row.getString("prompt_version"),
                row.getString("schema_version"),
                SemanticBuildStatus.valueOf(row.getString("build_status")),
                row.getInt("total_chunks"),
                row.getInt("skipped_chunks"),
                row.getInt("completed_chunks"),
                row.getInt("failed_chunks"),
                warnings,
                parseInstant(row.getString("started_at")),
                parseInstant(row.getString("finished_at")),
                row.getInt("active") == 1);
    }

    public int countByStatus(String projectId, String documentId, String requirementVersion,
                             ExtractionStatus status) {
        String sql = status == null
                ? "select count(*) from requirement_semantic_annotation where project_id=? and document_id=? and requirement_version=?"
                : "select count(*) from requirement_semantic_annotation where project_id=? and document_id=? and requirement_version=? and extraction_status=?";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, safe(projectId));
            statement.setString(2, safe(documentId));
            statement.setString(3, safe(requirementVersion));
            if (status != null) statement.setString(4, status.name());
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to count requirement semantic annotations", exception);
        }
    }

    /** 子表行数（供持久化完整性测试使用）。 */
    public int countChildren(String annotationId, String table) {
        List<String> allowed = List.of("requirement_semantic_entity", "requirement_semantic_condition",
                "requirement_semantic_event", "requirement_semantic_numeric_fact", "requirement_semantic_question");
        if (!allowed.contains(table)) throw new IllegalArgumentException("未知语义子表: " + table);
        String sql = "select count(*) from " + table + " where annotation_id=?";
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, safe(annotationId));
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to count requirement semantic children", exception);
        }
    }

    private void bindAnnotation(PreparedStatement statement, SemanticAnnotationRecord record,
                                Instant now) throws SQLException {
        statement.setString(1, record.annotationId());
        statement.setString(2, record.projectId());
        statement.setString(3, record.documentId());
        statement.setString(4, record.requirementVersion());
        statement.setString(5, record.sourceRevision());
        statement.setString(6, record.sourceChunkId());
        statement.setString(7, record.parentId());
        statement.setString(8, record.windowId());
        statement.setInt(9, record.windowIndex());
        statement.setInt(10, record.startOffset());
        statement.setInt(11, record.endOffset());
        statement.setString(12, record.sourceFile());
        statement.setInt(13, record.parentOrder());
        statement.setString(14, record.contentHash());
        statement.setString(15, record.rawText());
        statement.setString(16, record.semanticSummary());
        statement.setString(17, record.semanticText());
        statement.setString(18, record.result() == null ? null : json(record.result()));
        statement.setString(19, record.model());
        statement.setString(20, record.promptVersion());
        statement.setString(21, record.schemaVersion());
        statement.setString(22, record.extractionStatus().name());
        statement.setString(23, record.claimStatus().name());
        if (record.confidence() == null) statement.setNull(24, java.sql.Types.REAL);
        else statement.setDouble(24, record.confidence());
        statement.setInt(25, record.attemptCount());
        statement.setInt(26, record.modelCalls());
        statement.setLong(27, record.latencyMs());
        statement.setInt(28, record.tokenEstimate());
        statement.setString(29, record.errorCode() == null ? null : record.errorCode().name());
        statement.setString(30, record.createdAt() == null ? now.toString() : record.createdAt().toString());
        statement.setString(31, now.toString());
    }

    private void insertChildren(Connection connection, SemanticAnnotationRecord record) throws SQLException {
        SemanticAnnotationResult result = record.result();
        if (result == null) return;
        try (PreparedStatement entity = connection.prepareStatement("""
                insert or replace into requirement_semantic_entity(
                  annotation_id, entity_index, entity_name, entity_type, aliases_json, certainty, evidence_quote)
                values(?,?,?,?,?,?,?)""")) {
            int index = 0;
            for (var item : result.entities()) {
                entity.setString(1, record.annotationId());
                entity.setInt(2, index++);
                entity.setString(3, item.name());
                entity.setString(4, item.type());
                entity.setString(5, json(item.aliases()));
                entity.setString(6, item.certainty());
                entity.setString(7, item.evidenceQuote());
                entity.addBatch();
            }
            entity.executeBatch();
        }
        try (PreparedStatement condition = connection.prepareStatement("""
                insert or replace into requirement_semantic_condition(
                  annotation_id, condition_index, subject, field_name, operator, value, unit,
                  value_type, logical_group, certainty, evidence_quote)
                values(?,?,?,?,?,?,?,?,?,?,?)""")) {
            int index = 0;
            for (var item : result.conditions()) {
                condition.setString(1, record.annotationId());
                condition.setInt(2, index++);
                condition.setString(3, item.subject());
                condition.setString(4, item.field());
                condition.setString(5, item.operator());
                condition.setString(6, item.value());
                condition.setString(7, item.unit());
                condition.setString(8, item.valueType());
                condition.setString(9, item.logicalGroup());
                condition.setString(10, item.certainty());
                condition.setString(11, item.evidenceQuote());
                condition.addBatch();
            }
            condition.executeBatch();
        }
        try (PreparedStatement event = connection.prepareStatement("""
                insert or replace into requirement_semantic_event(
                  annotation_id, event_index, subject, event_name, object_name, result,
                  condition_text, certainty, evidence_quote)
                values(?,?,?,?,?,?,?,?,?)""")) {
            int index = 0;
            for (var item : result.events()) {
                event.setString(1, record.annotationId());
                event.setInt(2, index++);
                event.setString(3, item.subject());
                event.setString(4, item.event());
                event.setString(5, item.object());
                event.setString(6, item.result());
                event.setString(7, item.condition());
                event.setString(8, item.certainty());
                event.setString(9, item.evidenceQuote());
                event.addBatch();
            }
            event.executeBatch();
        }
        try (PreparedStatement numeric = connection.prepareStatement("""
                insert or replace into requirement_semantic_numeric_fact(
                  annotation_id, numeric_index, subject, field_name, value, normalized_value,
                  unit, normalized_unit, operator, certainty, evidence_quote)
                values(?,?,?,?,?,?,?,?,?,?,?)""")) {
            int index = 0;
            for (var item : result.numericFacts()) {
                numeric.setString(1, record.annotationId());
                numeric.setInt(2, index++);
                numeric.setString(3, item.subject());
                numeric.setString(4, item.field());
                numeric.setString(5, item.value());
                if (item.normalizedValue() == null) numeric.setNull(6, java.sql.Types.REAL);
                else numeric.setDouble(6, item.normalizedValue());
                numeric.setString(7, item.unit());
                numeric.setString(8, item.normalizedUnit());
                numeric.setString(9, item.operator());
                numeric.setString(10, item.certainty());
                numeric.setString(11, item.evidenceQuote());
                numeric.addBatch();
            }
            numeric.executeBatch();
        }
        try (PreparedStatement question = connection.prepareStatement("""
                insert or replace into requirement_semantic_question(
                  annotation_id, question_index, question_text, question_type)
                values(?,?,?,?)""")) {
            int index = 0;
            for (var item : result.questionExpansions()) {
                question.setString(1, record.annotationId());
                question.setInt(2, index++);
                question.setString(3, item.text());
                question.setString(4, item.type());
                question.addBatch();
            }
            question.executeBatch();
        }
    }

    private void deleteChildren(Connection connection, String annotationId) throws SQLException {
        for (String table : List.of("requirement_semantic_entity", "requirement_semantic_condition",
                "requirement_semantic_event", "requirement_semantic_numeric_fact",
                "requirement_semantic_question")) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "delete from " + table + " where annotation_id=?")) {
                statement.setString(1, annotationId);
                statement.executeUpdate();
            }
        }
    }

    private Optional<SemanticAnnotationRecord> queryOne(String sql, List<String> values) {
        List<SemanticAnnotationRecord> records = queryList(sql, values, 1, 0);
        return records.isEmpty() ? Optional.empty() : Optional.of(records.get(0));
    }

    private List<SemanticAnnotationRecord> queryList(String sql, List<String> values, int limit, int offset) {
        List<SemanticAnnotationRecord> result = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (String value : values) statement.setString(index++, value);
            // text block 里 limit 位于行首（前面是换行而非空格），不能依赖前导空格匹配。
            if (sql.contains("limit ?")) {
                statement.setInt(index++, limit);
                statement.setInt(index++, offset);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(record(rows));
            }
            return List.copyOf(result);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to query requirement semantic annotations", exception);
        }
    }

    private SemanticAnnotationRecord record(ResultSet row) throws SQLException {
        String resultJson = row.getString("result_json");
        return new SemanticAnnotationRecord(
                row.getString("annotation_id"),
                row.getString("project_id"),
                row.getString("document_id"),
                row.getString("requirement_version"),
                row.getString("source_revision"),
                row.getString("source_chunk_id"),
                row.getString("parent_id"),
                row.getString("window_id"),
                row.getInt("window_index"),
                row.getInt("start_offset"),
                row.getInt("end_offset"),
                row.getString("source_file"),
                row.getInt("parent_order"),
                row.getString("content_hash"),
                row.getString("raw_text"),
                row.getString("semantic_summary"),
                row.getString("semantic_text"),
                parseResult(resultJson),
                row.getString("model"),
                row.getString("prompt_version"),
                row.getString("schema_version"),
                ExtractionStatus.valueOf(row.getString("extraction_status")),
                ClaimStatus.valueOf(row.getString("claim_status")),
                row.getObject("confidence") == null ? null : row.getDouble("confidence"),
                row.getInt("attempt_count"),
                row.getInt("model_calls"),
                row.getLong("latency_ms"),
                row.getInt("token_estimate"),
                row.getString("error_code") == null ? null : SemanticErrorCode.valueOf(row.getString("error_code")),
                parseInstant(row.getString("created_at")),
                parseInstant(row.getString("updated_at")));
    }

    private SemanticAnnotationResult parseResult(String json) {
        if (json == null || json.isBlank() || "{}".equals(json)) return null;
        try {
            return objectMapper.readValue(json, SemanticAnnotationResult.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("需求语义标注 JSON 字段损坏", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化需求语义 JSON 字段", exception);
        }
    }

    private Instant parseInstant(String value) {
        return value == null ? null : Instant.parse(value);
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            // SQLite 外键与 busy_timeout 都是连接级设置：必须每个连接显式开启；
            // WAL 允许构建任务与查询并发，避免 database is locked。
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA busy_timeout=5000");
        }
        return connection;
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
