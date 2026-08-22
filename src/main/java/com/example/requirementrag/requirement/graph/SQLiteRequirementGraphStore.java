package com.example.requirementrag.requirement.graph;

import com.example.requirementrag.requirement.graph.RequirementGraphModels.AuditEntry;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.BuildJob;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.BuildJobState;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.ClaimPage;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.ClaimStatus;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.Conflict;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.Entity;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.EntityStatus;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.GraphSnapshot;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.Relation;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.RelationStatus;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.RelationType;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.SnapshotStatus;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.Uncertainty;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.WindowStatus;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.RequirementGraphWindowView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.io.IOException;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** SQLite persistence for versioned, reviewable requirement graph projections. */
@Repository
@ConditionalOnProperty(prefix = "app.rag.requirement-graph", name = "enabled", havingValue = "true", matchIfMissing = false)
public class SQLiteRequirementGraphStore {
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final String jdbcUrl;

    public SQLiteRequirementGraphStore(ObjectMapper objectMapper, RequirementGraphProperties properties) {
        this.objectMapper = objectMapper;
        try {
            Path database = Path.of(properties.databasePath()).toAbsolutePath().normalize();
            if (database.getParent() != null) Files.createDirectories(database.getParent());
            this.jdbcUrl = "jdbc:sqlite:" + database;
            initialize();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to initialize requirement graph directory", exception);
        }
    }

