package com.example.requirementrag.integration.gitlab;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

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
import java.util.UUID;

/** GitLab 托管项目与 Webhook 幂等事件的独立 SQLite 存储。 */
@Repository
@ConditionalOnProperty(name = "app.rag.gitlab.enabled", havingValue = "true")
public class GitLabProjectStore {

    private final String jdbcUrl;

    public GitLabProjectStore(GitLabIntegrationProperties properties) {
        Path database = Path.of(properties.databasePath()).toAbsolutePath().normalize();
        try {
            if (database.getParent() != null) {
                Files.createDirectories(database.getParent());
            }
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("无法创建 GitLab 接入数据库目录", exception);
        }
        this.jdbcUrl = "jdbc:sqlite:" + database;
        initialize();
    }

    public synchronized void save(GitLabManagedProject project) {
        String sql = """
                INSERT INTO gitlab_managed_project (
                    project_id, name, group_name, side, clone_url, branch_name, git_path,
                    requirement_collection, code_collection, repository_path, access_token_ciphertext,
                    webhook_secret_ciphertext, status, last_indexed_sha, target_sha, last_error,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(project_id) DO UPDATE SET
                    name=excluded.name, group_name=excluded.group_name, side=excluded.side,
                    clone_url=excluded.clone_url, branch_name=excluded.branch_name, git_path=excluded.git_path,
                    requirement_collection=excluded.requirement_collection,
                    code_collection=excluded.code_collection, repository_path=excluded.repository_path,
                    access_token_ciphertext=excluded.access_token_ciphertext,
                    webhook_secret_ciphertext=excluded.webhook_secret_ciphertext,
                    status=excluded.status, last_indexed_sha=excluded.last_indexed_sha,
                    target_sha=excluded.target_sha, last_error=excluded.last_error,
                    updated_at=excluded.updated_at
                """;
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, project);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("保存 GitLab 项目失败", exception);
        }
    }

    public synchronized Optional<GitLabManagedProject> find(String projectId) {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM gitlab_managed_project WHERE project_id=?")) {
            statement.setString(1, projectId);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(read(results)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("读取 GitLab 项目失败", exception);
        }
    }

    public synchronized List<GitLabManagedProject> all() {
        List<GitLabManagedProject> projects = new ArrayList<>();
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery(
                     "SELECT * FROM gitlab_managed_project ORDER BY created_at, project_id")) {
            while (results.next()) {
                projects.add(read(results));
            }
            return List.copyOf(projects);
        } catch (SQLException exception) {
            throw new IllegalStateException("读取 GitLab 项目列表失败", exception);
        }
    }

    public synchronized void updateState(String projectId, GitLabProjectStatus status,
                                         String lastIndexedSha, String targetSha, String lastError) {
        if (!updateState(projectId, status, lastIndexedSha, targetSha, lastError, false)) {
            throw new IllegalArgumentException("未知 GitLab 项目: " + projectId);
        }
    }

    public synchronized boolean updateStateIfEnabled(String projectId, GitLabProjectStatus status,
                                                     String lastIndexedSha, String targetSha,
                                                     String lastError) {
        return updateState(projectId, status, lastIndexedSha, targetSha, lastError, true);
    }

    public synchronized void updateStateKeepingTarget(String projectId, GitLabProjectStatus status,
                                                      String lastIndexedSha, String lastError) {
        if (!updateState(projectId, status, lastIndexedSha, null, lastError, false, true)) {
            throw new IllegalArgumentException("未知 GitLab 项目: " + projectId);
        }
    }

    public synchronized boolean updateStateIfEnabledKeepingTarget(String projectId,
                                                                  GitLabProjectStatus status,
                                                                  String lastIndexedSha,
                                                                  String lastError) {
        return updateState(projectId, status, lastIndexedSha, null, lastError, true, true);
    }

    private boolean updateState(String projectId, GitLabProjectStatus status,
                                String lastIndexedSha, String targetSha, String lastError,
                                boolean requireEnabled) {
        return updateState(projectId, status, lastIndexedSha, targetSha, lastError,
                requireEnabled, false);
    }

    private boolean updateState(String projectId, GitLabProjectStatus status,
                                String lastIndexedSha, String targetSha, String lastError,
                                boolean requireEnabled, boolean keepTargetSha) {
        String sql = """
                UPDATE gitlab_managed_project
                SET status=?, last_indexed_sha=COALESCE(?, last_indexed_sha),
                    target_sha=CASE WHEN ?=1 THEN target_sha ELSE ? END,
                    last_error=?, updated_at=?
                WHERE project_id=?
                """ + (requireEnabled ? " AND status <> 'DISABLED'" : "");
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status.name());
            statement.setString(2, lastIndexedSha);
            statement.setInt(3, keepTargetSha ? 1 : 0);
            statement.setString(4, targetSha);
            statement.setString(5, lastError);
            statement.setString(6, Instant.now().toString());
            statement.setString(7, projectId);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw new IllegalStateException("更新 GitLab 项目状态失败", exception);
        }
    }

    public synchronized boolean recordWebhookEvent(String projectId, String eventId) {
        String sql = "INSERT OR IGNORE INTO gitlab_webhook_event(project_id, event_id, received_at) VALUES(?,?,?)";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, projectId);
            statement.setString(2, eventId);
            statement.setString(3, Instant.now().toString());
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw new IllegalStateException("保存 GitLab Webhook 事件失败", exception);
        }
    }

    public synchronized String createJob(String projectId, String triggerType, String sourceSha,
                                         String targetSha) {
        String id = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        String sql = """
                INSERT INTO gitlab_sync_job(
                    id, project_id, trigger_type, status, phase, source_sha, target_sha,
                    correlation_id, started_at
                ) VALUES(?,?,?,?,?,?,?,?,?)
                """;
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            statement.setString(2, projectId);
            statement.setString(3, triggerType);
            statement.setString(4, "QUEUED");
            statement.setString(5, "QUEUED");
            statement.setString(6, sourceSha);
            statement.setString(7, targetSha);
            statement.setString(8, UUID.randomUUID().toString());
            statement.setString(9, now);
            statement.executeUpdate();
            appendJobEvent(connection, id, "QUEUED", "QUEUED", "同步任务已进入队列");
            return id;
        } catch (SQLException exception) {
            throw new IllegalStateException("创建 GitLab 同步任务失败", exception);
        }
    }

    public synchronized void updateJob(String jobId, String status, String phase, String targetSha,
                                       String errorCode, String errorMessage, boolean finished) {
        String sql = """
                UPDATE gitlab_sync_job
                SET status=?, phase=?, target_sha=COALESCE(?, target_sha),
                    error_code=?, error_message=?, finished_at=CASE WHEN ?=1 THEN ? ELSE finished_at END
                WHERE id=?
                """;
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, status);
                    statement.setString(2, phase);
                    statement.setString(3, targetSha);
                    statement.setString(4, errorCode);
                    statement.setString(5, errorMessage);
                    statement.setInt(6, finished ? 1 : 0);
                    statement.setString(7, Instant.now().toString());
                    statement.setString(8, jobId);
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalArgumentException("未知 GitLab 同步任务: " + jobId);
                    }
                }
                appendJobEvent(connection, jobId, phase, status, errorMessage);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("更新 GitLab 同步任务失败", exception);
        }
    }

    public synchronized List<GitLabSyncJob> jobs(String projectId) {
        List<GitLabSyncJob> jobs = new ArrayList<>();
        String sql = "SELECT * FROM gitlab_sync_job WHERE project_id=? ORDER BY started_at DESC, id";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, projectId);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    jobs.add(readJob(connection, results, false));
                }
            }
            return List.copyOf(jobs);
        } catch (SQLException exception) {
            throw new IllegalStateException("读取 GitLab 同步任务失败", exception);
        }
    }

    public synchronized Optional<GitLabSyncJob> findJob(String projectId, String jobId) {
        String sql = "SELECT * FROM gitlab_sync_job WHERE project_id=? AND id=?";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, projectId);
            statement.setString(2, jobId);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(readJob(connection, results, true)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("读取 GitLab 同步任务详情失败", exception);
        }
    }

    public synchronized Optional<GitLabSyncJob> latestJob(String projectId) {
        String sql = "SELECT * FROM gitlab_sync_job WHERE project_id=? ORDER BY started_at DESC, id LIMIT 1";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, projectId);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(readJob(connection, results, false)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("读取最近 GitLab 同步任务失败", exception);
        }
    }

    public synchronized String lastSuccessfulSyncAt(String projectId) {
        String sql = """
                SELECT finished_at FROM gitlab_sync_job
                WHERE project_id=? AND status='SUCCEEDED'
                ORDER BY finished_at DESC LIMIT 1
                """;
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, projectId);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? results.getString(1) : null;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("读取 GitLab 最近成功时间失败", exception);
        }
    }

    public synchronized void recordWebhookStatus(String projectId, String status, String eventId,
                                                 String targetSha, String message) {
        String sql = """
                INSERT INTO gitlab_webhook_status(project_id,status,event_id,target_sha,message,received_at)
                VALUES(?,?,?,?,?,?)
                ON CONFLICT(project_id) DO UPDATE SET status=excluded.status,event_id=excluded.event_id,
                    target_sha=excluded.target_sha,message=excluded.message,received_at=excluded.received_at
                """;
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, projectId);
            statement.setString(2, status);
            statement.setString(3, eventId);
            statement.setString(4, targetSha);
            statement.setString(5, message);
            statement.setString(6, Instant.now().toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("保存 GitLab Webhook 状态失败", exception);
        }
    }

    public synchronized Optional<GitLabWebhookStatus> webhookStatus(String projectId) {
        String sql = "SELECT * FROM gitlab_webhook_status WHERE project_id=?";
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, projectId);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(new GitLabWebhookStatus(
                        projectId, results.getString("status"), results.getString("event_id"),
                        results.getString("target_sha"), results.getString("message"),
                        results.getString("received_at"))) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("读取 GitLab Webhook 状态失败", exception);
        }
    }

    public synchronized void updateWebhookSecret(String projectId, String encryptedSecret) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE gitlab_managed_project SET webhook_secret_ciphertext=?,updated_at=? WHERE project_id=?")) {
            statement.setString(1, encryptedSecret);
            statement.setString(2, Instant.now().toString());
            statement.setString(3, projectId);
            if (statement.executeUpdate() != 1) {
                throw new IllegalArgumentException("未知 GitLab 项目: " + projectId);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("轮换 GitLab Webhook Secret 失败", exception);
        }
    }

    public synchronized boolean delete(String projectId) {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement events = connection.prepareStatement(
                    "DELETE FROM gitlab_webhook_event WHERE project_id=?");
                 PreparedStatement project = connection.prepareStatement(
                         "DELETE FROM gitlab_managed_project WHERE project_id=?")) {
                events.setString(1, projectId);
                events.executeUpdate();
                project.setString(1, projectId);
                boolean deleted = project.executeUpdate() == 1;
                connection.commit();
                return deleted;
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("删除 GitLab 项目失败", exception);
        }
    }

    private void initialize() {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS gitlab_managed_project (
                        project_id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        group_name TEXT NOT NULL,
                        side TEXT NOT NULL,
                        clone_url TEXT NOT NULL,
                        branch_name TEXT NOT NULL,
                        git_path TEXT NOT NULL,
                        requirement_collection TEXT NOT NULL,
                        code_collection TEXT NOT NULL,
                        repository_path TEXT NOT NULL,
                        access_token_ciphertext TEXT NOT NULL,
                        webhook_secret_ciphertext TEXT NOT NULL,
                        status TEXT NOT NULL,
                        last_indexed_sha TEXT,
                        target_sha TEXT,
                        last_error TEXT,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """);
            ensureColumn(connection, "gitlab_managed_project", "target_sha", "TEXT");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS gitlab_webhook_event (
                        project_id TEXT NOT NULL,
                        event_id TEXT NOT NULL,
                        received_at TEXT NOT NULL,
                        PRIMARY KEY(project_id, event_id),
                        FOREIGN KEY(project_id) REFERENCES gitlab_managed_project(project_id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS gitlab_sync_job (
                        id TEXT PRIMARY KEY,
                        project_id TEXT NOT NULL,
                        trigger_type TEXT NOT NULL,
                        status TEXT NOT NULL,
                        phase TEXT NOT NULL,
                        source_sha TEXT,
                        target_sha TEXT,
                        changed_files INTEGER,
                        error_code TEXT,
                        error_message TEXT,
                        correlation_id TEXT NOT NULL,
                        started_at TEXT NOT NULL,
                        finished_at TEXT,
                        FOREIGN KEY(project_id) REFERENCES gitlab_managed_project(project_id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS gitlab_sync_event (
                        id TEXT PRIMARY KEY,
                        job_id TEXT NOT NULL,
                        phase TEXT NOT NULL,
                        status TEXT NOT NULL,
                        message TEXT,
                        created_at TEXT NOT NULL,
                        FOREIGN KEY(job_id) REFERENCES gitlab_sync_job(id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS gitlab_webhook_status (
                        project_id TEXT PRIMARY KEY,
                        status TEXT NOT NULL,
                        event_id TEXT,
                        target_sha TEXT,
                        message TEXT,
                        received_at TEXT NOT NULL,
                        FOREIGN KEY(project_id) REFERENCES gitlab_managed_project(project_id) ON DELETE CASCADE
                    )
                    """);
            statement.executeUpdate("""
                    UPDATE gitlab_sync_job
                    SET status='INTERRUPTED',phase='INTERRUPTED',error_code='APPLICATION_RESTARTED',
                        error_message='应用重启中断了同步任务',finished_at=datetime('now')
                    WHERE status IN ('QUEUED','RUNNING')
                    """);
        } catch (SQLException exception) {
            throw new IllegalStateException("初始化 GitLab 接入数据库失败", exception);
        }
    }

    private void ensureColumn(Connection connection, String table, String column, String type)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet columns = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (columns.next()) {
                if (column.equals(columns.getString("name"))) {
                    return;
                }
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        }
    }

    private Connection connection() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
        }
        return connection;
    }

    private void bind(PreparedStatement statement, GitLabManagedProject project) throws SQLException {
        statement.setString(1, project.projectId());
        statement.setString(2, project.name());
        statement.setString(3, project.group());
        statement.setString(4, project.side());
        statement.setString(5, project.cloneUrl());
        statement.setString(6, project.branch());
        statement.setString(7, project.gitPath());
        statement.setString(8, project.requirementCollection());
        statement.setString(9, project.codeCollection());
        statement.setString(10, project.repositoryPath());
        statement.setString(11, project.encryptedAccessToken());
        statement.setString(12, project.encryptedWebhookSecret());
        statement.setString(13, project.status().name());
        statement.setString(14, project.lastIndexedSha());
        statement.setString(15, project.targetSha());
        statement.setString(16, project.lastError());
        statement.setString(17, project.createdAt());
        statement.setString(18, project.updatedAt());
    }

    private GitLabManagedProject read(ResultSet results) throws SQLException {
        return new GitLabManagedProject(
                results.getString("project_id"), results.getString("name"),
                results.getString("group_name"), results.getString("side"),
                results.getString("clone_url"), results.getString("branch_name"),
                results.getString("git_path"), results.getString("requirement_collection"),
                results.getString("code_collection"), results.getString("repository_path"),
                results.getString("access_token_ciphertext"), results.getString("webhook_secret_ciphertext"),
                GitLabProjectStatus.valueOf(results.getString("status")),
                results.getString("last_indexed_sha"), results.getString("target_sha"),
                results.getString("last_error"),
                results.getString("created_at"), results.getString("updated_at"));
    }

    private void appendJobEvent(Connection connection, String jobId, String phase, String status,
                                String message) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO gitlab_sync_event(id,job_id,phase,status,message,created_at)
                VALUES(?,?,?,?,?,?)
                """)) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, jobId);
            statement.setString(3, phase);
            statement.setString(4, status);
            statement.setString(5, message);
            statement.setString(6, Instant.now().toString());
            statement.executeUpdate();
        }
    }

    private GitLabSyncJob readJob(Connection connection, ResultSet results, boolean includeEvents)
            throws SQLException {
        String id = results.getString("id");
        return new GitLabSyncJob(
                id, results.getString("project_id"), results.getString("trigger_type"),
                results.getString("status"), results.getString("phase"),
                results.getString("source_sha"), results.getString("target_sha"),
                (Integer) results.getObject("changed_files"), results.getString("error_code"),
                results.getString("error_message"), results.getString("correlation_id"),
                results.getString("started_at"), results.getString("finished_at"),
                includeEvents ? readEvents(connection, id) : List.of());
    }

    private List<GitLabSyncJob.Event> readEvents(Connection connection, String jobId) throws SQLException {
        List<GitLabSyncJob.Event> events = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM gitlab_sync_event WHERE job_id=? ORDER BY created_at,id")) {
            statement.setString(1, jobId);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) {
                    events.add(new GitLabSyncJob.Event(
                            results.getString("id"), jobId, results.getString("phase"),
                            results.getString("status"), results.getString("message"),
                            results.getString("created_at")));
                }
            }
        }
        return List.copyOf(events);
    }
}
