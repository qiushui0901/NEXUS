package com.example.requirementrag.project;

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

/** SQLite 业务项目目录；与 GitLab 凭据/任务库分离。 */
@Repository
public class BusinessProjectCatalogStore {

    private final String jdbcUrl;

    public BusinessProjectCatalogStore(BusinessProjectCatalogProperties properties) {
        Path database = Path.of(properties.databasePath()).toAbsolutePath().normalize();
        try {
            if (database.getParent() != null) Files.createDirectories(database.getParent());
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("无法创建业务项目目录数据库", exception);
        }
        jdbcUrl = "jdbc:sqlite:" + database;
        initialize();
    }

    public synchronized List<BusinessProject> projects() {
        return queryProjects("SELECT * FROM business_project ORDER BY created_at,id", null);
    }

    public synchronized Optional<BusinessProject> project(String id) {
        List<BusinessProject> values = queryProjects(
                "SELECT * FROM business_project WHERE id=?", statement -> statement.setString(1, id));
        return values.stream().findFirst();
    }

    public synchronized Optional<String> resolveAlias(String legacyId) {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT business_project_id FROM business_project_alias WHERE legacy_id=?")) {
            statement.setString(1, legacyId);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(results.getString(1)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw failure("读取业务项目别名失败", exception);
        }
    }

    public synchronized List<CodeRepository> repositories() {
        return queryRepositories("SELECT * FROM code_repository ORDER BY created_at,id", null);
    }

    public synchronized Optional<CodeRepository> repository(String id) {
        List<CodeRepository> values = queryRepositories(
                "SELECT * FROM code_repository WHERE id=?", statement -> statement.setString(1, id));
        return values.stream().findFirst();
    }

    public synchronized List<CodeRepository> ownedRepositories(String businessProjectId) {
        return queryRepositories("""
                SELECT * FROM code_repository
                WHERE kind='PROJECT' AND business_project_id=?
                ORDER BY created_at,id
                """, statement -> statement.setString(1, businessProjectId));
    }

    public synchronized List<CodeRepository> referencedSharedRepositories(String businessProjectId) {
        return queryRepositories("""
                SELECT r.* FROM code_repository r
                JOIN business_project_shared_repository s ON s.repository_id=r.id
                WHERE s.business_project_id=? AND r.kind='SHARED'
                ORDER BY r.created_at,r.id
                """, statement -> statement.setString(1, businessProjectId));
    }