    @PostConstruct
    public final void initialize() {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("PRAGMA foreign_keys=ON");
            statement.executeUpdate("""
                    create table if not exists requirement_graph_snapshot(
                      id text primary key,
                      business_project_id text not null,
                      document_id text not null,
                      requirement_version text not null,
                      source_revision text not null,
                      extraction_model text,
                      prompt_version text not null,
                      status text not null,
                      entity_count integer not null default 0,
                      relation_count integer not null default 0,
                      created_at text not null,
                      updated_at text not null,
                      published_at text,
                      schema_version integer not null default 1,
                      ontology_version text not null default 'v1',
                      coverage_ratio real not null default 1.0,
                      window_count integer not null default 0,
                      succeeded_window_count integer not null default 0,
                      failed_window_count integer not null default 0,
                      warning_count integer not null default 0,
                      build_id text,
                      published_by text,
                      publication_reason text,
                      stale_at text,
                      unique(business_project_id,document_id,requirement_version,source_revision,prompt_version)
                    )
                    """);
            addColumn(statement, "requirement_graph_snapshot", "schema_version", "integer not null default 1");
            addColumn(statement, "requirement_graph_snapshot", "ontology_version", "text not null default 'v1'");
            addColumn(statement, "requirement_graph_snapshot", "coverage_ratio", "real not null default 1.0");
            addColumn(statement, "requirement_graph_snapshot", "window_count", "integer not null default 0");
            addColumn(statement, "requirement_graph_snapshot", "succeeded_window_count", "integer not null default 0");
            addColumn(statement, "requirement_graph_snapshot", "failed_window_count", "integer not null default 0");
            addColumn(statement, "requirement_graph_snapshot", "warning_count", "integer not null default 0");
            addColumn(statement, "requirement_graph_snapshot", "build_id", "text");
            addColumn(statement, "requirement_graph_snapshot", "published_by", "text");
            addColumn(statement, "requirement_graph_snapshot", "publication_reason", "text");
            addColumn(statement, "requirement_graph_snapshot", "stale_at", "text");

            statement.executeUpdate("""
                    create table if not exists requirement_graph_window(
                      id text not null,
                      snapshot_id text not null,
                      filename text not null,
                      parent_id text,
                      section_path text,
                      heading text,
                      window_index integer not null,
                      start_offset integer not null,
                      end_offset integer not null,
                      content_hash text not null,
                      status text not null,
                      attempt_count integer not null default 0,
                      last_error_code text,
                      started_at text,
                      completed_at text,
                      continuation_of text,
                      primary key(snapshot_id, id),
                      foreign key(snapshot_id) references requirement_graph_snapshot(id) on delete cascade
                    )
                    """);
            statement.executeUpdate("""
                    create table if not exists requirement_graph_window_result(
                      window_id text not null,
                      snapshot_id text not null,
                      result_json text not null,
                      updated_at text not null,
                      primary key(snapshot_id, window_id),
                      foreign key(snapshot_id) references requirement_graph_snapshot(id) on delete cascade
                    )
                    """);
            statement.executeUpdate("""
                    create table if not exists requirement_graph_entity_embedding(
                      entity_id text primary key,
                      snapshot_id text not null,
                      vector_json text not null,
                      foreign key(snapshot_id) references requirement_graph_snapshot(id) on delete cascade
                    )
                    """);
            statement.executeUpdate("""
                    create table if not exists requirement_graph_relation_embedding(
                      relation_id text primary key,
                      snapshot_id text not null,
                      vector_json text not null,
                      foreign key(snapshot_id) references requirement_graph_snapshot(id) on delete cascade
                    )
                    """);
            statement.executeUpdate("""
                    create table if not exists requirement_graph_entity(
                      id text primary key,
                      snapshot_id text not null,
                      type text not null,
                      canonical_name text not null,
                      display_name text not null,
                      aliases text not null,
                      description text,
                      source_evidence_ids text not null,
                      source_parent_ids text not null,
                      source_content_hashes text not null,
                      confidence real not null,
                      status text not null,
                      claim_status text not null default 'EXTRACTED',
                      normalized_by text,
                      context_key text,
                      first_seen_window_id text,
                      last_seen_window_id text,
                      uncertainty_ids text not null default '[]',
                      conflict_set_ids text not null default '[]',
                      reviewer text,
                      reviewed_at text,
                      review_reason text,
                      foreign key(snapshot_id) references requirement_graph_snapshot(id) on delete cascade,
                      unique(snapshot_id,type,canonical_name)
                    )
                    """);
            addColumn(statement, "requirement_graph_entity", "claim_status", "text not null default 'EXTRACTED'");
            addColumn(statement, "requirement_graph_entity", "normalized_by", "text");
            addColumn(statement, "requirement_graph_entity", "context_key", "text");
            addColumn(statement, "requirement_graph_entity", "first_seen_window_id", "text");
            addColumn(statement, "requirement_graph_entity", "last_seen_window_id", "text");
            addColumn(statement, "requirement_graph_entity", "uncertainty_ids", "text not null default '[]'");
            addColumn(statement, "requirement_graph_entity", "conflict_set_ids", "text not null default '[]'");
            addColumn(statement, "requirement_graph_entity", "review_reason", "text");

            statement.executeUpdate("""
                    create table if not exists requirement_graph_relation(
                      id text primary key,
                      snapshot_id text not null,
                      source_entity_id text not null,
                      relation_type text not null,
                      target_entity_id text not null,
                      statement text not null,
                      source_evidence_ids text not null,
                      confidence real not null,
                      status text not null,
                      reviewer text,
                      reviewed_at text,
                      claim_status text not null default 'EXTRACTED',
                      condition text,
                      scenario text,
                      statement_variants text not null default '[]',
                      uncertainty_ids text not null default '[]',
                      conflict_set_ids text not null default '[]',
                      review_reason text,
                      foreign key(snapshot_id) references requirement_graph_snapshot(id) on delete cascade,
                      foreign key(source_entity_id) references requirement_graph_entity(id) on delete cascade,
                      foreign key(target_entity_id) references requirement_graph_entity(id) on delete cascade,
                      unique(snapshot_id,source_entity_id,relation_type,target_entity_id)
                    )
                    """);
            addColumn(statement, "requirement_graph_relation", "claim_status", "text not null default 'EXTRACTED'");
            addColumn(statement, "requirement_graph_relation", "condition", "text");
            addColumn(statement, "requirement_graph_relation", "scenario", "text");
            addColumn(statement, "requirement_graph_relation", "statement_variants", "text not null default '[]'");
            addColumn(statement, "requirement_graph_relation", "uncertainty_ids", "text not null default '[]'");
            addColumn(statement, "requirement_graph_relation", "conflict_set_ids", "text not null default '[]'");
            addColumn(statement, "requirement_graph_relation", "review_reason", "text");

            statement.executeUpdate("""
                    create table if not exists requirement_graph_evidence(
                      snapshot_id text not null,
                      evidence_id text not null,
                      filename text not null,
                      parent_id text,
                      parent_order integer not null,
                      version text not null,
                      content_hash text,
                      section_path text,
                      quote text,
                      start_offset integer not null,
                      end_offset integer not null,
                      resolution_status text not null,
                      primary key(snapshot_id, evidence_id),
                      foreign key(snapshot_id) references requirement_graph_snapshot(id) on delete cascade
                    )
                    """);
            statement.executeUpdate("""
                    create table if not exists requirement_graph_claim_evidence(
                      snapshot_id text not null,
                      claim_id text not null,
                      evidence_id text not null,
                      support_type text not null default 'SUPPORTED',
                      confidence real not null default 1.0,
                      created_at text not null,
                      primary key(snapshot_id, claim_id, evidence_id),
                      foreign key(snapshot_id) references requirement_graph_snapshot(id) on delete cascade,
                      foreign key(snapshot_id, evidence_id) references requirement_graph_evidence(snapshot_id, evidence_id) on delete cascade
                    )
                    """);
            statement.executeUpdate("create index if not exists idx_req_graph_claim_evidence_claim on requirement_graph_claim_evidence(snapshot_id,claim_id)");
            statement.executeUpdate("create index if not exists idx_req_graph_claim_evidence_evidence on requirement_graph_claim_evidence(snapshot_id,evidence_id)");
            statement.executeUpdate("""
                    create table if not exists requirement_graph_uncertainty(
                      id text primary key, snapshot_id text not null, window_id text, code text not null,
                      message text not null, claim_ids text not null, status text not null, created_at text not null,
                      foreign key(snapshot_id) references requirement_graph_snapshot(id) on delete cascade
                    )
                    """);
            statement.executeUpdate("""
                    create table if not exists requirement_graph_conflict(
                      id text primary key, snapshot_id text not null, kind text not null, claim_ids text not null,
                      description text not null, status text not null, created_at text not null,
                      foreign key(snapshot_id) references requirement_graph_snapshot(id) on delete cascade
                    )
                    """);
            statement.executeUpdate("""
                    create table if not exists requirement_graph_audit(
                      id text primary key, snapshot_id text not null, claim_id text, action text not null,
                      actor text not null, reason text, occurred_at text not null,
                      foreign key(snapshot_id) references requirement_graph_snapshot(id) on delete cascade
                    )
                    """);
            statement.executeUpdate("""
                    create table if not exists requirement_graph_build_job(
                      build_id text primary key,
                      snapshot_id text,
                      project_id text not null,
                      document_id text not null,
                      requirement_version text not null,
                      state text not null,
                      completed_windows integer not null default 0,
                      total_windows integer not null default 0,
                      error_code text,
                      error_message text,
                      request_json text not null,
                      resume_snapshot_id text,
                      cancel_requested integer not null default 0,
                      created_at text not null,
                      started_at text,
                      finished_at text
                    )
                    """);
            statement.executeUpdate("create index if not exists idx_req_graph_job_state on requirement_graph_build_job(state,created_at)");
            statement.executeUpdate("create index if not exists idx_req_graph_snapshot_scope on requirement_graph_snapshot(business_project_id,document_id,requirement_version,status)");
            statement.executeUpdate("create index if not exists idx_req_graph_entity_snapshot on requirement_graph_entity(snapshot_id,type,canonical_name)");
            statement.executeUpdate("create index if not exists idx_req_graph_relation_source on requirement_graph_relation(snapshot_id,source_entity_id,relation_type)");
            statement.executeUpdate("create index if not exists idx_req_graph_relation_target on requirement_graph_relation(snapshot_id,target_entity_id,relation_type)");
            migrateWindowCompositeKeys(connection);
            migrateEvidenceCompositeKey(connection);
        } catch (SQLException exception) {
            throw failure("Unable to initialize requirement graph store", exception);
        }
    }

    private void addColumn(Statement statement, String table, String column, String definition) throws SQLException {
        try {
            statement.executeUpdate("alter table " + table + " add column " + column + " " + definition);
        } catch (SQLException exception) {
            if (!exception.getMessage().toLowerCase().contains("duplicate column")) throw exception;
        }
    }

    /** 判断某表是否已使用给定列的复合主键（旧库是单列主键，需要重建迁移）。 */
    private boolean hasCompositePrimaryKey(Connection connection, String table, List<String> columns) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("pragma table_info(" + table + ")")) {
            Map<String, Integer> pk = new LinkedHashMap<>();
            while (rows.next()) pk.put(rows.getString("name"), rows.getInt("pk"));
            if (pk.isEmpty()) return true; // 表不存在，无需迁移
            return columns.stream().allMatch(column -> pk.containsKey(column) && pk.get(column) != null && pk.get(column) > 0);
        }
    }

    /** 把 window/window_result 从全局窗口主键迁移为 (snapshot_id, id)/(snapshot_id, window_id) 复合主键。 */
    private void migrateWindowCompositeKeys(Connection connection) throws SQLException {
        boolean migrateWindow = !hasCompositePrimaryKey(connection, "requirement_graph_window", List.of("snapshot_id", "id"));
        boolean migrateResult = !hasCompositePrimaryKey(connection, "requirement_graph_window_result", List.of("snapshot_id", "window_id"));
        if (!migrateWindow && !migrateResult) return;
        connection.createStatement().execute("pragma foreign_keys=OFF");
        try {
            if (migrateWindow) {
                connection.createStatement().executeUpdate("alter table requirement_graph_window rename to requirement_graph_window_old");
                connection.createStatement().executeUpdate("""
                        create table requirement_graph_window(
                          id text not null,
                          snapshot_id text not null,
                          filename text not null,
                          parent_id text,
                          section_path text,
                          heading text,
                          window_index integer not null,
                          start_offset integer not null,
                          end_offset integer not null,
                          content_hash text not null,
                          status text not null,
                          attempt_count integer not null default 0,
                          last_error_code text,
                          started_at text,
                          completed_at text,
                          continuation_of text,
                          primary key(snapshot_id, id),
                          foreign key(snapshot_id) references requirement_graph_snapshot(id) on delete cascade
                        )
                        """);
                connection.createStatement().executeUpdate("""
                        insert into requirement_graph_window(
                          id,snapshot_id,filename,parent_id,section_path,heading,window_index,start_offset,end_offset,
                          content_hash,status,attempt_count,last_error_code,started_at,completed_at,continuation_of)
                        select id,snapshot_id,filename,parent_id,section_path,heading,window_index,start_offset,end_offset,
                          content_hash,status,attempt_count,last_error_code,started_at,completed_at,continuation_of
                        from requirement_graph_window_old
                        """);
                connection.createStatement().executeUpdate("drop table requirement_graph_window_old");
            }
            if (migrateResult) {
                connection.createStatement().executeUpdate("alter table requirement_graph_window_result rename to requirement_graph_window_result_old");
                connection.createStatement().executeUpdate("""
                        create table requirement_graph_window_result(
                          window_id text not null,
                          snapshot_id text not null,
                          result_json text not null,
                          updated_at text not null,
                          primary key(snapshot_id, window_id),
                          foreign key(snapshot_id) references requirement_graph_snapshot(id) on delete cascade
                        )
                        """);
                connection.createStatement().executeUpdate("""
                        insert into requirement_graph_window_result(window_id,snapshot_id,result_json,updated_at)
                        select window_id,snapshot_id,result_json,updated_at from requirement_graph_window_result_old
                        """);
                connection.createStatement().executeUpdate("drop table requirement_graph_window_result_old");
            }
        } finally {
            connection.createStatement().execute("pragma foreign_keys=ON");
        }
    }

    /** 把 evidence 迁移为 (snapshot_id, evidence_id) 复合主键，并同步重建 claim_evidence 的复合外键。 */
    private void migrateEvidenceCompositeKey(Connection connection) throws SQLException {
        if (hasCompositePrimaryKey(connection, "requirement_graph_evidence", List.of("snapshot_id", "evidence_id"))) return;
        connection.createStatement().execute("pragma foreign_keys=OFF");
        try {
            connection.createStatement().executeUpdate("alter table requirement_graph_evidence rename to requirement_graph_evidence_old");
            connection.createStatement().executeUpdate("alter table requirement_graph_claim_evidence rename to requirement_graph_claim_evidence_old");
            connection.createStatement().executeUpdate("""
                    create table requirement_graph_evidence(
                      snapshot_id text not null,
                      evidence_id text not null,
                      filename text not null,
                      parent_id text,
                      parent_order integer not null,
                      version text not null,
                      content_hash text,
                      section_path text,
                      quote text,
                      start_offset integer not null,
                      end_offset integer not null,
                      resolution_status text not null,
                      primary key(snapshot_id, evidence_id),
                      foreign key(snapshot_id) references requirement_graph_snapshot(id) on delete cascade
                    )
                    """);
            connection.createStatement().executeUpdate("""
                    create table requirement_graph_claim_evidence(
                      snapshot_id text not null,
                      claim_id text not null,
                      evidence_id text not null,
                      support_type text not null default 'SUPPORTED',
                      confidence real not null default 1.0,
                      created_at text not null,
                      primary key(snapshot_id, claim_id, evidence_id),
                      foreign key(snapshot_id) references requirement_graph_snapshot(id) on delete cascade,
                      foreign key(snapshot_id, evidence_id) references requirement_graph_evidence(snapshot_id, evidence_id) on delete cascade
                    )
                    """);
            connection.createStatement().executeUpdate("""
                    insert into requirement_graph_evidence(
                      snapshot_id,evidence_id,filename,parent_id,parent_order,version,content_hash,section_path,
                      quote,start_offset,end_offset,resolution_status)
                    select snapshot_id,evidence_id,filename,parent_id,parent_order,version,content_hash,section_path,
                      quote,start_offset,end_offset,resolution_status
                    from requirement_graph_evidence_old
                    """);
            connection.createStatement().executeUpdate("""
                    insert into requirement_graph_claim_evidence(
                      snapshot_id,claim_id,evidence_id,support_type,confidence,created_at)
                    select snapshot_id,claim_id,evidence_id,support_type,confidence,created_at
                    from requirement_graph_claim_evidence_old
                    """);
            connection.createStatement().executeUpdate("drop table requirement_graph_claim_evidence_old");
            connection.createStatement().executeUpdate("drop table requirement_graph_evidence_old");
        } finally {
            connection.createStatement().execute("pragma foreign_keys=ON");
        }
    }

    public synchronized void saveSnapshot(GraphSnapshot snapshot) {
        requireText(snapshot == null ? null : snapshot.id(), "snapshot id");
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                insert into requirement_graph_snapshot(
                  id,business_project_id,document_id,requirement_version,source_revision,
                  extraction_model,prompt_version,status,entity_count,relation_count,created_at,updated_at,published_at,
                  schema_version,ontology_version,coverage_ratio,window_count,succeeded_window_count,failed_window_count,
                  warning_count,build_id,published_by,publication_reason,stale_at)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                on conflict(id) do update set
                  extraction_model=excluded.extraction_model,prompt_version=excluded.prompt_version,status=excluded.status,
                  entity_count=excluded.entity_count,relation_count=excluded.relation_count,updated_at=excluded.updated_at,
                  published_at=excluded.published_at,schema_version=excluded.schema_version,ontology_version=excluded.ontology_version,
                  coverage_ratio=excluded.coverage_ratio,window_count=excluded.window_count,succeeded_window_count=excluded.succeeded_window_count,
                  failed_window_count=excluded.failed_window_count,warning_count=excluded.warning_count,build_id=excluded.build_id,
                  published_by=excluded.published_by,publication_reason=excluded.publication_reason,stale_at=excluded.stale_at
                """)) {
            bindSnapshot(statement, snapshot);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw failure("Unable to save requirement graph snapshot", exception);
        }
    }

    public synchronized void deleteSnapshot(String snapshotId) {
        requireText(snapshotId, "snapshot id");
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "delete from requirement_graph_snapshot where id=?")) {
            statement.setString(1, snapshotId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw failure("Unable to delete requirement graph snapshot", exception);
        }
    }

    /** 已发布快照不可变：所有图数据写入口必须在写入前校验，防止构建/恢复覆盖发布结果。 */
    private void assertMutable(String snapshotId) {
        if (snapshotId == null || snapshotId.isBlank()) return;
        findSnapshotById(snapshotId).ifPresent(snapshot -> {
            if (snapshot.status() == SnapshotStatus.PUBLISHED) {
                throw new RequirementGraphException("GRAPH_SNAPSHOT_IMMUTABLE", "已发布需求语义图快照不可修改");
            }
        });
    }

    public synchronized void replaceDraft(GraphSnapshot snapshot, List<Entity> entities, List<Relation> relations) {
        requireText(snapshot == null ? null : snapshot.id(), "snapshot id");
        assertMutable(snapshot.id());
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                deleteGraph(connection, snapshot.id());
                upsertSnapshot(connection, snapshot);
                insertEntities(connection, entities == null ? List.of() : entities);
                insertRelations(connection, relations == null ? List.of() : relations);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw failure("Unable to replace requirement graph draft", exception);
        }
    }

    /**
     * 原子保存一份完整草稿：快照、证据、实体/关系、Claim→Evidence 关联与不确定性/冲突。
     * 写入顺序保证在开启外键约束时 Claim→Evidence 只会引用已存在的证据。
     */
    public synchronized void saveDraftSnapshot(GraphSnapshot snapshot, List<Entity> entities, List<Relation> relations,
                                               List<RequirementGraphModels.Evidence> evidence,
                                               List<Uncertainty> uncertainties, List<Conflict> conflicts) {
        requireText(snapshot == null ? null : snapshot.id(), "snapshot id");
        assertMutable(snapshot.id());
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                deleteGraph(connection, snapshot.id());
                upsertSnapshot(connection, snapshot);
                insertEvidence(connection, snapshot.id(), evidence == null ? List.of() : evidence);
                insertEntities(connection, entities == null ? List.of() : entities);
                insertRelations(connection, relations == null ? List.of() : relations);
                for (Entity entity : entities == null ? List.<Entity>of() : entities) {
                    insertClaimEvidence(connection, snapshot.id(), entity.id(), entity.sourceEvidenceIds(), entity.confidence());
                }
                for (Relation relation : relations == null ? List.<Relation>of() : relations) {
                    insertClaimEvidence(connection, snapshot.id(), relation.id(), relation.sourceEvidenceIds(), relation.confidence());
                }
                insertUncertainties(connection, snapshot.id(), uncertainties == null ? List.of() : uncertainties);
                insertConflicts(connection, snapshot.id(), conflicts == null ? List.of() : conflicts);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw failure("Unable to save requirement graph draft snapshot", exception);
        }
    }

    public synchronized void saveWindows(String snapshotId, List<RequirementGraphWindowView> windows) {
        assertMutable(snapshotId);
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    insert into requirement_graph_window(id,snapshot_id,filename,parent_id,section_path,heading,
                      window_index,start_offset,end_offset,content_hash,status,attempt_count,last_error_code,
                      started_at,completed_at,continuation_of) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    on conflict(snapshot_id,id) do nothing
                    """)) {
                for (RequirementGraphWindowView window : windows == null ? List.<RequirementGraphWindowView>of() : windows) {
                    bindWindow(statement, snapshotId, window);
                    statement.addBatch();
                }
                statement.executeBatch();
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw failure("Unable to save requirement graph windows", exception);
        }
    }

    public synchronized List<RequirementGraphWindowView> windows(String snapshotId) {
        return queryWindows(snapshotId);
    }

    public synchronized Map<String, RequirementGraphModels.ExtractionResult> windowResults(String snapshotId) {
        Map<String, RequirementGraphModels.ExtractionResult> result = new LinkedHashMap<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "select window_id,result_json from requirement_graph_window_result where snapshot_id=?")) {
            statement.setString(1, snapshotId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    try {
                        result.put(rows.getString("window_id"), objectMapper.readValue(rows.getString("result_json"),
                                RequirementGraphModels.ExtractionResult.class));
                    } catch (JsonProcessingException exception) {
                        throw new IllegalStateException("需求语义图窗口结果损坏", exception);
                    }
                }
            }
            return Map.copyOf(result);
        } catch (SQLException exception) {
            throw failure("Unable to read requirement graph window results", exception);
        }
    }

    public synchronized void saveWindowResult(String snapshotId, String windowId,
                                              RequirementGraphModels.ExtractionResult result) {
        assertMutable(snapshotId);
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                insert into requirement_graph_window_result(window_id,snapshot_id,result_json,updated_at)
                values(?,?,?,?) on conflict(snapshot_id,window_id) do update set result_json=excluded.result_json,updated_at=excluded.updated_at
                """)) {
            statement.setString(1, windowId);
            statement.setString(2, snapshotId);
            statement.setString(3, objectMapper.writeValueAsString(result));
            statement.setString(4, Instant.now().toString());
            statement.executeUpdate();
        } catch (SQLException | JsonProcessingException exception) {
            throw new IllegalStateException("Unable to save requirement graph window result", exception);
        }
    }

    public synchronized void saveEntityEmbeddings(String snapshotId, Map<String, float[]> vectors) {
        assertMutable(snapshotId);
        saveEmbeddings("requirement_graph_entity_embedding", "entity_id", snapshotId, vectors);
    }

    public synchronized void saveRelationEmbeddings(String snapshotId, Map<String, float[]> vectors) {
        assertMutable(snapshotId);
        saveEmbeddings("requirement_graph_relation_embedding", "relation_id", snapshotId, vectors);
    }

    private void saveEmbeddings(String table, String idColumn, String snapshotId, Map<String, float[]> vectors) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "insert into " + table + "(" + idColumn + ",snapshot_id,vector_json) values(?,?,?) "
                        + "on conflict(" + idColumn + ") do update set vector_json=excluded.vector_json")) {
            for (Map.Entry<String, float[]> entry : vectors == null ? Map.<String, float[]>of().entrySet() : vectors.entrySet()) {
                statement.setString(1, entry.getKey());
                statement.setString(2, snapshotId);
                statement.setString(3, objectMapper.writeValueAsString(entry.getValue()));
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException | JsonProcessingException exception) {
            throw new IllegalStateException("Unable to save requirement graph embeddings", exception);
        }
    }

    public synchronized void updateWindow(String snapshotId, RequirementGraphWindowView window) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                update requirement_graph_window set status=?,attempt_count=?,last_error_code=?,started_at=coalesce(?,started_at),completed_at=?
                where snapshot_id=? and id=?
                """)) {
            statement.setString(1, window.status().name());
            statement.setInt(2, window.attemptCount());
            statement.setString(3, window.lastErrorCode());
            statement.setString(4, text(window.startedAt()));
            statement.setString(5, text(window.completedAt()));
            statement.setString(6, snapshotId);
            statement.setString(7, window.id());
            if (statement.executeUpdate() != 1) throw new IllegalArgumentException("未知需求图窗口: " + window.id());
        } catch (SQLException exception) {
            throw failure("Unable to update requirement graph window", exception);
        }
    }

    public synchronized void saveEvidence(String snapshotId, List<RequirementGraphModels.Evidence> evidence) {
        assertMutable(snapshotId);
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                insertEvidence(connection, snapshotId, evidence == null ? List.of() : evidence);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) { throw failure("Unable to save requirement graph evidence", exception); }
    }

    private void insertEvidence(Connection connection, String snapshotId, List<RequirementGraphModels.Evidence> evidence) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into requirement_graph_evidence(snapshot_id,evidence_id,filename,parent_id,parent_order,version,
                  content_hash,section_path,quote,start_offset,end_offset,resolution_status)
                values(?,?,?,?,?,?,?,?,?,?,?,?) on conflict(snapshot_id,evidence_id) do update set
                  resolution_status=excluded.resolution_status,quote=excluded.quote,start_offset=excluded.start_offset,
                  end_offset=excluded.end_offset,section_path=excluded.section_path
                """)) {
            for (RequirementGraphModels.Evidence item : evidence) {
                statement.setString(1, snapshotId); statement.setString(2, item.evidenceId()); statement.setString(3, item.filename());
                statement.setString(4, item.parentId()); statement.setInt(5, item.parentOrder()); statement.setString(6, item.version());
                statement.setString(7, item.contentHash()); statement.setString(8, item.sectionPath()); statement.setString(9, item.quote());
                statement.setInt(10, item.startOffset()); statement.setInt(11, item.endOffset()); statement.setString(12, item.resolutionStatus().name());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /** 规范化 Claim→Evidence 关联：删除旧关联后按当前 source_evidence_ids 重建。 */
    public synchronized void replaceClaimEvidence(String snapshotId, String claimId, List<String> evidenceIds, double confidence) {
        requireText(snapshotId, "snapshot id");
        requireText(claimId, "claim id");
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                deleteClaimEvidence(connection, snapshotId, claimId);
                insertClaimEvidence(connection, snapshotId, claimId, evidenceIds, confidence);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw failure("Unable to replace requirement graph claim evidence", exception);
        }
    }

    /** 读取一个 Claim 的规范证据关联，用于跟踪与发布审计。 */
    public synchronized List<RequirementGraphModels.ClaimEvidence> claimEvidence(String snapshotId, String claimId) {
        List<RequirementGraphModels.ClaimEvidence> result = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "select snapshot_id,claim_id,evidence_id,support_type,confidence,created_at " +
                        "from requirement_graph_claim_evidence where snapshot_id=? and claim_id=? order by confidence desc,created_at")) {
            statement.setString(1, snapshotId);
            statement.setString(2, claimId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new RequirementGraphModels.ClaimEvidence(rows.getString("snapshot_id"),
                            rows.getString("claim_id"), rows.getString("evidence_id"), rows.getString("support_type"),
                            rows.getDouble("confidence"), instant(rows.getString("created_at"))));
                }
            }
            return List.copyOf(result);
        } catch (SQLException exception) {
            throw failure("Unable to read requirement graph claim evidence", exception);
        }
    }

    public synchronized void saveUncertainties(String snapshotId, List<Uncertainty> uncertainties) {
        assertMutable(snapshotId);
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                insertUncertainties(connection, snapshotId, uncertainties == null ? List.of() : uncertainties);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) { throw failure("Unable to save requirement graph uncertainties", exception); }
    }

    private void insertUncertainties(Connection connection, String snapshotId, List<Uncertainty> uncertainties) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into requirement_graph_uncertainty(id,snapshot_id,window_id,code,message,claim_ids,status,created_at)
                values(?,?,?,?,?,?,?,?) on conflict(id) do update set message=excluded.message,status=excluded.status
                """)) {
            for (Uncertainty item : uncertainties) {
                statement.setString(1, item.id()); statement.setString(2, snapshotId); statement.setString(3, item.windowId());
                statement.setString(4, item.code()); statement.setString(5, item.message()); statement.setString(6, json(item.claimIds()));
                statement.setString(7, item.status().name()); statement.setString(8, text(item.createdAt())); statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    public synchronized void saveConflicts(String snapshotId, List<Conflict> conflicts) {
        assertMutable(snapshotId);
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                insertConflicts(connection, snapshotId, conflicts == null ? List.of() : conflicts);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) { throw failure("Unable to save requirement graph conflicts", exception); }
    }

    private void insertConflicts(Connection connection, String snapshotId, List<Conflict> conflicts) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into requirement_graph_conflict(id,snapshot_id,kind,claim_ids,description,status,created_at)
                values(?,?,?,?,?,?,?) on conflict(id) do update set description=excluded.description,status=excluded.status
                """)) {
            for (Conflict item : conflicts) {
                statement.setString(1, item.id()); statement.setString(2, snapshotId); statement.setString(3, item.kind());
                statement.setString(4, json(item.claimIds())); statement.setString(5, item.description()); statement.setString(6, item.status().name());
                statement.setString(7, text(item.createdAt())); statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    public synchronized void updateStatus(String snapshotId, SnapshotStatus status, String reviewer) {
        updateStatus(snapshotId, status, reviewer, null);
    }

    public synchronized void updateStatus(String snapshotId, SnapshotStatus status, String reviewer, String reason) {
        requireText(snapshotId, "snapshot id");
        String now = Instant.now().toString();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                update requirement_graph_snapshot set status=?,updated_at=?,published_at=?,published_by=?,publication_reason=? where id=?
                """)) {
            statement.setString(1, status.name());
            statement.setString(2, now);
            statement.setString(3, status == SnapshotStatus.PUBLISHED ? now : null);
            statement.setString(4, status == SnapshotStatus.PUBLISHED ? reviewer : null);
            statement.setString(5, reason);
            statement.setString(6, snapshotId);
            if (statement.executeUpdate() != 1) throw new IllegalArgumentException("未知需求图快照: " + snapshotId);
            audit(connection, snapshotId, null, status.name(), reviewer, reason, now);
        } catch (SQLException exception) {
            throw failure("Unable to update requirement graph snapshot", exception);
        }
    }

    public synchronized void reviewEntity(String entityId, ClaimStatus status, String reviewer, String reason) {
        updateClaim("entity", entityId, status, reviewer, reason);
    }

    public synchronized void reviewRelation(String relationId, ClaimStatus status, String reviewer, String reason) {
        updateClaim("relation", relationId, status, reviewer, reason);
    }

    public synchronized ClaimPage claims(String snapshotId, ClaimStatus status, int limit, int offset) {
        List<Entity> entities = queryEntities("select * from requirement_graph_entity where snapshot_id=?"
                + (status == null ? "" : " and claim_status=?") + " order by id limit ? offset ?",
                status == null ? List.of(snapshotId) : List.of(snapshotId, status.name()), Math.max(1, Math.min(limit, 200)), offset);
        List<Relation> relations = queryRelations("select * from requirement_graph_relation where snapshot_id=?"
                + (status == null ? "" : " and claim_status=?") + " order by id limit ? offset ?",
                status == null ? List.of(snapshotId) : List.of(snapshotId, status.name()), Math.max(1, Math.min(limit, 200)), offset);
        return new ClaimPage(entities, relations, entities.size() + relations.size(),
                entities.size() >= limit || relations.size() >= limit);
    }

    public synchronized List<AuditEntry> audits(String snapshotId) {
        List<AuditEntry> result = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "select * from requirement_graph_audit where snapshot_id=? order by occurred_at,id")) {
            statement.setString(1, snapshotId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new AuditEntry(rows.getString("id"), snapshotId,
                        rows.getString("claim_id"), rows.getString("action"), rows.getString("actor"),
                        rows.getString("reason"), instant(rows.getString("occurred_at"))));
            }
            return List.copyOf(result);
        } catch (SQLException exception) {
            throw failure("Unable to read requirement graph audit", exception);
        }
    }

    public synchronized GraphSnapshot requireSnapshot(String snapshotId) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "select * from requirement_graph_snapshot where id=?")) {
            statement.setString(1, snapshotId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException("未知需求图快照: " + snapshotId);
                return snapshot(result);
            }
        } catch (SQLException exception) {
            throw failure("Unable to read requirement graph snapshot", exception);
        }
    }

    public synchronized Optional<GraphSnapshot> findSnapshotById(String snapshotId) {
        if (snapshotId == null || snapshotId.isBlank()) return Optional.empty();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "select * from requirement_graph_snapshot where id=?")) {
            statement.setString(1, snapshotId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(snapshot(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw failure("Unable to read requirement graph snapshot", exception);
        }
    }

    /** 按业务唯一域查询快照（project/doc/version/sourceRev/prompt），用于内容身份复用与迁移兼容。 */
    public synchronized Optional<GraphSnapshot> findSnapshotByScope(String projectId, String documentId,
                                                                    String version, String sourceRevision,
                                                                    String promptVersion) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                select * from requirement_graph_snapshot
                where business_project_id=? and document_id=? and requirement_version=?
                  and source_revision=? and prompt_version=?
                order by updated_at desc limit 1
                """)) {
            statement.setString(1, projectId);
            statement.setString(2, documentId);
            statement.setString(3, version);
            statement.setString(4, sourceRevision);
            statement.setString(5, promptVersion);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(snapshot(rows)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw failure("Unable to find requirement graph snapshot by scope", exception);
        }
    }

    public synchronized Optional<GraphSnapshot> findLatest(String projectId, String documentId, String version) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                select * from requirement_graph_snapshot
                where business_project_id=? and document_id=? and requirement_version=?
                  and status in ('PUBLISHED','VERIFIED','REVIEW_REQUIRED','DRAFT','PARTIAL_FAILED')
                order by case status when 'PUBLISHED' then 0 when 'VERIFIED' then 1
                         when 'REVIEW_REQUIRED' then 2 when 'DRAFT' then 3 when 'PARTIAL_FAILED' then 4 else 5 end,
                         updated_at desc limit 1
                """)) {
            statement.setString(1, projectId);
            statement.setString(2, documentId);
            statement.setString(3, version);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(snapshot(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw failure("Unable to find requirement graph snapshot", exception);
        }
    }

    public synchronized Optional<GraphSnapshot> findSnapshotByBuildId(String buildId) {
        if (buildId == null || buildId.isBlank()) return Optional.empty();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "select * from requirement_graph_snapshot where build_id=? order by updated_at desc limit 1")) {
            statement.setString(1, buildId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(snapshot(rows)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw failure("Unable to find requirement graph snapshot by build id", exception);
        }
    }

    public synchronized List<GraphSnapshot> listSnapshots(String projectId, String documentId, String version) {
        StringBuilder sql = new StringBuilder("select * from requirement_graph_snapshot where business_project_id=?");
        List<String> values = new ArrayList<>();
        values.add(projectId);
        if (documentId != null && !documentId.isBlank()) { sql.append(" and document_id=?"); values.add(documentId); }
        if (version != null && !version.isBlank()) { sql.append(" and requirement_version=?"); values.add(version); }
        sql.append(" order by updated_at desc, id asc");
        return querySnapshots(sql.toString(), values);
    }

    public synchronized List<Entity> entities(String snapshotId, String query, String type, int limit) {
        StringBuilder sql = new StringBuilder("select * from requirement_graph_entity where snapshot_id=?");
        List<String> values = new ArrayList<>();
        values.add(snapshotId);
        if (type != null && !type.isBlank()) { sql.append(" and type=?"); values.add(type); }
        if (query != null && !query.isBlank()) {
            sql.append(" and (lower(canonical_name) like ? or lower(display_name) like ? or lower(aliases) like ?)");
            String term = "%" + query.trim().toLowerCase(java.util.Locale.ROOT) + "%";
            values.add(term); values.add(term); values.add(term);
        }
        sql.append(" order by confidence desc, canonical_name asc limit ? offset ?");
        return queryEntities(sql.toString(), values, Math.max(1, Math.min(limit, 200)), 0);
    }

    public synchronized Entity requireEntity(String snapshotId, String entityId) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "select * from requirement_graph_entity where snapshot_id=? and id=?")) {
            statement.setString(1, snapshotId);
            statement.setString(2, entityId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new RequirementGraphException("GRAPH_INPUT_EMPTY", "未知需求图实体: " + entityId);
                return entity(rows);
            }
        } catch (SQLException exception) {
            throw failure("Unable to read requirement graph entity", exception);
        }
    }

    public synchronized List<Entity> allEntities(String snapshotId, int limit) { return entities(snapshotId, null, null, limit); }

    public synchronized List<Relation> allRelations(String snapshotId, int limit) {
        return queryRelations("select * from requirement_graph_relation where snapshot_id=? order by confidence desc,id asc limit ? offset ?",
                List.of(snapshotId), Math.max(1, Math.min(limit, 100_000)), 0);
    }

    public synchronized List<Relation> relationsForEntity(String snapshotId, String entityId, int limit) {
        return queryRelations("select * from requirement_graph_relation where snapshot_id=? and (source_entity_id=? or target_entity_id=?) order by confidence desc,id asc limit ? offset ?",
                List.of(snapshotId, entityId, entityId), Math.max(1, Math.min(limit, 10_000)), 0);
    }

    public synchronized String snapshotIdForEntity(String entityId) {
        return snapshotIdForClaim("requirement_graph_entity", entityId);
    }

    public synchronized String snapshotIdForRelation(String relationId) {
        return snapshotIdForClaim("requirement_graph_relation", relationId);
    }

    private String snapshotIdForClaim(String table, String claimId) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "select snapshot_id from " + table + " where id=?")) {
            statement.setString(1, claimId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("未知需求图声明: " + claimId);
                return rows.getString(1);
            }
        } catch (SQLException exception) {
            throw failure("Unable to resolve requirement graph claim", exception);
        }
    }

    public synchronized Relation requireRelation(String snapshotId, String relationId) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "select * from requirement_graph_relation where snapshot_id=? and id=?")) {
            statement.setString(1, snapshotId);
            statement.setString(2, relationId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new RequirementGraphException("GRAPH_INPUT_EMPTY", "未知需求图关系: " + relationId);
                return relation(rows);
            }
        } catch (SQLException exception) {
            throw failure("Unable to read requirement graph relation", exception);
        }
    }

    public synchronized int countEntities(String snapshotId) {
        return count("requirement_graph_entity", snapshotId);
    }

    public synchronized int countRelations(String snapshotId) {
        return count("requirement_graph_relation", snapshotId);
    }

    public synchronized Map<String, float[]> entityEmbeddings(String snapshotId) {
        return embeddings("requirement_graph_entity_embedding", "entity_id", snapshotId);
    }

    public synchronized Map<String, float[]> relationEmbeddings(String snapshotId) {
        return embeddings("requirement_graph_relation_embedding", "relation_id", snapshotId);
    }

    public synchronized List<RequirementGraphModels.Evidence> evidence(String snapshotId, java.util.Set<String> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        String sql = "select * from requirement_graph_evidence where snapshot_id=? and evidence_id in (" + placeholders + ") order by evidence_id";
        List<RequirementGraphModels.Evidence> result = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, snapshotId);
            for (String id : ids) statement.setString(index++, id);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(new RequirementGraphModels.Evidence(rows.getString("evidence_id"),
                        rows.getString("filename"), rows.getString("parent_id"), rows.getInt("parent_order"),
                        rows.getString("version"), rows.getString("quote"), rows.getString("content_hash"),
                        rows.getString("section_path"), rows.getString("quote"), rows.getInt("start_offset"),
                        rows.getInt("end_offset"), RequirementGraphModels.EvidenceResolutionStatus.valueOf(rows.getString("resolution_status"))));
            }
            return List.copyOf(result);
        } catch (SQLException exception) {
            throw failure("Unable to read requirement graph evidence", exception);
        }
    }

    public synchronized List<String> publicationBlockers(String snapshotId) {
        List<String> blockers = new ArrayList<>();
        try (Connection connection = open()) {
            for (String table : List.of("requirement_graph_entity", "requirement_graph_relation")) {
                try (PreparedStatement statement = connection.prepareStatement("select id from " + table + " where snapshot_id=? and claim_status<>?")) {
                    statement.setString(1, snapshotId);
                    statement.setString(2, ClaimStatus.VERIFIED.name());
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) blockers.add("UNVERIFIED_CLAIM:" + rows.getString(1));
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement("select evidence_id from requirement_graph_evidence where snapshot_id=? and resolution_status<>?")) {
                statement.setString(1, snapshotId);
                statement.setString(2, RequirementGraphModels.EvidenceResolutionStatus.RESOLVED.name());
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) blockers.add("UNRESOLVED_EVIDENCE:" + rows.getString(1));
                }
            }
            blockers.addAll(missingEvidenceBlockers(connection, snapshotId));
            return List.copyOf(blockers);
        } catch (SQLException exception) {
            throw failure("Unable to validate requirement graph publication", exception);
        }
    }

    /** 校验 VERIFIED 声明引用的 source_evidence_ids 确实存在于证据表且为 RESOLVED，防止悬空引用发布。 */
    private List<String> missingEvidenceBlockers(Connection connection, String snapshotId) throws SQLException {
        Map<String, String> evidenceStatus = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "select evidence_id, resolution_status from requirement_graph_evidence where snapshot_id=?")) {
            statement.setString(1, snapshotId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) evidenceStatus.put(rows.getString(1), rows.getString(2));
            }
        }
        ReferencedEvidence referenced = referencedEvidence(connection, snapshotId);
        List<String> blockers = new ArrayList<>();
        for (String evidenceId : referenced.ids()) {
            String status = evidenceStatus.get(evidenceId);
            if (status == null) {
                blockers.add("GRAPH_EVIDENCE_MISSING:" + referenced.claimByEvidence().get(evidenceId) + ":" + evidenceId);
            } else if (!RequirementGraphModels.EvidenceResolutionStatus.RESOLVED.name().equals(status)) {
                blockers.add("UNRESOLVED_EVIDENCE:" + evidenceId);
            }
        }
        return blockers;
    }

    /** 优先使用规范化 claim_evidence 表；旧快照无关联表数据时回退读取 JSON source_evidence_ids。 */
    private ReferencedEvidence referencedEvidence(Connection connection, String snapshotId) throws SQLException {
        Map<String, String> claimByEvidence = new LinkedHashMap<>();
        Set<String> referenced = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "select claim_id, evidence_id from requirement_graph_claim_evidence where snapshot_id=? order by confidence desc, created_at")) {
            statement.setString(1, snapshotId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String evidenceId = rows.getString(2);
                    referenced.add(evidenceId);
                    claimByEvidence.putIfAbsent(evidenceId, rows.getString(1));
                }
            }
        }
        if (!referenced.isEmpty()) {
            return new ReferencedEvidence(referenced, claimByEvidence);
        }
        for (String table : List.of("requirement_graph_entity", "requirement_graph_relation")) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "select id, source_evidence_ids from " + table + " where snapshot_id=? and claim_status=?")) {
                statement.setString(1, snapshotId);
                statement.setString(2, ClaimStatus.VERIFIED.name());
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        String claimId = rows.getString(1);
                        for (String evidenceId : list(rows.getString(2))) {
                            if (evidenceId == null || evidenceId.isBlank()) continue;
                            referenced.add(evidenceId);
                            claimByEvidence.putIfAbsent(evidenceId, claimId);
                        }
                    }
                }
            }
        }
        return new ReferencedEvidence(referenced, claimByEvidence);
    }

    private record ReferencedEvidence(Set<String> ids, Map<String, String> claimByEvidence) {
    }

    public record StoredBuildJob(BuildJob job, String requestJson, boolean cancelRequested,
                                 String resumeSnapshotId) {
    }

    public synchronized void saveBuildJob(StoredBuildJob stored) {
        BuildJob job = stored.job();
        if (job == null || job.buildId() == null || job.buildId().isBlank()) {
            throw new IllegalArgumentException("build id 不能为空");
        }
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                insert into requirement_graph_build_job(
                  build_id,snapshot_id,project_id,document_id,requirement_version,state,
                  completed_windows,total_windows,error_code,error_message,request_json,
                  resume_snapshot_id,cancel_requested,created_at,started_at,finished_at)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                on conflict(build_id) do update set
                  snapshot_id=excluded.snapshot_id,
                  state=excluded.state,
                  completed_windows=excluded.completed_windows,
                  total_windows=excluded.total_windows,
                  error_code=excluded.error_code,
                  error_message=excluded.error_message,
                  request_json=excluded.request_json,
                  resume_snapshot_id=excluded.resume_snapshot_id,
                  cancel_requested=excluded.cancel_requested,
                  started_at=excluded.started_at,
                  finished_at=excluded.finished_at
                """)) {
            statement.setString(1, job.buildId());
            statement.setString(2, job.snapshotId());
            statement.setString(3, job.projectId());
            statement.setString(4, job.documentId());
            statement.setString(5, job.requirementVersion());
            statement.setString(6, job.state().name());
            statement.setInt(7, job.completedWindows());
            statement.setInt(8, job.totalWindows());
            statement.setString(9, job.errorCode());
            statement.setString(10, job.errorMessage());
            statement.setString(11, stored.requestJson());
            statement.setString(12, stored.resumeSnapshotId());
            statement.setInt(13, stored.cancelRequested() ? 1 : 0);
            statement.setString(14, text(job.createdAt()));
            statement.setString(15, text(job.startedAt()));
            statement.setString(16, text(job.finishedAt()));
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw failure("Unable to save requirement graph build job", exception);
        }
    }

    public synchronized Optional<StoredBuildJob> loadBuildJob(String buildId) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "select * from requirement_graph_build_job where build_id=?")) {
            statement.setString(1, buildId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                return Optional.of(buildJob(rows));
            }
        } catch (SQLException exception) {
            throw failure("Unable to load requirement graph build job", exception);
        }
    }

    public synchronized List<StoredBuildJob> listBuildJobs() {
        List<StoredBuildJob> result = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "select * from requirement_graph_build_job order by created_at")) {
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(buildJob(rows));
            }
            return List.copyOf(result);
        } catch (SQLException exception) {
            throw failure("Unable to list requirement graph build jobs", exception);
        }
    }

    public synchronized void updateBuildJobProgress(String buildId, int completedWindows, int totalWindows) {
        if (buildId == null || buildId.isBlank()) return;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "update requirement_graph_build_job set completed_windows=?, total_windows=? where build_id=?")) {
            statement.setInt(1, completedWindows);
            statement.setInt(2, totalWindows);
            statement.setString(3, buildId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw failure("Unable to update requirement graph build job progress", exception);
        }
    }

    public synchronized void deleteBuildJob(String buildId) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "delete from requirement_graph_build_job where build_id=?")) {
            statement.setString(1, buildId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw failure("Unable to delete requirement graph build job", exception);
        }
    }

    public synchronized void markInterruptedBuildJobs() {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                update requirement_graph_build_job set
                  snapshot_id = coalesce(snapshot_id, (
                    select id from requirement_graph_snapshot s
                    where s.build_id = requirement_graph_build_job.build_id
                    order by s.updated_at desc limit 1
                  )),
                  state=?, error_code=?, error_message=?, finished_at=?
                where state in ('QUEUED','RUNNING')
                """)) {
            statement.setString(1, BuildJobState.FAILED.name());
            statement.setString(2, "GRAPH_JOB_INTERRUPTED");
            statement.setString(3, "应用重启导致构建任务中断，可恢复后重试");
            statement.setString(4, Instant.now().toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw failure("Unable to mark interrupted requirement graph build jobs", exception);
        }
    }

    public synchronized void deleteTerminalBuildJobsBefore(Instant threshold) {
        if (threshold == null) return;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "delete from requirement_graph_build_job where state in ('SUCCEEDED','FAILED','PARTIAL_FAILED','CANCELLED') and finished_at<?")) {
            statement.setString(1, threshold.toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw failure("Unable to cleanup requirement graph build jobs", exception);
        }
    }

    private StoredBuildJob buildJob(ResultSet rows) throws SQLException {
        BuildJob job = new BuildJob(rows.getString("build_id"), rows.getString("snapshot_id"),
                rows.getString("project_id"), rows.getString("document_id"), rows.getString("requirement_version"),
                BuildJobState.valueOf(rows.getString("state")), rows.getInt("completed_windows"), rows.getInt("total_windows"),
                rows.getString("error_code"), rows.getString("error_message"), instant(rows.getString("created_at")),
                instant(rows.getString("started_at")), instant(rows.getString("finished_at")));
        return new StoredBuildJob(job, rows.getString("request_json"), rows.getInt("cancel_requested") == 1,
                rows.getString("resume_snapshot_id"));
    }

    public synchronized void updateSnapshotWarningCount(String snapshotId, int warningCount) {
        if (snapshotId == null || snapshotId.isBlank()) return;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "update requirement_graph_snapshot set warning_count=?, updated_at=? where id=?")) {
            statement.setInt(1, warningCount);
            statement.setString(2, Instant.now().toString());
            statement.setString(3, snapshotId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw failure("Unable to update requirement graph snapshot warning count", exception);
        }
    }

    public synchronized GraphSnapshot publishSnapshot(String snapshotId, String actor, String reason) {
        GraphSnapshot snapshot = requireSnapshot(snapshotId);
        if (snapshot.status() != SnapshotStatus.REVIEW_REQUIRED && snapshot.status() != SnapshotStatus.VERIFIED) {
            throw new RequirementGraphException("GRAPH_PUBLICATION_BLOCKED", "当前快照状态不允许发布");
        }
        List<String> blockers = publicationBlockers(snapshotId);
        if (!blockers.isEmpty()) {
            throw new RequirementGraphException("GRAPH_PUBLICATION_BLOCKED",
                    "存在未审核声明或未解析证据: " + blockers.size() + " " + String.join(",", blockers));
        }
        updateStatus(snapshotId, SnapshotStatus.PUBLISHED, actor, reason);
        return requireSnapshot(snapshotId);
    }

    public synchronized void patchEntity(String entityId, String displayName, String description,
                                          String actor, String reason) {
        updateEntityPatch(entityId, displayName, description, actor, reason);
    }

    public synchronized void patchRelation(String relationId, String statement, String condition,
                                            String scenario, String actor, String reason) {
        String now = Instant.now().toString();
        try (Connection connection = open()) {
            String snapshotId = claimSnapshot(connection, "requirement_graph_relation", relationId);
            ensureMutable(connection, snapshotId);
            try (PreparedStatement statementSql = connection.prepareStatement("""
                    update requirement_graph_relation set statement=?,condition=?,scenario=?,claim_status=?,reviewer=?,reviewed_at=?,review_reason=? where id=?
                    """)) {
                statementSql.setString(1, statement); statementSql.setString(2, condition); statementSql.setString(3, scenario);
                statementSql.setString(4, ClaimStatus.EXTRACTED.name()); statementSql.setString(5, actor); statementSql.setString(6, now);
                statementSql.setString(7, reason); statementSql.setString(8, relationId);
                if (statementSql.executeUpdate() != 1) throw new IllegalArgumentException("未知需求图声明: " + relationId);
            }
            audit(connection, snapshotId, relationId, "PATCH", actor, reason, now);
        } catch (SQLException exception) {
            throw failure("Unable to patch requirement graph relation", exception);
        }
    }

    public synchronized void reviewClaim(String claimId, ClaimStatus status, String actor, String reason) {
        if (claimId == null || claimId.isBlank()) throw new IllegalArgumentException("声明 ID 不能为空");
        if (claimId.startsWith("entity:")) reviewEntity(claimId, status, actor, reason);
        else if (claimId.startsWith("relation:")) reviewRelation(claimId, status, actor, reason);
        else throw new IllegalArgumentException("未知需求图声明类型");
    }

    private void updateEntityPatch(String entityId, String displayName, String description, String actor, String reason) {
        String now = Instant.now().toString();
        try (Connection connection = open()) {
            String snapshotId = claimSnapshot(connection, "requirement_graph_entity", entityId);
            ensureMutable(connection, snapshotId);
            try (PreparedStatement statement = connection.prepareStatement("""
                    update requirement_graph_entity set display_name=?,description=?,claim_status=?,reviewer=?,reviewed_at=?,review_reason=? where id=?
                    """)) {
                statement.setString(1, displayName); statement.setString(2, description); statement.setString(3, ClaimStatus.EXTRACTED.name());
                statement.setString(4, actor); statement.setString(5, now); statement.setString(6, reason); statement.setString(7, entityId);
                if (statement.executeUpdate() != 1) throw new IllegalArgumentException("未知需求图声明: " + entityId);
            }
            audit(connection, snapshotId, entityId, "PATCH", actor, reason, now);
        } catch (SQLException exception) {
            throw failure("Unable to patch requirement graph entity", exception);
        }
    }

    private int count(String table, String snapshotId) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("select count(*) from " + table + " where snapshot_id=?")) {
            statement.setString(1, snapshotId);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? rows.getInt(1) : 0; }
        } catch (SQLException exception) { throw failure("Unable to count requirement graph rows", exception); }
    }

    private Map<String, float[]> embeddings(String table, String idColumn, String snapshotId) {
        Map<String, float[]> result = new LinkedHashMap<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("select " + idColumn + ",vector_json from " + table + " where snapshot_id=?")) {
            statement.setString(1, snapshotId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    try { result.put(rows.getString(1), objectMapper.readValue(rows.getString(2), float[].class)); }
                    catch (JsonProcessingException exception) { throw new IllegalStateException("需求语义图向量字段损坏", exception); }
                }
            }
            return Map.copyOf(result);
        } catch (SQLException exception) { throw failure("Unable to read requirement graph embeddings", exception); }
    }

    public synchronized void mergeClaim(String sourceClaimId, String targetClaimId, String actor, String reason) {
        if (sourceClaimId == null || targetClaimId == null || sourceClaimId.equals(targetClaimId)) {
            throw new RequirementGraphException("GRAPH_SCHEMA_INVALID", "合并声明参数无效");
        }
        boolean entity = sourceClaimId.startsWith("entity:");
        String sourceTable = entity ? "requirement_graph_entity" : "requirement_graph_relation";
        String sourceSnapshot = entity ? snapshotIdForEntity(sourceClaimId) : snapshotIdForRelation(sourceClaimId);
        String targetSnapshot = entity && targetClaimId.startsWith("entity:") ? snapshotIdForEntity(targetClaimId) : sourceSnapshot;
        if (!sourceSnapshot.equals(targetSnapshot)) throw new RequirementGraphException("GRAPH_SCHEMA_INVALID", "只能合并同一快照内的声明");
        String now = Instant.now().toString();
        try (Connection connection = open()) {
            ensureMutable(connection, sourceSnapshot);
            if (entity) {
                Entity source = requireEntity(sourceSnapshot, sourceClaimId);
                Entity target = requireEntity(sourceSnapshot, targetClaimId);
                List<String> aliases = union(target.aliases(), List.of(source.displayName()), source.aliases());
                List<String> evidence = union(target.sourceEvidenceIds(), source.sourceEvidenceIds());
                List<String> parents = union(target.sourceParentIds(), source.sourceParentIds());
                List<String> hashes = union(target.sourceContentHashes(), source.sourceContentHashes());
                try (PreparedStatement update = connection.prepareStatement("""
                        update requirement_graph_entity set aliases=?,source_evidence_ids=?,source_parent_ids=?,source_content_hashes=?,
                          claim_status=?,reviewer=?,reviewed_at=?,review_reason=? where id=?
                        """)) {
                    update.setString(1, json(aliases)); update.setString(2, json(evidence)); update.setString(3, json(parents));
                    update.setString(4, json(hashes)); update.setString(5, ClaimStatus.EXTRACTED.name()); update.setString(6, actor);
                    update.setString(7, now); update.setString(8, reason); update.setString(9, targetClaimId); update.executeUpdate();
                }
                try (PreparedStatement reject = connection.prepareStatement("update requirement_graph_entity set claim_status=?,reviewer=?,reviewed_at=?,review_reason=? where id=?")) {
                    reject.setString(1, ClaimStatus.REJECTED.name()); reject.setString(2, actor); reject.setString(3, now);
                    reject.setString(4, "MERGED_INTO:" + targetClaimId + (reason == null ? "" : " " + reason)); reject.setString(5, sourceClaimId); reject.executeUpdate();
                }
                try (PreparedStatement relations = connection.prepareStatement("update requirement_graph_relation set claim_status=?,review_reason=? where snapshot_id=? and (source_entity_id=? or target_entity_id=?)")) {
                    relations.setString(1, ClaimStatus.CONFLICTED.name()); relations.setString(2, "SOURCE_ENTITY_MERGED:" + targetClaimId);
                    relations.setString(3, sourceSnapshot); relations.setString(4, sourceClaimId); relations.setString(5, sourceClaimId); relations.executeUpdate();
                }
            } else {
                try (PreparedStatement reject = connection.prepareStatement("update requirement_graph_relation set claim_status=?,reviewer=?,reviewed_at=?,review_reason=? where id=?")) {
                    reject.setString(1, ClaimStatus.REJECTED.name()); reject.setString(2, actor); reject.setString(3, now);
                    reject.setString(4, "MERGED_INTO:" + targetClaimId + (reason == null ? "" : " " + reason)); reject.setString(5, sourceClaimId); reject.executeUpdate();
                }
            }
            audit(connection, sourceSnapshot, sourceClaimId, "MERGE", actor, reason == null ? targetClaimId : targetClaimId + " " + reason, now);
        } catch (SQLException exception) { throw failure("Unable to merge requirement graph claims", exception); }
    }

    public synchronized String splitEntity(String entityId, String newName, String actor, String reason) {
        if (newName == null || newName.isBlank()) throw new RequirementGraphException("GRAPH_SCHEMA_INVALID", "拆分实体名称不能为空");
        String snapshotId = snapshotIdForEntity(entityId);
        Entity source = requireEntity(snapshotId, entityId);
        String now = Instant.now().toString();
        String newId = "entity:split:" + java.util.UUID.randomUUID();
        try (Connection connection = open()) {
            ensureMutable(connection, snapshotId);
            Entity created = new Entity(newId, snapshotId, source.type(), canonical(newName), newName.trim(),
                    List.of(), source.description(), source.sourceEvidenceIds(), source.sourceParentIds(),
                    source.sourceContentHashes(), source.confidence(), EntityStatus.EXTRACTED, ClaimStatus.EXTRACTED,
                    actor, source.contextKey(), source.firstSeenWindowId(), source.lastSeenWindowId(), source.uncertaintyIds(),
                    source.conflictSetIds(), null, null, reason);
            insertEntities(connection, List.of(created));
            audit(connection, snapshotId, entityId, "SPLIT", actor, reason, now);
            audit(connection, snapshotId, newId, "SPLIT_CREATED", actor, reason, now);
            return newId;
        } catch (SQLException exception) { throw failure("Unable to split requirement graph entity", exception); }
    }

    public synchronized String splitRelation(String relationId, String statement, String newTargetEntityId,
                                              String newRelationType, String actor, String reason) {
        String snapshotId = snapshotIdForRelation(relationId);
        Relation source = requireRelation(snapshotId, relationId);
        String target = newTargetEntityId == null || newTargetEntityId.isBlank() ? source.targetEntityId() : newTargetEntityId;
        RelationType type = newRelationType == null || newRelationType.isBlank() ? source.type() : RelationType.valueOf(newRelationType);
        requireEntity(snapshotId, source.sourceEntityId());
        requireEntity(snapshotId, target);
        String now = Instant.now().toString();
        String newId = "relation:split:" + java.util.UUID.randomUUID();
        try (Connection connection = open()) {
            ensureMutable(connection, snapshotId);
            Relation created = new Relation(newId, snapshotId, source.sourceEntityId(), type, target,
                    statement == null || statement.isBlank() ? source.statement() : statement,
                    source.sourceEvidenceIds(), source.confidence(), RelationStatus.EXTRACTED, null, null,
                    ClaimStatus.EXTRACTED, source.condition(), source.scenario(), List.of(statement == null ? source.statement() : statement),
                    source.uncertaintyIds(), source.conflictSetIds(), reason);
            insertRelations(connection, List.of(created));
            audit(connection, snapshotId, relationId, "SPLIT", actor, reason, now);
            audit(connection, snapshotId, newId, "SPLIT_CREATED", actor, reason, now);
            return newId;
        } catch (SQLException exception) { throw failure("Unable to split requirement graph relation", exception); }
    }

    private List<String> union(List<String>... values) {
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        for (List<String> current : values) if (current != null) result.addAll(current);
        return List.copyOf(result);
    }

    private String canonical(String value) { return value == null ? "" : value.trim().replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT); }

    private void updateClaim(String kind, String claimId, ClaimStatus status, String reviewer, String reason) {
        if (status == null || reviewer == null || reviewer.isBlank()) throw new IllegalArgumentException("审核状态和审核人不能为空");
        String table = "entity".equals(kind) ? "requirement_graph_entity" : "requirement_graph_relation";
        String now = Instant.now().toString();
        try (Connection connection = open()) {
            String snapshotId = claimSnapshot(connection, table, claimId);
            ensureMutable(connection, snapshotId);
            try (PreparedStatement statement = connection.prepareStatement(
                    "update " + table + " set claim_status=?,reviewer=?,reviewed_at=?,review_reason=? where id=?")) {
                statement.setString(1, status.name()); statement.setString(2, reviewer.trim()); statement.setString(3, now);
                statement.setString(4, reason); statement.setString(5, claimId);
                if (statement.executeUpdate() != 1) throw new IllegalArgumentException("未知需求图声明: " + claimId);
            }
            audit(connection, snapshotId, claimId, status.name(), reviewer.trim(), reason, now);
        } catch (SQLException exception) { throw failure("Unable to review requirement graph claim", exception); }
    }

    private void ensureMutable(Connection connection, String snapshotId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select status from requirement_graph_snapshot where id=?")) {
            statement.setString(1, snapshotId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("未知需求图快照: " + snapshotId);
                if (SnapshotStatus.PUBLISHED.name().equals(rows.getString(1))) {
                    throw new RequirementGraphException("GRAPH_PUBLICATION_BLOCKED", "已发布需求图不可直接修改，请创建新草稿");
                }
            }
        }
    }

    private String claimSnapshot(Connection connection, String table, String claimId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("select snapshot_id from " + table + " where id=?")) {
            statement.setString(1, claimId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalArgumentException("未知需求图声明: " + claimId);
                return rows.getString(1);
            }
        }
    }

    private void audit(Connection connection, String snapshotId, String claimId, String action, String actor,
                       String reason, String now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into requirement_graph_audit(id,snapshot_id,claim_id,action,actor,reason,occurred_at) values(?,?,?,?,?,?,?)")) {
            statement.setString(1, "audit:" + java.util.UUID.randomUUID()); statement.setString(2, snapshotId);
            statement.setString(3, claimId); statement.setString(4, action); statement.setString(5,
                    actor == null || actor.isBlank() ? "system" : actor); statement.setString(6, reason); statement.setString(7, now);
            statement.executeUpdate();
        }
    }

    private void bindSnapshot(PreparedStatement statement, GraphSnapshot snapshot) throws SQLException {
        statement.setString(1, snapshot.id()); statement.setString(2, snapshot.businessProjectId()); statement.setString(3, snapshot.documentId());
        statement.setString(4, snapshot.requirementVersion()); statement.setString(5, snapshot.sourceRevision()); statement.setString(6, snapshot.extractionModel());
        statement.setString(7, snapshot.promptVersion()); statement.setString(8, snapshot.status().name()); statement.setInt(9, snapshot.entityCount());
        statement.setInt(10, snapshot.relationCount()); statement.setString(11, text(snapshot.createdAt())); statement.setString(12, text(snapshot.updatedAt()));
        statement.setString(13, text(snapshot.publishedAt())); statement.setInt(14, snapshot.schemaVersion()); statement.setString(15, snapshot.ontologyVersion());
        statement.setDouble(16, snapshot.coverageRatio()); statement.setInt(17, snapshot.windowCount()); statement.setInt(18, snapshot.succeededWindowCount());
        statement.setInt(19, snapshot.failedWindowCount()); statement.setInt(20, snapshot.warningCount()); statement.setString(21, snapshot.buildId());
        statement.setString(22, snapshot.publishedBy()); statement.setString(23, snapshot.publicationReason()); statement.setString(24, text(snapshot.staleAt()));
    }

    private void upsertSnapshot(Connection connection, GraphSnapshot snapshot) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into requirement_graph_snapshot(
                  id,business_project_id,document_id,requirement_version,source_revision,extraction_model,prompt_version,status,
                  entity_count,relation_count,created_at,updated_at,published_at,schema_version,ontology_version,coverage_ratio,
                  window_count,succeeded_window_count,failed_window_count,warning_count,build_id,published_by,publication_reason,stale_at)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                on conflict(id) do update set status=excluded.status,entity_count=excluded.entity_count,relation_count=excluded.relation_count,
                  updated_at=excluded.updated_at,published_at=excluded.published_at,schema_version=excluded.schema_version,
                  ontology_version=excluded.ontology_version,coverage_ratio=excluded.coverage_ratio,window_count=excluded.window_count,
                  succeeded_window_count=excluded.succeeded_window_count,failed_window_count=excluded.failed_window_count,
                  warning_count=excluded.warning_count,build_id=excluded.build_id,published_by=excluded.published_by,
                  publication_reason=excluded.publication_reason,stale_at=excluded.stale_at
                """)) { bindSnapshot(statement, snapshot); statement.executeUpdate(); }
    }

    private void bindWindow(PreparedStatement statement, String snapshotId, RequirementGraphWindowView window) throws SQLException {
        statement.setString(1, window.id()); statement.setString(2, snapshotId); statement.setString(3, window.filename()); statement.setString(4, window.parentId());
        statement.setString(5, window.sectionPath()); statement.setString(6, window.heading()); statement.setInt(7, window.windowIndex()); statement.setInt(8, window.startOffset());
        statement.setInt(9, window.endOffset()); statement.setString(10, window.contentHash()); statement.setString(11, window.status().name()); statement.setInt(12, window.attemptCount());
        statement.setString(13, window.lastErrorCode()); statement.setString(14, text(window.startedAt())); statement.setString(15, text(window.completedAt())); statement.setString(16, window.continuationOf());
    }

    private void insertEntities(Connection connection, List<Entity> entities) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into requirement_graph_entity(id,snapshot_id,type,canonical_name,display_name,aliases,description,source_evidence_ids,
                  source_parent_ids,source_content_hashes,confidence,status,claim_status,normalized_by,context_key,first_seen_window_id,last_seen_window_id,
                  uncertainty_ids,conflict_set_ids,reviewer,reviewed_at,review_reason) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            for (Entity entity : entities) {
                statement.setString(1, entity.id()); statement.setString(2, entity.snapshotId()); statement.setString(3, entity.type().name()); statement.setString(4, entity.canonicalName());
                statement.setString(5, entity.displayName()); statement.setString(6, json(entity.aliases())); statement.setString(7, entity.description()); statement.setString(8, json(entity.sourceEvidenceIds()));
                statement.setString(9, json(entity.sourceParentIds())); statement.setString(10, json(entity.sourceContentHashes())); statement.setDouble(11, entity.confidence()); statement.setString(12, entity.status().name());
                statement.setString(13, entity.claimStatus().name()); statement.setString(14, entity.normalizedBy()); statement.setString(15, entity.contextKey()); statement.setString(16, entity.firstSeenWindowId()); statement.setString(17, entity.lastSeenWindowId());
                statement.setString(18, json(entity.uncertaintyIds())); statement.setString(19, json(entity.conflictSetIds())); statement.setString(20, entity.reviewer()); statement.setString(21, text(entity.reviewedAt())); statement.setString(22, entity.reviewReason()); statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertRelations(Connection connection, List<Relation> relations) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into requirement_graph_relation(id,snapshot_id,source_entity_id,relation_type,target_entity_id,statement,source_evidence_ids,confidence,status,reviewer,reviewed_at,
                  claim_status,condition,scenario,statement_variants,uncertainty_ids,conflict_set_ids,review_reason) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            for (Relation relation : relations) {
                statement.setString(1, relation.id()); statement.setString(2, relation.snapshotId()); statement.setString(3, relation.sourceEntityId()); statement.setString(4, relation.type().name()); statement.setString(5, relation.targetEntityId());
                statement.setString(6, relation.statement()); statement.setString(7, json(relation.sourceEvidenceIds())); statement.setDouble(8, relation.confidence()); statement.setString(9, relation.status().name()); statement.setString(10, relation.reviewer()); statement.setString(11, text(relation.reviewedAt()));
                statement.setString(12, relation.claimStatus().name()); statement.setString(13, relation.condition()); statement.setString(14, relation.scenario()); statement.setString(15, json(relation.statementVariants())); statement.setString(16, json(relation.uncertaintyIds())); statement.setString(17, json(relation.conflictSetIds())); statement.setString(18, relation.reviewReason()); statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertClaimEvidence(Connection connection, String snapshotId, String claimId, List<String> evidenceIds,
                                     double confidence) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert or replace into requirement_graph_claim_evidence(
                  snapshot_id,claim_id,evidence_id,support_type,confidence,created_at)
                values(?,?,?,?,?,?)
                """)) {
            String createdAt = Instant.now().toString();
            for (String evidenceId : evidenceIds == null ? List.<String>of() : evidenceIds) {
                if (evidenceId == null || evidenceId.isBlank()) continue;
                statement.setString(1, snapshotId);
                statement.setString(2, claimId);
                statement.setString(3, evidenceId);
                statement.setString(4, "SUPPORTED");
                statement.setDouble(5, Math.max(0, Math.min(1, confidence)));
                statement.setString(6, createdAt);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void deleteClaimEvidence(Connection connection, String snapshotId, String claimId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "delete from requirement_graph_claim_evidence where snapshot_id=? and claim_id=?")) {
            statement.setString(1, snapshotId);
            statement.setString(2, claimId);
            statement.executeUpdate();
        }
    }

    private void deleteGraph(Connection connection, String snapshotId) throws SQLException {
        for (String table : List.of("requirement_graph_claim_evidence", "requirement_graph_audit", "requirement_graph_evidence", "requirement_graph_uncertainty", "requirement_graph_conflict", "requirement_graph_entity_embedding", "requirement_graph_relation_embedding", "requirement_graph_relation", "requirement_graph_entity")) {
            try (PreparedStatement statement = connection.prepareStatement("delete from " + table + " where snapshot_id=?")) { statement.setString(1, snapshotId); statement.executeUpdate(); }
        }
    }

    private List<GraphSnapshot> querySnapshots(String sql, List<String> values) { List<GraphSnapshot> result = new ArrayList<>(); try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) { bindValues(statement, values); try (ResultSet rows = statement.executeQuery()) { while (rows.next()) result.add(snapshot(rows)); } return List.copyOf(result); } catch (SQLException exception) { throw failure("Unable to list requirement graph snapshots", exception); } }

    private List<RequirementGraphWindowView> queryWindows(String snapshotId) { List<RequirementGraphWindowView> result = new ArrayList<>(); try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("select * from requirement_graph_window where snapshot_id=? order by window_index")) { statement.setString(1, snapshotId); try (ResultSet rows = statement.executeQuery()) { while (rows.next()) result.add(window(rows)); } return List.copyOf(result); } catch (SQLException exception) { throw failure("Unable to read requirement graph windows", exception); } }

    private List<Entity> queryEntities(String sql, List<String> values, int limit, int offset) { List<Entity> result = new ArrayList<>(); try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) { int index = bindValues(statement, values); statement.setInt(index++, limit); statement.setInt(index, offset); try (ResultSet rows = statement.executeQuery()) { while (rows.next()) result.add(entity(rows)); } return List.copyOf(result); } catch (SQLException exception) { throw failure("Unable to query requirement graph entities", exception); } }

    private List<Relation> queryRelations(String sql, List<String> values, int limit, int offset) { List<Relation> result = new ArrayList<>(); try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) { int index = bindValues(statement, values); statement.setInt(index++, limit); statement.setInt(index, offset); try (ResultSet rows = statement.executeQuery()) { while (rows.next()) result.add(relation(rows)); } return List.copyOf(result); } catch (SQLException exception) { throw failure("Unable to query requirement graph relations", exception); } }

    private int bindValues(PreparedStatement statement, List<String> values) throws SQLException { int index = 1; for (String value : values) statement.setString(index++, value); return index; }

    private GraphSnapshot snapshot(ResultSet row) throws SQLException { return new GraphSnapshot(row.getString("id"), row.getString("business_project_id"), row.getString("document_id"), row.getString("requirement_version"), row.getString("source_revision"), row.getString("extraction_model"), row.getString("prompt_version"), SnapshotStatus.valueOf(row.getString("status")), row.getInt("entity_count"), row.getInt("relation_count"), instant(row.getString("created_at")), instant(row.getString("updated_at")), instant(row.getString("published_at")), row.getInt("schema_version"), row.getString("ontology_version"), row.getDouble("coverage_ratio"), row.getInt("window_count"), row.getInt("succeeded_window_count"), row.getInt("failed_window_count"), row.getInt("warning_count"), row.getString("build_id"), row.getString("published_by"), row.getString("publication_reason"), instant(row.getString("stale_at"))); }

    private RequirementGraphWindowView window(ResultSet row) throws SQLException { return new RequirementGraphWindowView(row.getString("id"), row.getString("snapshot_id"), row.getString("filename"), row.getString("parent_id"), row.getString("section_path"), row.getString("heading"), row.getInt("window_index"), row.getInt("start_offset"), row.getInt("end_offset"), row.getString("content_hash"), WindowStatus.valueOf(row.getString("status")), row.getInt("attempt_count"), row.getString("last_error_code"), instant(row.getString("started_at")), instant(row.getString("completed_at")), row.getString("continuation_of")); }

    private Entity entity(ResultSet row) throws SQLException { return new Entity(row.getString("id"), row.getString("snapshot_id"), RequirementGraphModels.EntityType.valueOf(row.getString("type")), row.getString("canonical_name"), row.getString("display_name"), list(row.getString("aliases")), row.getString("description"), list(row.getString("source_evidence_ids")), list(row.getString("source_parent_ids")), list(row.getString("source_content_hashes")), row.getDouble("confidence"), EntityStatus.valueOf(row.getString("status")), ClaimStatus.valueOf(row.getString("claim_status")), row.getString("normalized_by"), row.getString("context_key"), row.getString("first_seen_window_id"), row.getString("last_seen_window_id"), list(row.getString("uncertainty_ids")), list(row.getString("conflict_set_ids")), row.getString("reviewer"), instant(row.getString("reviewed_at")), row.getString("review_reason")); }

    private Relation relation(ResultSet row) throws SQLException { return new Relation(row.getString("id"), row.getString("snapshot_id"), row.getString("source_entity_id"), RelationType.valueOf(row.getString("relation_type")), row.getString("target_entity_id"), row.getString("statement"), list(row.getString("source_evidence_ids")), row.getDouble("confidence"), RelationStatus.valueOf(row.getString("status")), row.getString("reviewer"), instant(row.getString("reviewed_at")), ClaimStatus.valueOf(row.getString("claim_status")), row.getString("condition"), row.getString("scenario"), list(row.getString("statement_variants")), list(row.getString("uncertainty_ids")), list(row.getString("conflict_set_ids")), row.getString("review_reason")); }

    private List<String> list(String value) { if (value == null || value.isBlank()) return List.of(); try { return objectMapper.readValue(value, STRINGS); } catch (JsonProcessingException exception) { throw new IllegalStateException("需求语义图 JSON 字段损坏", exception); } }
    private String json(List<String> values) { try { return objectMapper.writeValueAsString(values == null ? List.of() : values); } catch (JsonProcessingException exception) { throw new IllegalStateException("无法序列化需求语义图 JSON 字段", exception); } }
    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            // SQLite 外键是连接级设置：必须在每个业务连接上显式开启，
            // 否则 ON DELETE CASCADE 和引用完整性约束都不会真正执行。
            statement.execute("PRAGMA foreign_keys=ON");
        }
        return connection;
    }
    private String text(Instant value) { return value == null ? null : value.toString(); }
    private Instant instant(String value) { return value == null ? null : Instant.parse(value); }
    private void requireText(String value, String field) { if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " 不能为空"); }
    private IllegalStateException failure(String message, SQLException exception) { return new IllegalStateException(message, exception); }
}
