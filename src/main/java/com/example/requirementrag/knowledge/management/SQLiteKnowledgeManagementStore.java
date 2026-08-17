package com.example.requirementrag.knowledge.management;

import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.*;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;

/** SQLite 知识管理状态目录。正文与向量仍由 Qdrant 持有。 */
@Repository
@ConditionalOnProperty(prefix = "app.knowledge-management", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SQLiteKnowledgeManagementStore {
    private final String jdbcUrl;

    public SQLiteKnowledgeManagementStore(KnowledgeManagementProperties properties) {
        try {
            Path database = Path.of(properties.databasePath()).toAbsolutePath().normalize();
            if (database.getParent() != null) Files.createDirectories(database.getParent());
            jdbcUrl = "jdbc:sqlite:" + database;
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to initialize knowledge management directory", exception);
        }
    }

    @PostConstruct
    void initialize() {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    create table if not exists knowledge_base(
                      id text primary key, project_id text not null, name text not null, type text not null,
                      collection text not null unique, source_type text not null, status text not null,
                      published_revision text, target_revision text, last_published_at text,
                      created_at text not null, updated_at text not null, unique(project_id,type))
                    """);
            statement.executeUpdate("""
                    create table if not exists knowledge_ingestion_run(
                      id text primary key, knowledge_base_id text not null, trigger_type text not null,
                      status text not null, phase text, target_revision text, files_total integer not null default 0,
                      files_processed integer not null default 0, chunks_total integer not null default 0,
                      chunks_ready integer not null default 0, chunks_failed integer not null default 0,
                      current_file text, error_code text, error_message text, started_at text not null,
                      finished_at text, correlation_id text not null)
                    """);
            statement.executeUpdate("""
                    create table if not exists knowledge_document(
                      id text primary key, knowledge_base_id text not null, run_id text, source_path text not null,
                      source_hash text, revision text, status text not null, phase text,
                      chunk_count integer not null default 0, excluded_chunk_count integer not null default 0,
                      error_code text, error_message text, started_at text, finished_at text, updated_at text not null)
                    """);
            statement.executeUpdate("""
                    create table if not exists knowledge_chunk_status(
                      chunk_id text primary key, document_id text not null, run_id text, parent_id text,
                      parent_order integer not null, child_order integer not null, content_hash text,
                      status text not null, phase text, dense_ready integer not null default 0,
                      sparse_ready integer not null default 0, qdrant_verified integer not null default 0,
                      retry_count integer not null default 0, error_code text, error_message text, indexed_at text)
                    """);
            statement.executeUpdate("""
                    create table if not exists knowledge_stage_event(
                      id text primary key, run_id text not null, entity_type text not null, entity_id text not null,
                      stage text not null, status text not null, input_count integer not null default 0,
                      output_count integer not null default 0, excluded_count integer not null default 0,
                      error_code text, error_message text, occurred_at text not null)
                    """);
            statement.executeUpdate("create index if not exists idx_knowledge_base_project on knowledge_base(project_id,type)");
            statement.executeUpdate("create index if not exists idx_knowledge_run_base on knowledge_ingestion_run(knowledge_base_id,started_at desc)");
            statement.executeUpdate("create index if not exists idx_knowledge_document_base on knowledge_document(knowledge_base_id,updated_at desc)");
            statement.executeUpdate("create index if not exists idx_knowledge_chunk_document on knowledge_chunk_status(document_id,parent_order,child_order)");
            statement.executeUpdate("create index if not exists idx_knowledge_event_run on knowledge_stage_event(run_id,occurred_at)");
            recoverInterruptedWork(connection);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to initialize knowledge management store", exception);
        }
    }

    public KnowledgeBaseView ensureBase(String projectId, String name, BaseType type, String collection,
                                        SourceType sourceType, String targetRevision) {
        String id = projectId + ":" + type.name().toLowerCase();
        String now = Instant.now().toString();
        String sql = """
                insert into knowledge_base(id,project_id,name,type,collection,source_type,status,target_revision,created_at,updated_at)
                values(?,?,?,?,?,?,'IDLE',?,?,?)
                on conflict(id) do update set name=excluded.name,collection=excluded.collection,
                source_type=excluded.source_type,target_revision=excluded.target_revision,updated_at=excluded.updated_at
                """;
        execute(sql, statement -> {
            statement.setString(1, id); statement.setString(2, projectId); statement.setString(3, name);
            statement.setString(4, type.name()); statement.setString(5, collection);
            statement.setString(6, sourceType.name()); statement.setString(7, targetRevision);
            statement.setString(8, now); statement.setString(9, now);
        });
        return requireBase(id);
    }

    public RunView startRun(String knowledgeBaseId, TriggerType triggerType, String targetRevision) {
        String id = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        execute("""
                insert into knowledge_ingestion_run(id,knowledge_base_id,trigger_type,status,phase,target_revision,
                started_at,correlation_id) values(?,?,?,'RUNNING','DISCOVER',?,?,?)
                """, statement -> {
            statement.setString(1, id); statement.setString(2, knowledgeBaseId);
            statement.setString(3, triggerType.name()); statement.setString(4, targetRevision);
            statement.setString(5, now); statement.setString(6, id);
        });
        execute("update knowledge_base set status='RUNNING',target_revision=?,updated_at=? where id=?", statement -> {
            statement.setString(1, targetRevision); statement.setString(2, now); statement.setString(3, knowledgeBaseId);
        });
        return requireRun(knowledgeBaseId, id);
    }

    public void updateRun(String runId, Stage phase, int total, int processed, String currentFile) {
        execute("""
                update knowledge_ingestion_run set phase=?,files_total=?,files_processed=?,current_file=? where id=?
                """, statement -> {
            statement.setString(1, phase == null ? null : phase.name()); statement.setInt(2, total);
            statement.setInt(3, processed); statement.setString(4, currentFile); statement.setString(5, runId);
        });
    }

    public void updateRunChunks(String runId, Stage phase, int total, int ready, int failed) {
        execute("""
                update knowledge_ingestion_run set phase=?,chunks_total=?,chunks_ready=?,chunks_failed=? where id=?
                """, statement -> {
            statement.setString(1, phase == null ? null : phase.name());
            statement.setInt(2, Math.max(0, total));
            statement.setInt(3, Math.max(0, ready));
            statement.setInt(4, Math.max(0, failed));
            statement.setString(5, runId);
        });
    }

    public void finishRun(String knowledgeBaseId, String runId, String revision, int chunks) {
        String now = Instant.now().toString();
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement staleChunks = connection.prepareStatement("""
                        delete from knowledge_chunk_status
                        where document_id in (
                          select id from knowledge_document where knowledge_base_id=?
                        ) and (run_id is null or run_id<>?)
                        """);
                     PreparedStatement staleDocuments = connection.prepareStatement("""
                        delete from knowledge_document
                        where knowledge_base_id=? and (run_id is null or run_id<>?)
                        """);
                     PreparedStatement run = connection.prepareStatement("""
                        update knowledge_ingestion_run set status='READY',phase='PUBLISH',chunks_total=?,
                        chunks_ready=?,finished_at=?,current_file=null where id=? and knowledge_base_id=?
                        """);
                     PreparedStatement base = connection.prepareStatement("""
                        update knowledge_base set status='READY',published_revision=?,target_revision=?,
                        last_published_at=?,updated_at=? where id=?
                        """)) {
                    staleChunks.setString(1, knowledgeBaseId);
                    staleChunks.setString(2, runId);
                    staleChunks.executeUpdate();
                    staleDocuments.setString(1, knowledgeBaseId);
                    staleDocuments.setString(2, runId);
                    staleDocuments.executeUpdate();
                    run.setInt(1, chunks);
                    run.setInt(2, chunks);
                    run.setString(3, now);
                    run.setString(4, runId);
                    run.setString(5, knowledgeBaseId);
                    if (run.executeUpdate() != 1) {
                        throw new IllegalArgumentException("知识导入任务不存在");
                    }
                    base.setString(1, revision);
                    base.setString(2, revision);
                    base.setString(3, now);
                    base.setString(4, now);
                    base.setString(5, knowledgeBaseId);
                    if (base.executeUpdate() != 1) {
                        throw new IllegalArgumentException("知识库不存在");
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    public void failRun(String knowledgeBaseId, String runId, SafeError error) {
        String now = Instant.now().toString();
        execute("""
                update knowledge_ingestion_run set status='FAILED',error_code=?,error_message=?,finished_at=? where id=?
                """, statement -> {
            statement.setString(1, error == null ? null : error.code());
            statement.setString(2, error == null ? null : error.message());
            statement.setString(3, now); statement.setString(4, runId);
        });
        execute("update knowledge_base set status='FAILED',updated_at=? where id=?", statement -> {
            statement.setString(1, now); statement.setString(2, knowledgeBaseId);
        });
    }

    public void upsertDocument(DocumentView document) {
        execute("""
                insert into knowledge_document values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                on conflict(id) do update set run_id=excluded.run_id,source_hash=excluded.source_hash,
                revision=excluded.revision,status=excluded.status,phase=excluded.phase,
                chunk_count=excluded.chunk_count,excluded_chunk_count=excluded.excluded_chunk_count,
                error_code=excluded.error_code,error_message=excluded.error_message,
                started_at=coalesce(knowledge_document.started_at,excluded.started_at),
                finished_at=excluded.finished_at,updated_at=excluded.updated_at
                """, statement -> bindDocument(statement, document));
    }

    public void upsertChunks(List<ChunkView> chunks) {
        if (chunks == null || chunks.isEmpty()) return;
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                    insert into knowledge_chunk_status values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    on conflict(chunk_id) do update set run_id=excluded.run_id,status=excluded.status,
                    phase=excluded.phase,dense_ready=excluded.dense_ready,sparse_ready=excluded.sparse_ready,
                    qdrant_verified=excluded.qdrant_verified,error_code=excluded.error_code,
                    error_message=excluded.error_message,indexed_at=excluded.indexed_at
                    """)) {
                for (ChunkView chunk : chunks) {
                    bindChunk(statement, chunk);
                    statement.addBatch();
                }
                statement.executeBatch();
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw failure(exception);
        }
    }

    public void event(String runId, String entityType, String entityId, Stage stage, EventStatus status,
                      int input, int output, int excluded, SafeError error) {
        execute("insert into knowledge_stage_event values(?,?,?,?,?,?,?,?,?,?,?,?)", statement -> {
            statement.setString(1, UUID.randomUUID().toString()); statement.setString(2, runId);
            statement.setString(3, entityType); statement.setString(4, entityId);
            statement.setString(5, stage.name()); statement.setString(6, status.name());
            statement.setInt(7, input); statement.setInt(8, output); statement.setInt(9, excluded);
            statement.setString(10, error == null ? null : error.code());
            statement.setString(11, error == null ? null : error.message());
            statement.setString(12, Instant.now().toString());
        });
    }

    public Page<KnowledgeBaseView> listBases(String projectId, int page, int size) {
        return listBases(projectId, null, null, null, page, size);
    }

    public Page<KnowledgeBaseView> listBases(String projectId, SummaryStatus status, BaseType type,
                                             String query, int page, int size) {
        List<String> clauses = new ArrayList<>();
        List<String> values = new ArrayList<>();
        addEquals(clauses, values, "project_id", projectId);
        addEquals(clauses, values, "status", status == null ? null : status.name());
        addEquals(clauses, values, "type", type == null ? null : type.name());
        addSearch(clauses, values, query, "name", "project_id", "collection");
        return filteredPage("knowledge_base", clauses, values, "updated_at desc, id asc",
                page, size, this::base);
    }

    public Page<KnowledgeBaseView> listBasesForProjects(List<String> projectIds, int page, int size) {
        return listBasesForProjects(projectIds, null, null, null, page, size);
    }

    public Page<KnowledgeBaseView> listBasesForProjects(List<String> projectIds, SummaryStatus status,
                                                        BaseType type, String query, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = pageSize(size);
        List<String> ids = projectIds == null ? List.of() : projectIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return new Page<>(List.of(), safePage, safeSize, 0);
        }
        StringJoiner placeholders = new StringJoiner(",");
        ids.forEach(ignored -> placeholders.add("?"));
        List<String> clauses = new ArrayList<>();
        List<String> values = new ArrayList<>(ids);
        clauses.add("project_id in (" + placeholders + ")");
        addEquals(clauses, values, "status", status == null ? null : status.name());
        addEquals(clauses, values, "type", type == null ? null : type.name());
        addSearch(clauses, values, query, "name", "project_id", "collection");
        String where = " where " + String.join(" and ", clauses);
        long total = scalarValues("select count(*) from knowledge_base" + where, values);
        List<KnowledgeBaseView> items = query(
                "select * from knowledge_base" + where + " order by updated_at desc, id asc limit ? offset ?",
                statement -> {
                    int index = 1;
                    for (String value : values) statement.setString(index++, value);
                    statement.setInt(index++, safeSize);
                    statement.setInt(index, safePage * safeSize);
                }, this::base);
        return new Page<>(items, safePage, safeSize, total);
    }

    public KnowledgeBaseView requireBase(String id) {
        return one("select * from knowledge_base where id=?", id, this::base);
    }

    public Page<RunView> listRuns(String baseId, int page, int size) {
        return page("knowledge_ingestion_run", "knowledge_base_id", baseId,
                "started_at desc, id asc", page, size, this::run);
    }

    public RunView requireRun(String baseId, String runId) {
        return one("select * from knowledge_ingestion_run where knowledge_base_id=? and id=?",
                List.of(baseId, runId), this::run);
    }

    public Page<DocumentView> listDocuments(String baseId, int page, int size) {
        return listDocuments(baseId, null, null, null, page, size);
    }

    public Page<DocumentView> listDocuments(String baseId, EntityStatus status, Stage phase,
                                            String query, int page, int size) {
        List<String> clauses = new ArrayList<>();
        List<String> values = new ArrayList<>();
        addEquals(clauses, values, "knowledge_base_id", baseId);
        addEquals(clauses, values, "status", status == null ? null : status.name());
        addEquals(clauses, values, "phase", phase == null ? null : phase.name());
        addSearch(clauses, values, query, "source_path", "error_code");
        return filteredPage("knowledge_document", clauses, values, "updated_at desc, id asc",
                page, size, this::document);
    }

    public DocumentView requireDocument(String baseId, String documentId) {
        return one("select * from knowledge_document where knowledge_base_id=? and id=?",
                List.of(baseId, documentId), this::document);
    }

    public Page<ChunkView> listChunks(String documentId, int page, int size) {
        return listChunks(documentId, null, null, page, size);
    }

    public Page<ChunkView> listChunks(String documentId, EntityStatus status, String query,
                                     int page, int size) {
        List<String> clauses = new ArrayList<>();
        List<String> values = new ArrayList<>();
        addEquals(clauses, values, "document_id", documentId);
        addEquals(clauses, values, "status", status == null ? null : status.name());
        addSearch(clauses, values, query, "chunk_id", "parent_id", "content_hash", "error_code");
        return filteredPage("knowledge_chunk_status", clauses, values,
                "parent_order asc, child_order asc, chunk_id asc", page, size, this::chunk);
    }

    public ChunkView requireChunk(String documentId, String chunkId) {
        return one("select * from knowledge_chunk_status where document_id=? and chunk_id=?",
                List.of(documentId, chunkId), this::chunk);
    }

    public ChunkView requireChunkInBase(String baseId, String chunkId) {
        return one("""
                select c.* from knowledge_chunk_status c
                join knowledge_document d on d.id=c.document_id
                where d.knowledge_base_id=? and c.chunk_id=?
                """, List.of(baseId, chunkId), this::chunk);
    }

    public List<StageEventView> events(String runId) {
        return query("select * from knowledge_stage_event where run_id=? order by occurred_at, id", statement ->
                statement.setString(1, runId), this::stageEvent);
    }

    private void recoverInterruptedWork(Connection connection) throws SQLException {
        String now = Instant.now().toString();
        try (PreparedStatement runs = connection.prepareStatement("""
                update knowledge_ingestion_run set status='INTERRUPTED', finished_at=?,
                error_code='APPLICATION_RESTARTED', error_message='应用重启，中断的任务可重新执行'
                where status in ('PENDING','RUNNING','EMBEDDING','INDEXING')
                """);
             PreparedStatement documents = connection.prepareStatement("""
                update knowledge_document set status='INTERRUPTED', finished_at=?, updated_at=?,
                error_code='APPLICATION_RESTARTED', error_message='应用重启，中断的文档可重新执行'
                where status in ('PENDING','RUNNING','EMBEDDING','INDEXING')
                """);
             PreparedStatement bases = connection.prepareStatement("""
                update knowledge_base set
                  status=case when published_revision is null then 'FAILED' else 'STALE' end,
                  updated_at=?
                where status in ('QUEUED','RUNNING')
                """)) {
            runs.setString(1, now);
            runs.executeUpdate();
            documents.setString(1, now);
            documents.setString(2, now);
            documents.executeUpdate();
            bases.setString(1, now);
            bases.executeUpdate();
        }
    }

    private <T> Page<T> filteredPage(String table, List<String> clauses, List<String> values,
                                     String order, int page, int size, RowMapper<T> mapper) {
        int safePage = Math.max(0, page);
        int safeSize = pageSize(size);
        String where = clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses);
        long total = scalarValues("select count(*) from " + table + where, values);
        List<T> items = query("select * from " + table + where + " order by " + order + " limit ? offset ?",
                statement -> {
                    int index = 1;
                    for (String value : values) statement.setString(index++, value);
                    statement.setInt(index++, safeSize);
                    statement.setInt(index, safePage * safeSize);
                }, mapper);
        return new Page<>(items, safePage, safeSize, total);
    }

    private void addEquals(List<String> clauses, List<String> values, String column, String value) {
        if (value == null || value.isBlank()) return;
        clauses.add(column + "=?");
        values.add(value);
    }

    private void addSearch(List<String> clauses, List<String> values, String query, String... columns) {
        if (query == null || query.isBlank()) return;
        StringJoiner search = new StringJoiner(" or ", "(", ")");
        for (String column : columns) {
            search.add("lower(coalesce(" + column + ",'')) like ?");
            values.add("%" + query.trim().toLowerCase(java.util.Locale.ROOT) + "%");
        }
        clauses.add(search.toString());
    }

    private <T> Page<T> page(String table, String column, String value, String order, int page, int size,
                             RowMapper<T> mapper) {
        int safeSize = pageSize(size);
        long total = count("select count(*) from " + table + " where " + column + "=?", value);
        List<T> items = query("select * from " + table + " where " + column + "=? order by " + order
                        + " limit ? offset ?", statement -> {
            statement.setString(1, value); statement.setInt(2, safeSize);
            statement.setInt(3, Math.max(0, page) * safeSize);
        }, mapper);
        return new Page<>(items, Math.max(0, page), safeSize, total);
    }

    private int pageSize(int size) { return Math.max(1, Math.min(size <= 0 ? 50 : size, 200)); }

    private KnowledgeBaseView base(ResultSet row) throws SQLException {
        String id = row.getString("id");
        long documents = scalar("select count(*) from knowledge_document where knowledge_base_id=?", id);
        long ready = scalar("select count(*) from knowledge_document where knowledge_base_id=? and status='READY'", id);
        long failed = scalar("select count(*) from knowledge_document where knowledge_base_id=? and status='FAILED'", id);
        long chunks = scalar("""
                select count(*) from knowledge_chunk_status c join knowledge_document d on d.id=c.document_id
                where d.knowledge_base_id=? and c.status='READY'
                """, id);
        return new KnowledgeBaseView(id, row.getString("project_id"), row.getString("name"),
                BaseType.valueOf(row.getString("type")), row.getString("collection"),
                SourceType.valueOf(row.getString("source_type")), SummaryStatus.valueOf(row.getString("status")),
                row.getString("published_revision"), row.getString("target_revision"), documents, ready, failed, chunks,
                instant(row.getString("last_published_at")), instant(row.getString("created_at")),
                instant(row.getString("updated_at")));
    }

    private RunView run(ResultSet row) throws SQLException {
        return new RunView(row.getString("id"), row.getString("knowledge_base_id"),
                TriggerType.valueOf(row.getString("trigger_type")), EntityStatus.valueOf(row.getString("status")),
                stage(row.getString("phase")), row.getString("target_revision"), row.getInt("files_total"),
                row.getInt("files_processed"), row.getInt("chunks_total"), row.getInt("chunks_ready"),
                row.getInt("chunks_failed"), row.getString("current_file"), error(row),
                instant(row.getString("started_at")), instant(row.getString("finished_at")),
                row.getString("correlation_id"));
    }

    private DocumentView document(ResultSet row) throws SQLException {
        return new DocumentView(row.getString("id"), row.getString("knowledge_base_id"), row.getString("run_id"),
                row.getString("source_path"), row.getString("source_hash"), row.getString("revision"),
                EntityStatus.valueOf(row.getString("status")), stage(row.getString("phase")),
                row.getInt("chunk_count"), row.getInt("excluded_chunk_count"), error(row),
                instant(row.getString("started_at")), instant(row.getString("finished_at")),
                instant(row.getString("updated_at")));
    }

    private ChunkView chunk(ResultSet row) throws SQLException {
        return new ChunkView(row.getString("chunk_id"), row.getString("document_id"), row.getString("run_id"),
                row.getString("parent_id"), row.getInt("parent_order"), row.getInt("child_order"),
                row.getString("content_hash"), EntityStatus.valueOf(row.getString("status")),
                stage(row.getString("phase")), row.getInt("dense_ready") == 1, row.getInt("sparse_ready") == 1,
                row.getInt("qdrant_verified") == 1, row.getInt("retry_count"), error(row),
                instant(row.getString("indexed_at")));
    }

    private StageEventView stageEvent(ResultSet row) throws SQLException {
        return new StageEventView(row.getString("id"), row.getString("run_id"), row.getString("entity_type"),
                row.getString("entity_id"), Stage.valueOf(row.getString("stage")),
                EventStatus.valueOf(row.getString("status")), row.getInt("input_count"),
                row.getInt("output_count"), row.getInt("excluded_count"), error(row),
                instant(row.getString("occurred_at")));
    }

    private void bindDocument(PreparedStatement s, DocumentView d) throws SQLException {
        s.setString(1, d.id()); s.setString(2, d.knowledgeBaseId()); s.setString(3, d.runId());
        s.setString(4, d.sourcePath()); s.setString(5, d.sourceHash()); s.setString(6, d.revision());
        s.setString(7, d.status().name()); s.setString(8, d.phase() == null ? null : d.phase().name());
        s.setInt(9, d.chunkCount()); s.setInt(10, d.excludedChunkCount());
        s.setString(11, d.error() == null ? null : d.error().code());
        s.setString(12, d.error() == null ? null : d.error().message());
        s.setString(13, text(d.startedAt())); s.setString(14, text(d.finishedAt()));
        s.setString(15, text(d.updatedAt() == null ? Instant.now() : d.updatedAt()));
    }

    private void bindChunk(PreparedStatement s, ChunkView c) throws SQLException {
        s.setString(1, c.chunkId()); s.setString(2, c.documentId()); s.setString(3, c.runId());
        s.setString(4, c.parentId()); s.setInt(5, c.parentOrder()); s.setInt(6, c.childOrder());
        s.setString(7, c.contentHash()); s.setString(8, c.status().name());
        s.setString(9, c.phase() == null ? null : c.phase().name()); s.setInt(10, c.denseReady() ? 1 : 0);
        s.setInt(11, c.sparseReady() ? 1 : 0); s.setInt(12, c.qdrantVerified() ? 1 : 0);
        s.setInt(13, c.retryCount()); s.setString(14, c.error() == null ? null : c.error().code());
        s.setString(15, c.error() == null ? null : c.error().message()); s.setString(16, text(c.indexedAt()));
    }

    private SafeError error(ResultSet row) throws SQLException {
        String code = row.getString("error_code");
        return code == null ? null : new SafeError(code, row.getString("error_message"));
    }
    private Stage stage(String value) { return value == null ? null : Stage.valueOf(value); }
    private Instant instant(String value) { return value == null ? null : Instant.parse(value); }
    private String text(Instant value) { return value == null ? null : value.toString(); }
    private Connection open() throws SQLException { return DriverManager.getConnection(jdbcUrl); }

    private void execute(String sql, Binder binder) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement); statement.executeUpdate();
        } catch (SQLException exception) { throw failure(exception); }
    }

    private long count(String sql, String value) {
        return value == null || value.isBlank() ? scalar(sql) : scalar(sql, value);
    }
    private long scalar(String sql, String... values) {
        return scalarValues(sql, List.of(values));
    }
    private long scalarValues(String sql, List<String> values) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.size(); i++) statement.setString(i + 1, values.get(i));
            try (ResultSet result = statement.executeQuery()) { return result.next() ? result.getLong(1) : 0; }
        } catch (SQLException exception) { throw failure(exception); }
    }
    private <T> T one(String sql, String value, RowMapper<T> mapper) {
        return one(sql, List.of(value), mapper);
    }
    private <T> T one(String sql, List<String> values, RowMapper<T> mapper) {
        List<T> rows = query(sql, statement -> {
            for (int i = 0; i < values.size(); i++) statement.setString(i + 1, values.get(i));
        }, mapper);
        if (rows.isEmpty()) throw new IllegalArgumentException("知识管理资源不存在");
        return rows.getFirst();
    }
    private <T> List<T> query(String sql, Binder binder, RowMapper<T> mapper) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet result = statement.executeQuery()) {
                List<T> values = new ArrayList<>();
                while (result.next()) values.add(mapper.map(result));
                return values;
            }
        } catch (SQLException exception) { throw failure(exception); }
    }
    private IllegalStateException failure(SQLException exception) {
        return new IllegalStateException("Unable to access knowledge management store", exception);
    }
    @FunctionalInterface private interface Binder { void bind(PreparedStatement statement) throws SQLException; }
    @FunctionalInterface private interface RowMapper<T> { T map(ResultSet result) throws SQLException; }
}