    public synchronized void createProject(BusinessProject project) {
        validateProject(project);
        String sql = """
                INSERT INTO business_project(
                  id,name,version_anchor_repository_id,requirement_collection,requirement_document_id,
                  requirement_snapshot_namespace,wiki_namespace,latest_requirement_version,status,created_at,updated_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """;
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bindProject(statement, project);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw failure("创建业务项目失败", exception);
        }
    }

    public synchronized void createRepository(CodeRepository repository) {
        validateRepository(repository);
        String sql = """
                INSERT INTO code_repository(
                  id,name,kind,business_project_id,side,code_collection,repository_path,git_path,
                  version_source_type,version_source_path,live_alias,enabled,created_at,updated_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """;
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bindRepository(statement, repository);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw failure("创建代码仓库目录记录失败", exception);
        }
    }

    public synchronized void deleteRepository(String repositoryId) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM code_repository WHERE id=?")) {
            statement.setString(1, repositoryId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw failure("删除代码仓库目录记录失败", exception);
        }
    }

    public synchronized void addSharedReference(String projectId, String repositoryId) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
                INSERT OR IGNORE INTO business_project_shared_repository(
                  business_project_id,repository_id,created_at
                ) VALUES(?,?,?)
                """)) {
            statement.setString(1, projectId);
            statement.setString(2, repositoryId);
            statement.setString(3, Instant.now().toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw failure("添加公共库引用失败", exception);
        }
    }

    public synchronized void removeSharedReference(String projectId, String repositoryId) {
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM business_project_shared_repository
                WHERE business_project_id=? AND repository_id=?
                """)) {
            statement.setString(1, projectId);
            statement.setString(2, repositoryId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw failure("移除公共库引用失败", exception);
        }
    }

    /** 原子写入业务项目、仓库和旧 ID 别名；重复执行保持同一结果。 */
    public synchronized void applyMigration(String migrationId, BusinessProject project,
                                            List<CodeRepository> repositories, List<String> legacyAliases) {
        validateProject(project);
        repositories.forEach(BusinessProjectCatalogStore::validateRepository);
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                if (migrationCompleted(connection, migrationId)) {
                    ensureProject(connection, project);
                    for (CodeRepository repository : repositories) ensureRepository(connection, repository);
                    for (String alias : legacyAliases) ensureAlias(connection, alias, project.id());
                    connection.rollback();
                    return;
                }
                ensureProject(connection, project);
                for (CodeRepository repository : repositories) {
                    ensureRepository(connection, repository);
                }
                for (String alias : legacyAliases) {
                    ensureAlias(connection, alias, project.id());
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO business_project_migration(id,status,completed_at)
                        VALUES(?,'COMPLETED',?)
                        ON CONFLICT(id) DO UPDATE SET status='COMPLETED',completed_at=excluded.completed_at
                        """)) {
                    statement.setString(1, migrationId);
                    statement.setString(2, Instant.now().toString());
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw failure("执行业务项目迁移失败", exception);
        }
    }

    private void initialize() {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS business_project(
                      id TEXT PRIMARY KEY,
                      name TEXT NOT NULL,
                      version_anchor_repository_id TEXT NOT NULL,
                      requirement_collection TEXT NOT NULL,
                      requirement_document_id TEXT NOT NULL,
                      requirement_snapshot_namespace TEXT NOT NULL,
                      wiki_namespace TEXT NOT NULL,
                      latest_requirement_version TEXT,
                      status TEXT NOT NULL,
                      created_at TEXT NOT NULL,
                      updated_at TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS code_repository(
                      id TEXT PRIMARY KEY,
                      name TEXT NOT NULL,
                      kind TEXT NOT NULL CHECK(kind IN ('PROJECT','SHARED')),
                      business_project_id TEXT,
                      side TEXT NOT NULL,
                      code_collection TEXT NOT NULL UNIQUE,
                      repository_path TEXT NOT NULL,
                      git_path TEXT,
                      version_source_type TEXT NOT NULL,
                      version_source_path TEXT NOT NULL,
                      live_alias INTEGER NOT NULL,
                      enabled INTEGER NOT NULL,
                      created_at TEXT NOT NULL,
                      updated_at TEXT NOT NULL,
                      FOREIGN KEY(business_project_id) REFERENCES business_project(id),
                      CHECK((kind='PROJECT' AND business_project_id IS NOT NULL)
                         OR (kind='SHARED' AND business_project_id IS NULL))
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS business_project_shared_repository(
                      business_project_id TEXT NOT NULL,
                      repository_id TEXT NOT NULL,
                      created_at TEXT NOT NULL,
                      PRIMARY KEY(business_project_id,repository_id),
                      FOREIGN KEY(business_project_id) REFERENCES business_project(id) ON DELETE CASCADE,
                      FOREIGN KEY(repository_id) REFERENCES code_repository(id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS business_project_alias(
                      legacy_id TEXT PRIMARY KEY,
                      business_project_id TEXT NOT NULL,
                      FOREIGN KEY(business_project_id) REFERENCES business_project(id) ON DELETE CASCADE
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS business_project_migration(
                      id TEXT PRIMARY KEY,
                      status TEXT NOT NULL,
                      completed_at TEXT
                    )
                    """);
            ensureColumn(connection, "business_project", "wiki_namespace", "TEXT NOT NULL DEFAULT ''");
            ensureColumn(connection, "code_repository", "live_alias", "INTEGER NOT NULL DEFAULT 0");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_repository_project ON code_repository(business_project_id,kind)");
        } catch (SQLException exception) {
            throw failure("初始化业务项目目录失败", exception);
        }
    }

    private List<BusinessProject> queryProjects(String sql, SqlBinder binder) {
        List<BusinessProject> values = new ArrayList<>();
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            if (binder != null) binder.bind(statement);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) values.add(readProject(results));
            }
            return List.copyOf(values);
        } catch (SQLException exception) {
            throw failure("读取业务项目目录失败", exception);
        }
    }

    private List<CodeRepository> queryRepositories(String sql, SqlBinder binder) {
        List<CodeRepository> values = new ArrayList<>();
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            if (binder != null) binder.bind(statement);
            try (ResultSet results = statement.executeQuery()) {
                while (results.next()) values.add(readRepository(results));
            }
            return List.copyOf(values);
        } catch (SQLException exception) {
            throw failure("读取代码仓库目录失败", exception);
        }
    }

    private void ensureProject(Connection connection, BusinessProject project) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM business_project WHERE id=?")) {
            statement.setString(1, project.id());
            try (ResultSet results = statement.executeQuery()) {
                if (results.next()) {
                    if (!sameProject(readProject(results), project)) {
                        throw new IllegalStateException("业务项目迁移冲突: " + project.id());
                    }
                    return;
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO business_project(
                  id,name,version_anchor_repository_id,requirement_collection,requirement_document_id,
                  requirement_snapshot_namespace,wiki_namespace,latest_requirement_version,status,created_at,updated_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            bindProject(statement, project);
            statement.executeUpdate();
        }
    }

    private void ensureRepository(Connection connection, CodeRepository repository) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM code_repository WHERE id=? OR code_collection=?")) {
            statement.setString(1, repository.id());
            statement.setString(2, repository.codeCollection());
            try (ResultSet results = statement.executeQuery()) {
                if (results.next()) {
                    CodeRepository existing = readRepository(results);
                    if (!existing.id().equals(repository.id()) || !sameRepository(existing, repository)) {
                        throw new IllegalStateException("代码仓库迁移冲突: " + repository.id());
                    }
                    return;
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO code_repository(
                  id,name,kind,business_project_id,side,code_collection,repository_path,git_path,
                  version_source_type,version_source_path,live_alias,enabled,created_at,updated_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            bindRepository(statement, repository);
            statement.executeUpdate();
        }
    }

    private void ensureAlias(Connection connection, String alias, String projectId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT business_project_id FROM business_project_alias WHERE legacy_id=?")) {
            statement.setString(1, alias);
            try (ResultSet results = statement.executeQuery()) {
                if (results.next()) {
                    if (!projectId.equals(results.getString(1))) {
                        throw new IllegalStateException("业务项目别名迁移冲突: " + alias);
                    }
                    return;
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO business_project_alias(legacy_id,business_project_id) VALUES(?,?)
                """)) {
            statement.setString(1, alias);
            statement.setString(2, projectId);
            statement.executeUpdate();
        }
    }

    private boolean sameProject(BusinessProject left, BusinessProject right) {
        return left.id().equals(right.id())
                && left.name().equals(right.name())
                && left.versionAnchorRepositoryId().equals(right.versionAnchorRepositoryId())
                && left.requirementCollection().equals(right.requirementCollection())
                && left.requirementDocumentId().equals(right.requirementDocumentId())
                && left.requirementSnapshotNamespace().equals(right.requirementSnapshotNamespace())
                && left.wikiNamespace().equals(right.wikiNamespace())
                && java.util.Objects.equals(left.latestRequirementVersion(), right.latestRequirementVersion())
                && left.status() == right.status();
    }

    private boolean sameRepository(CodeRepository left, CodeRepository right) {
        return left.id().equals(right.id())
                && left.name().equals(right.name())
                && left.kind() == right.kind()
                && java.util.Objects.equals(left.businessProjectId(), right.businessProjectId())
                && left.side().equals(right.side())
                && left.codeCollection().equals(right.codeCollection())
                && left.repositoryPath().equals(right.repositoryPath())
                && java.util.Objects.equals(left.gitPath(), right.gitPath())
                && left.versionSourceType().equals(right.versionSourceType())
                && left.versionSourcePath().equals(right.versionSourcePath())
                && left.liveAlias() == right.liveAlias()
                && left.enabled() == right.enabled();
    }

    private boolean migrationCompleted(Connection connection, String migrationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT status FROM business_project_migration WHERE id=?")) {
            statement.setString(1, migrationId);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() && "COMPLETED".equals(results.getString(1));
            }
        }
    }

    private void bindProject(PreparedStatement statement, BusinessProject value) throws SQLException {
        statement.setString(1, value.id());
        statement.setString(2, value.name());
        statement.setString(3, value.versionAnchorRepositoryId());
        statement.setString(4, value.requirementCollection());
        statement.setString(5, value.requirementDocumentId());
        statement.setString(6, value.requirementSnapshotNamespace());
        statement.setString(7, value.wikiNamespace());
        statement.setString(8, value.latestRequirementVersion());
        statement.setString(9, value.status().name());
        statement.setString(10, value.createdAt());
        statement.setString(11, value.updatedAt());
    }

    private void bindRepository(PreparedStatement statement, CodeRepository value) throws SQLException {
        statement.setString(1, value.id());
        statement.setString(2, value.name());
        statement.setString(3, value.kind().name());
        statement.setString(4, value.businessProjectId());
        statement.setString(5, value.side());
        statement.setString(6, value.codeCollection());
        statement.setString(7, value.repositoryPath());
        statement.setString(8, value.gitPath());
        statement.setString(9, value.versionSourceType());
        statement.setString(10, value.versionSourcePath());
        statement.setInt(11, value.liveAlias() ? 1 : 0);
        statement.setInt(12, value.enabled() ? 1 : 0);
        statement.setString(13, value.createdAt());
        statement.setString(14, value.updatedAt());
    }

    private BusinessProject readProject(ResultSet results) throws SQLException {
        return new BusinessProject(results.getString("id"), results.getString("name"),
                results.getString("version_anchor_repository_id"), results.getString("requirement_collection"),
                results.getString("requirement_document_id"), results.getString("requirement_snapshot_namespace"),
                results.getString("wiki_namespace"), results.getString("latest_requirement_version"),
                BusinessProject.Status.valueOf(results.getString("status")),
                results.getString("created_at"), results.getString("updated_at"));
    }

    private CodeRepository readRepository(ResultSet results) throws SQLException {
        return new CodeRepository(results.getString("id"), results.getString("name"),
                CodeRepository.Kind.valueOf(results.getString("kind")),
                results.getString("business_project_id"), results.getString("side"),
                results.getString("code_collection"), results.getString("repository_path"),
                results.getString("git_path"), results.getString("version_source_type"),
                results.getString("version_source_path"), results.getBoolean("live_alias"),
                results.getBoolean("enabled"),
                results.getString("created_at"), results.getString("updated_at"));
    }

    private Connection connection() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");
        }
        return connection;
    }

    private void ensureColumn(Connection connection, String table, String column, String definition)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet columns = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (columns.next()) {
                if (column.equals(columns.getString("name"))) return;
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        }
    }

    private static void validateProject(BusinessProject value) {
        if (value == null || !value.complete() || value.status() == null) {
            throw new IllegalArgumentException("业务项目配置不完整");
        }
    }

    private static void validateRepository(CodeRepository value) {
        if (value == null || blank(value.id()) || blank(value.name()) || value.kind() == null
                || blank(value.side()) || blank(value.codeCollection()) || blank(value.repositoryPath())
                || blank(value.versionSourceType()) || blank(value.versionSourcePath())
                || (value.kind() == CodeRepository.Kind.PROJECT && blank(value.businessProjectId()))
                || (value.kind() == CodeRepository.Kind.SHARED && value.businessProjectId() != null)) {
            throw new IllegalArgumentException("代码仓库目录配置不完整");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private IllegalStateException failure(String message, SQLException exception) {
        return new IllegalStateException(message, exception);
    }

    @FunctionalInterface
    private interface SqlBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }
}
