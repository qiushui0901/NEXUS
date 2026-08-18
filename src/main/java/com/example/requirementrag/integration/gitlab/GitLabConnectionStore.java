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

/** GitLab 账号连接 SQLite 存储。 */
@Repository
@ConditionalOnProperty(name = "app.rag.gitlab.enabled", havingValue = "true")
public class GitLabConnectionStore {
    private final String jdbcUrl;

    public GitLabConnectionStore(GitLabIntegrationProperties properties) {
        Path database = Path.of(properties.databasePath()).toAbsolutePath().normalize();
        try {
            if (database.getParent() != null) {
                Files.createDirectories(database.getParent());
            }
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("无法创建 GitLab 接入数据库目录", exception);
        }
        jdbcUrl = "jdbc:sqlite:" + database;
        initialize();
    }

    public synchronized void save(GitLabConnection value) {
        String sql = """
                INSERT INTO gitlab_connection(
                    id,name,base_url,host,username,display_name,access_token_ciphertext,
                    status,last_verified_at,last_error,created_at,updated_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET
                    name=excluded.name,base_url=excluded.base_url,host=excluded.host,
                    username=excluded.username,display_name=excluded.display_name,
                    access_token_ciphertext=excluded.access_token_ciphertext,status=excluded.status,
                    last_verified_at=excluded.last_verified_at,last_error=excluded.last_error,
                    updated_at=excluded.updated_at
                """;
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value.id());
            statement.setString(2, value.name());
            statement.setString(3, value.baseUrl());
            statement.setString(4, value.host());
            statement.setString(5, value.username());
            statement.setString(6, value.displayName());
            statement.setString(7, value.encryptedAccessToken());
            statement.setString(8, value.status().name());
            statement.setString(9, value.lastVerifiedAt());
            statement.setString(10, value.lastError());
            statement.setString(11, value.createdAt());
            statement.setString(12, value.updatedAt());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("保存 GitLab 账号连接失败", exception);
        }
    }

    public synchronized Optional<GitLabConnection> find(String id) {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM gitlab_connection WHERE id=?")) {
            statement.setString(1, id);
            try (ResultSet results = statement.executeQuery()) {
                return results.next() ? Optional.of(read(results)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("读取 GitLab 账号连接失败", exception);
        }
    }

    public synchronized List<GitLabConnection> all() {
        List<GitLabConnection> values = new ArrayList<>();
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet results = statement.executeQuery(
                     "SELECT * FROM gitlab_connection ORDER BY created_at,id")) {
            while (results.next()) {
                values.add(read(results));
            }
            return List.copyOf(values);
        } catch (SQLException exception) {
            throw new IllegalStateException("读取 GitLab 账号连接列表失败", exception);
        }
    }

    public synchronized void updateStatus(String id, GitLabConnectionStatus status, String error) {
        String sql = """
                UPDATE gitlab_connection
                SET status=?,last_error=?,last_verified_at=?,updated_at=?
                WHERE id=?
                """;
        String now = Instant.now().toString();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status.name());
            statement.setString(2, error);
            statement.setString(3, now);
            statement.setString(4, now);
            statement.setString(5, id);
            if (statement.executeUpdate() != 1) {
                throw new IllegalArgumentException("未知 GitLab 账号连接: " + id);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("更新 GitLab 账号连接状态失败", exception);
        }
    }

    private void initialize() {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS gitlab_connection(
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        base_url TEXT NOT NULL,
                        host TEXT NOT NULL,
                        username TEXT NOT NULL,
                        display_name TEXT NOT NULL,
                        access_token_ciphertext TEXT NOT NULL,
                        status TEXT NOT NULL,
                        last_verified_at TEXT,
                        last_error TEXT,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_gitlab_connection_name
                    ON gitlab_connection(name)
                    """);
        } catch (SQLException exception) {
            throw new IllegalStateException("初始化 GitLab 账号连接数据库失败", exception);
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

    private GitLabConnection read(ResultSet results) throws SQLException {
        return new GitLabConnection(
                results.getString("id"),
                results.getString("name"),
                results.getString("base_url"),
                results.getString("host"),
                results.getString("username"),
                results.getString("display_name"),
                results.getString("access_token_ciphertext"),
                GitLabConnectionStatus.valueOf(results.getString("status")),
                results.getString("last_verified_at"),
                results.getString("last_error"),
                results.getString("created_at"),
                results.getString("updated_at"));
    }
}
