package com.example.requirementrag.requirement.graph;

import com.example.requirementrag.requirement.graph.RequirementGraphModels.Entity;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.EntityStatus;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.GraphSnapshot;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.Relation;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.RelationStatus;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.RelationType;
import com.example.requirementrag.requirement.graph.RequirementGraphModels.SnapshotStatus;
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
import java.util.List;
import java.util.Set;

/** 需求语义图持久化。与代码符号图分库，图关系只保存派生事实和需求证据引用。 */
@Repository
@ConditionalOnProperty(prefix = "app.rag.requirement-graph", name = "enabled",
        havingValue = "true", matchIfMissing = false)
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

    /** 供应用启动与测试显式初始化；DDL 全部幂等。 */
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
                      unique(business_project_id,document_id,requirement_version,source_revision,prompt_version)
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
                      foreign key(snapshot_id) references requirement_graph_snapshot(id) on delete cascade,
                      unique(snapshot_id,type,canonical_name)
                    )
                    """);
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
                      foreign key(snapshot_id) references requirement_graph_snapshot(id) on delete cascade,
                      foreign key(source_entity_id) references requirement_graph_entity(id) on delete cascade,
                      foreign key(target_entity_id) references requirement_graph_entity(id) on delete cascade,
                      unique(snapshot_id,source_entity_id,relation_type,target_entity_id)
                    )
                    """);
            statement.executeUpdate("create index if not exists idx_req_graph_snapshot_scope on requirement_graph_snapshot(business_project_id,document_id,requirement_version,status)");
            statement.executeUpdate("create index if not exists idx_req_graph_entity_snapshot on requirement_graph_entity(snapshot_id,type,canonical_name)");
            statement.executeUpdate("create index if not exists idx_req_graph_relation_source on requirement_graph_relation(snapshot_id,source_entity_id,relation_type)");
            statement.executeUpdate("create index if not exists idx_req_graph_relation_target on requirement_graph_relation(snapshot_id,target_entity_id,relation_type)");
        } catch (SQLException exception) {
            throw failure("Unable to initialize requirement graph store", exception);
        }
    }

    public synchronized void saveSnapshot(GraphSnapshot snapshot) {
        requireText(snapshot == null ? null : snapshot.id(), "snapshot id");
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                insert into requirement_graph_snapshot(
                  id,business_project_id,document_id,requirement_version,source_revision,
                  extraction_model,prompt_version,status,entity_count,relation_count,
                  created_at,updated_at,published_at)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?)
                on conflict(id) do update set
                  extraction_model=excluded.extraction_model,
                  prompt_version=excluded.prompt_version,
                  status=excluded.status,
                  entity_count=excluded.entity_count,
                  relation_count=excluded.relation_count,
                  updated_at=excluded.updated_at,
                  published_at=excluded.published_at
                """)) {
            bindSnapshot(statement, snapshot);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw failure("Unable to save requirement graph snapshot", exception);
        }
    }

    /** 原子替换一个草稿快照的实体和关系，失败时旧快照保持可读。 */
    public synchronized void replaceDraft(GraphSnapshot snapshot, List<Entity> entities, List<Relation> relations) {
        requireText(snapshot == null ? null : snapshot.id(), "snapshot id");
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                deleteGraph(connection, snapshot.id());
                GraphSnapshot persisted = new GraphSnapshot(snapshot.id(), snapshot.businessProjectId(),
                        snapshot.documentId(), snapshot.requirementVersion(), snapshot.sourceRevision(),
                        snapshot.extractionModel(), snapshot.promptVersion(), snapshot.status(),
                        entities == null ? 0 : entities.size(), relations == null ? 0 : relations.size(),
                        snapshot.createdAt(), Instant.now(), snapshot.publishedAt());
                upsertSnapshot(connection, persisted);
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

    public synchronized void updateStatus(String snapshotId, SnapshotStatus status, String reviewer) {
        requireText(snapshotId, "snapshot id");
        String now = Instant.now().toString();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                update requirement_graph_snapshot set status=?,updated_at=?,published_at=? where id=?
                """)) {
            statement.setString(1, status.name());
            statement.setString(2, now);
            statement.setString(3, status == SnapshotStatus.PUBLISHED ? now : null);
            statement.setString(4, snapshotId);
            if (statement.executeUpdate() != 1) throw new IllegalArgumentException("未知需求图快照: " + snapshotId);
        } catch (SQLException exception) {
            throw failure("Unable to update requirement graph snapshot", exception);
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

    public synchronized java.util.Optional<GraphSnapshot> findLatest(String projectId, String documentId,
                                                                       String version) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                select * from requirement_graph_snapshot
                where business_project_id=? and document_id=? and requirement_version=?
                  and status in ('PUBLISHED','VERIFIED','REVIEW_REQUIRED','DRAFT')
                order by case status when 'PUBLISHED' then 0 when 'VERIFIED' then 1
                         when 'REVIEW_REQUIRED' then 2 when 'DRAFT' then 3 else 4 end,
                         updated_at desc limit 1
                """)) {
            statement.setString(1, projectId);
            statement.setString(2, documentId);
            statement.setString(3, version);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? java.util.Optional.of(snapshot(result)) : java.util.Optional.empty();
            }
        } catch (SQLException exception) {
            throw failure("Unable to find requirement graph snapshot", exception);
        }
    }

    public synchronized List<GraphSnapshot> listSnapshots(String projectId, String documentId, String version) {
        StringBuilder sql = new StringBuilder("select * from requirement_graph_snapshot where business_project_id=?");
        List<String> values = new ArrayList<>();
        values.add(projectId);
        if (documentId != null && !documentId.isBlank()) {
            sql.append(" and document_id=?");
            values.add(documentId);
        }
        if (version != null && !version.isBlank()) {
            sql.append(" and requirement_version=?");
            values.add(version);
        }
        sql.append(" order by updated_at desc, id asc");
        return querySnapshots(sql.toString(), values);
    }

    public synchronized List<Entity> entities(String snapshotId, String query, String type, int limit) {
        StringBuilder sql = new StringBuilder("select * from requirement_graph_entity where snapshot_id=?");
        List<String> values = new ArrayList<>();
        values.add(snapshotId);
        if (type != null && !type.isBlank()) {
            sql.append(" and type=?");
            values.add(type);
        }
        if (query != null && !query.isBlank()) {
            sql.append(" and (lower(canonical_name) like ? or lower(display_name) like ? or lower(aliases) like ?)");
            String term = "%" + query.trim().toLowerCase(java.util.Locale.ROOT) + "%";
            values.add(term);
            values.add(term);
            values.add(term);
        }
        sql.append(" order by confidence desc, canonical_name asc limit ?");
        return queryEntities(sql.toString(), values, Math.max(1, Math.min(limit, 200)));
    }

    public synchronized List<Entity> allEntities(String snapshotId, int limit) {
        return entities(snapshotId, null, null, limit);
    }

    public synchronized List<Relation> allRelations(String snapshotId, int limit) {
        return queryRelations("select * from requirement_graph_relation where snapshot_id="
                + "? order by confidence desc, id asc limit ?", List.of(snapshotId),
                Math.max(1, Math.min(limit, 10_000)));
    }

    private void bindSnapshot(PreparedStatement statement, GraphSnapshot snapshot) throws SQLException {
        statement.setString(1, snapshot.id());
        statement.setString(2, snapshot.businessProjectId());
        statement.setString(3, snapshot.documentId());
        statement.setString(4, snapshot.requirementVersion());
        statement.setString(5, snapshot.sourceRevision());
        statement.setString(6, snapshot.extractionModel());
        statement.setString(7, snapshot.promptVersion());
        statement.setString(8, snapshot.status().name());
        statement.setInt(9, snapshot.entityCount());
        statement.setInt(10, snapshot.relationCount());
        statement.setString(11, text(snapshot.createdAt()));
        statement.setString(12, text(snapshot.updatedAt()));
        statement.setString(13, text(snapshot.publishedAt()));
    }

    private void upsertSnapshot(Connection connection, GraphSnapshot snapshot) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into requirement_graph_snapshot(
                  id,business_project_id,document_id,requirement_version,source_revision,
                  extraction_model,prompt_version,status,entity_count,relation_count,
                  created_at,updated_at,published_at)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?)
                on conflict(id) do update set
                  extraction_model=excluded.extraction_model,status=excluded.status,
                  entity_count=excluded.entity_count,relation_count=excluded.relation_count,
                  updated_at=excluded.updated_at,published_at=excluded.published_at
                """)) {
            bindSnapshot(statement, snapshot);
            statement.executeUpdate();
        }
    }

    private void insertEntities(Connection connection, List<Entity> entities) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into requirement_graph_entity(
                  id,snapshot_id,type,canonical_name,display_name,aliases,description,
                  source_evidence_ids,source_parent_ids,source_content_hashes,confidence,status)
                values(?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            for (Entity entity : entities) {
                statement.setString(1, entity.id());
                statement.setString(2, entity.snapshotId());
                statement.setString(3, entity.type().name());
                statement.setString(4, entity.canonicalName());
                statement.setString(5, entity.displayName());
                statement.setString(6, json(entity.aliases()));
                statement.setString(7, entity.description());
                statement.setString(8, json(entity.sourceEvidenceIds()));
                statement.setString(9, json(entity.sourceParentIds()));
                statement.setString(10, json(entity.sourceContentHashes()));
                statement.setDouble(11, entity.confidence());
                statement.setString(12, entity.status().name());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertRelations(Connection connection, List<Relation> relations) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into requirement_graph_relation(
                  id,snapshot_id,source_entity_id,relation_type,target_entity_id,statement,
                  source_evidence_ids,confidence,status,reviewer,reviewed_at)
                values(?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            for (Relation relation : relations) {
                statement.setString(1, relation.id());
                statement.setString(2, relation.snapshotId());
                statement.setString(3, relation.sourceEntityId());
                statement.setString(4, relation.type().name());
                statement.setString(5, relation.targetEntityId());
                statement.setString(6, relation.statement());
                statement.setString(7, json(relation.sourceEvidenceIds()));
                statement.setDouble(8, relation.confidence());
                statement.setString(9, relation.status().name());
                statement.setString(10, relation.reviewer());
                statement.setString(11, text(relation.reviewedAt()));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void deleteGraph(Connection connection, String snapshotId) throws SQLException {
        try (PreparedStatement relations = connection.prepareStatement("delete from requirement_graph_relation where snapshot_id=?");
             PreparedStatement entities = connection.prepareStatement("delete from requirement_graph_entity where snapshot_id=?")) {
            relations.setString(1, snapshotId);
            relations.executeUpdate();
            entities.setString(1, snapshotId);
            entities.executeUpdate();
        }
    }

    private List<GraphSnapshot> querySnapshots(String sql, List<String> values) {
        List<GraphSnapshot> result = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bindValues(statement, values);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(snapshot(rows));
            }
            return List.copyOf(result);
        } catch (SQLException exception) {
            throw failure("Unable to list requirement graph snapshots", exception);
        }
    }

    private List<Entity> queryEntities(String sql, List<String> values, int limit) {
        List<Entity> result = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = bindValues(statement, values);
            statement.setInt(index, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(entity(rows));
            }
            return List.copyOf(result);
        } catch (SQLException exception) {
            throw failure("Unable to query requirement graph entities", exception);
        }
    }

    private List<Relation> queryRelations(String sql, List<String> values, int limit) {
        List<Relation> result = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = bindValues(statement, values);
            statement.setInt(index, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) result.add(relation(rows));
            }
            return List.copyOf(result);
        } catch (SQLException exception) {
            throw failure("Unable to query requirement graph relations", exception);
        }
    }

    private int bindValues(PreparedStatement statement, List<String> values) throws SQLException {
        int index = 1;
        for (String value : values) statement.setString(index++, value);
        return index;
    }

    private GraphSnapshot snapshot(ResultSet row) throws SQLException {
        return new GraphSnapshot(row.getString("id"), row.getString("business_project_id"),
                row.getString("document_id"), row.getString("requirement_version"),
                row.getString("source_revision"), row.getString("extraction_model"),
                row.getString("prompt_version"), SnapshotStatus.valueOf(row.getString("status")),
                row.getInt("entity_count"), row.getInt("relation_count"),
                instant(row.getString("created_at")), instant(row.getString("updated_at")),
                instant(row.getString("published_at")));
    }

    private Entity entity(ResultSet row) throws SQLException {
        return new Entity(row.getString("id"), row.getString("snapshot_id"),
                RequirementGraphModels.EntityType.valueOf(row.getString("type")),
                row.getString("canonical_name"), row.getString("display_name"),
                list(row.getString("aliases")), row.getString("description"),
                list(row.getString("source_evidence_ids")), list(row.getString("source_parent_ids")),
                list(row.getString("source_content_hashes")), row.getDouble("confidence"),
                EntityStatus.valueOf(row.getString("status")));
    }

    private Relation relation(ResultSet row) throws SQLException {
        return new Relation(row.getString("id"), row.getString("snapshot_id"),
                row.getString("source_entity_id"), RelationType.valueOf(row.getString("relation_type")),
                row.getString("target_entity_id"), row.getString("statement"),
                list(row.getString("source_evidence_ids")), row.getDouble("confidence"),
                RelationStatus.valueOf(row.getString("status")), row.getString("reviewer"),
                instant(row.getString("reviewed_at")));
    }

    private List<String> list(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return objectMapper.readValue(value, STRINGS);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("需求语义图 JSON 字段损坏", exception);
        }
    }

    private String json(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化需求语义图 JSON 字段", exception);
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    private String text(Instant value) {
        return value == null ? null : value.toString();
    }

    private Instant instant(String value) {
        return value == null ? null : Instant.parse(value);
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " 不能为空");
    }

    private IllegalStateException failure(String message, SQLException exception) {
        return new IllegalStateException(message, exception);
    }
}
