package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.DoubtClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.KnowledgeStatus;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.ParameterClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.ParameterValueType;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.TestCaseClaim;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.TestResultClaim;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
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

/**
 * 多源知识 SQLite 存储：结构化参数 Claim 与需求存疑 Claim。
 *
 * <p>独立于既有知识管理存储，避免改动成熟链路；schema 以来源元数据 + 版本 + 状态为核心。
 */
@Component
public class MultiSourceKnowledgeStore {
    private final String jdbcUrl;
    private final ObjectMapper objectMapper;

    /** Spring 默认数据库路径。 */
    @Autowired
    public MultiSourceKnowledgeStore(ObjectMapper objectMapper) {
        this("data/multi-source-knowledge.db", objectMapper);
    }

    public MultiSourceKnowledgeStore(String databasePath, ObjectMapper objectMapper) {
        try {
            Path database = Path.of(databasePath).toAbsolutePath().normalize();
            if (database.getParent() != null) Files.createDirectories(database.getParent());
            this.jdbcUrl = "jdbc:sqlite:" + database;
            this.objectMapper = objectMapper;
            initialize();
        } catch (IOException exception) {
            throw new IllegalStateException("无法初始化多源知识库目录", exception);
        }
    }

    private void initialize() {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.executeUpdate("""
                    create table if not exists multi_source_parameter(
                      claim_id text primary key,
                      project_id text not null,
                      version text not null,
                      workbook text not null,
                      sheet_name text not null,
                      row_number integer not null,
                      column_range text,
                      module text,
                      parameter text not null,
                      raw_value text,
                      normalized_value text,
                      unit text,
                      min_value text,
                      max_value text,
                      precision integer not null default 0,
                      inclusive_boundary integer not null default 0,
                      value_type text not null,
                      fact_key text not null,
                      evidence_location text not null,
                      status text not null default 'SUPPORTED',
                      created_at text not null
                    )
                    """);
            statement.executeUpdate("""
                    create table if not exists multi_source_doubt(
                      doubt_id text primary key,
                      project_id text not null,
                      version text not null,
                      module text,
                      question text not null,
                      answer text,
                      source_sheet text,
                      row_number integer not null,
                      status text not null default 'OPEN',
                      owner text,
                      severity text,
                      due_date text,
                      proposed_options text not null default '[]',
                      evidence_location text not null,
                      created_at text not null
                    )
                    """);
            statement.executeUpdate("create index if not exists idx_multi_source_param_scope on multi_source_parameter(project_id,version,fact_key)");
            statement.executeUpdate("create index if not exists idx_multi_source_doubt_scope on multi_source_doubt(project_id,version,status)");
            statement.executeUpdate("""
                    create table if not exists multi_source_test_case(
                      claim_id text primary key,
                      project_id text not null,
                      version text not null,
                      test_case_id text not null,
                      title text,
                      module text,
                      preconditions text,
                      steps text,
                      expected_result text,
                      covered_requirement_id text,
                      framework text,
                      file_path text,
                      test_method text,
                      evidence_location text not null,
                      created_at text not null
                    )
                    """);
            statement.executeUpdate("""
                    create table if not exists multi_source_test_result(
                      claim_id text primary key,
                      project_id text not null,
                      version text not null,
                      test_run_id text,
                      test_case_id text not null,
                      execution_status text not null,
                      executed_at text,
                      environment text,
                      actual_result text,
                      failure_message text,
                      evidence_location text not null,
                      created_at text not null
                    )
                    """);
            statement.executeUpdate("create index if not exists idx_multi_source_tc_scope on multi_source_test_case(project_id,version,covered_requirement_id)");
            statement.executeUpdate("create index if not exists idx_multi_source_tr_scope on multi_source_test_result(project_id,version,test_case_id)");
        } catch (SQLException exception) {
            throw new IllegalStateException("无法初始化多源知识库", exception);
        }
    }

    public synchronized void saveParameters(String projectId, String version, List<ParameterClaim> claims) {
        if (claims == null || claims.isEmpty()) return;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                insert into multi_source_parameter(
                  claim_id,project_id,version,workbook,sheet_name,row_number,column_range,module,parameter,
                  raw_value,normalized_value,unit,min_value,max_value,precision,inclusive_boundary,value_type,
                  fact_key,evidence_location,status,created_at)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                on conflict(claim_id) do update set
                  module=excluded.module,parameter=excluded.parameter,raw_value=excluded.raw_value,
                  normalized_value=excluded.normalized_value,unit=excluded.unit,min_value=excluded.min_value,
                  max_value=excluded.max_value,precision=excluded.precision,inclusive_boundary=excluded.inclusive_boundary,
                  value_type=excluded.value_type,fact_key=excluded.fact_key,evidence_location=excluded.evidence_location,
                  status=excluded.status
                """)) {
            String createdAt = Instant.now().toString();
            for (ParameterClaim claim : claims) {
                statement.setString(1, claim.claimId());
                statement.setString(2, projectId);
                statement.setString(3, version);
                statement.setString(4, claim.workbook());
                statement.setString(5, claim.sheetName());
                statement.setInt(6, claim.rowNumber());
                statement.setString(7, claim.columnRange());
                statement.setString(8, claim.module());
                statement.setString(9, claim.parameter());
                statement.setString(10, claim.rawValue());
                statement.setString(11, claim.normalizedValue());
                statement.setString(12, claim.unit());
                statement.setString(13, decimal(claim.minValue()));
                statement.setString(14, decimal(claim.maxValue()));
                statement.setInt(15, claim.precision());
                statement.setInt(16, claim.inclusiveBoundary() ? 1 : 0);
                statement.setString(17, claim.valueType().name());
                statement.setString(18, claim.factKey());
                statement.setString(19, claim.evidenceLocation());
                statement.setString(20, KnowledgeStatus.SUPPORTED.name());
                statement.setString(21, createdAt);
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException exception) {
            throw new IllegalStateException("无法保存多源参数 Claim", exception);
        }
    }

    public synchronized List<ParameterClaim> findParameters(String projectId, String version) {
        List<ParameterClaim> result = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "select * from multi_source_parameter where project_id=? and version=? order by row_number")) {
            statement.setString(1, projectId);
            statement.setString(2, version);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new ParameterClaim(
                            rows.getString("claim_id"), rows.getString("project_id"), rows.getString("version"),
                            rows.getString("workbook"), rows.getString("sheet_name"), rows.getInt("row_number"),
                            rows.getString("column_range"), rows.getString("module"), rows.getString("parameter"),
                            rows.getString("raw_value"), rows.getString("normalized_value"), rows.getString("unit"),
                            decimal(rows.getString("min_value")), decimal(rows.getString("max_value")),
                            rows.getInt("precision"), rows.getInt("inclusive_boundary") == 1,
                            ParameterValueType.valueOf(rows.getString("value_type")),
                            rows.getString("fact_key"), rows.getString("evidence_location")));
                }
            }
            return List.copyOf(result);
        } catch (SQLException exception) {
            throw new IllegalStateException("无法读取多源参数 Claim", exception);
        }
    }

    public synchronized void saveDoubts(String projectId, String version, List<DoubtClaim> doubts) {
        if (doubts == null || doubts.isEmpty()) return;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                insert into multi_source_doubt(
                  doubt_id,project_id,version,module,question,answer,source_sheet,row_number,status,
                  owner,severity,due_date,proposed_options,evidence_location,created_at)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                on conflict(doubt_id) do update set
                  module=excluded.module,question=excluded.question,answer=excluded.answer,
                  source_sheet=excluded.source_sheet,row_number=excluded.row_number,status=excluded.status,
                  owner=excluded.owner,severity=excluded.severity,due_date=excluded.due_date,
                  proposed_options=excluded.proposed_options,evidence_location=excluded.evidence_location
                """)) {
            String createdAt = Instant.now().toString();
            for (DoubtClaim doubt : doubts) {
                statement.setString(1, doubt.doubtId());
                statement.setString(2, projectId);
                statement.setString(3, version);
                statement.setString(4, doubt.module());
                statement.setString(5, doubt.question());
                statement.setString(6, doubt.answer());
                statement.setString(7, doubt.sourceSheet());
                statement.setInt(8, doubt.rowNumber());
                statement.setString(9, doubt.status().name());
                statement.setString(10, doubt.owner());
                statement.setString(11, doubt.severity());
                statement.setString(12, doubt.dueDate());
                statement.setString(13, json(doubt.proposedOptions()));
                statement.setString(14, doubt.evidenceLocation());
                statement.setString(15, createdAt);
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException exception) {
            throw new IllegalStateException("无法保存多源存疑 Claim", exception);
        }
    }

    public synchronized List<DoubtClaim> findDoubts(String projectId, String version) {
        List<DoubtClaim> result = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "select * from multi_source_doubt where project_id=? and version=? order by row_number")) {
            statement.setString(1, projectId);
            statement.setString(2, version);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new DoubtClaim(
                            rows.getString("doubt_id"), rows.getString("project_id"), rows.getString("version"),
                            rows.getString("module"), rows.getString("question"), rows.getString("answer"),
                            rows.getString("source_sheet"), rows.getInt("row_number"),
                            MultiSourceKnowledgeModels.DoubtStatus.valueOf(rows.getString("status")),
                            rows.getString("owner"), rows.getString("severity"), rows.getString("due_date"),
                            list(rows.getString("proposed_options")), rows.getString("evidence_location")));
                }
            }
            return List.copyOf(result);
        } catch (SQLException exception) {
            throw new IllegalStateException("无法读取多源存疑 Claim", exception);
        }
    }

    public synchronized void saveTestCases(String projectId, String version, List<TestCaseClaim> claims) {
        if (claims == null || claims.isEmpty()) return;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                insert into multi_source_test_case(
                  claim_id,project_id,version,test_case_id,title,module,preconditions,steps,expected_result,
                  covered_requirement_id,framework,file_path,test_method,evidence_location,created_at)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                on conflict(claim_id) do update set
                  title=excluded.title,module=excluded.module,steps=excluded.steps,
                  expected_result=excluded.expected_result,covered_requirement_id=excluded.covered_requirement_id,
                  framework=excluded.framework,test_method=excluded.test_method,evidence_location=excluded.evidence_location
                """)) {
            String createdAt = Instant.now().toString();
            for (TestCaseClaim claim : claims) {
                statement.setString(1, claim.claimId());
                statement.setString(2, projectId);
                statement.setString(3, version);
                statement.setString(4, claim.testCaseId());
                statement.setString(5, claim.title());
                statement.setString(6, claim.module());
                statement.setString(7, claim.preconditions());
                statement.setString(8, claim.steps());
                statement.setString(9, claim.expectedResult());
                statement.setString(10, claim.coveredRequirementId());
                statement.setString(11, claim.framework());
                statement.setString(12, claim.filePath());
                statement.setString(13, claim.testMethod());
                statement.setString(14, claim.evidenceLocation());
                statement.setString(15, createdAt);
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException exception) {
            throw new IllegalStateException("无法保存测试用例 Claim", exception);
        }
    }

    public synchronized List<TestCaseClaim> findTestCases(String projectId, String version) {
        List<TestCaseClaim> result = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "select * from multi_source_test_case where project_id=? and version=? order by test_case_id")) {
            statement.setString(1, projectId);
            statement.setString(2, version);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new TestCaseClaim(
                            rows.getString("claim_id"), rows.getString("project_id"), rows.getString("version"),
                            rows.getString("test_case_id"), rows.getString("title"), rows.getString("module"),
                            rows.getString("preconditions"), rows.getString("steps"), rows.getString("expected_result"),
                            rows.getString("covered_requirement_id"), rows.getString("framework"),
                            rows.getString("file_path"), rows.getString("test_method"), rows.getString("evidence_location")));
                }
            }
            return List.copyOf(result);
        } catch (SQLException exception) {
            throw new IllegalStateException("无法读取测试用例 Claim", exception);
        }
    }

    public synchronized void saveTestResults(String projectId, String version, List<TestResultClaim> claims) {
        if (claims == null || claims.isEmpty()) return;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                insert into multi_source_test_result(
                  claim_id,project_id,version,test_run_id,test_case_id,execution_status,executed_at,
                  environment,actual_result,failure_message,evidence_location,created_at)
                values(?,?,?,?,?,?,?,?,?,?,?,?)
                on conflict(claim_id) do update set
                  execution_status=excluded.execution_status,executed_at=excluded.executed_at,
                  environment=excluded.environment,actual_result=excluded.actual_result,
                  failure_message=excluded.failure_message,evidence_location=excluded.evidence_location
                """)) {
            String createdAt = Instant.now().toString();
            for (TestResultClaim claim : claims) {
                statement.setString(1, claim.claimId());
                statement.setString(2, projectId);
                statement.setString(3, version);
                statement.setString(4, claim.testRunId());
                statement.setString(5, claim.testCaseId());
                statement.setString(6, claim.executionStatus());
                statement.setString(7, claim.executedAt());
                statement.setString(8, claim.environment());
                statement.setString(9, claim.actualResult());
                statement.setString(10, claim.failureMessage());
                statement.setString(11, claim.evidenceLocation());
                statement.setString(12, createdAt);
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException exception) {
            throw new IllegalStateException("无法保存测试结果 Claim", exception);
        }
    }

    public synchronized List<TestResultClaim> findTestResults(String projectId, String version) {
        List<TestResultClaim> result = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "select * from multi_source_test_result where project_id=? and version=? order by test_case_id")) {
            statement.setString(1, projectId);
            statement.setString(2, version);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new TestResultClaim(
                            rows.getString("claim_id"), rows.getString("project_id"), rows.getString("version"),
                            rows.getString("test_run_id"), rows.getString("test_case_id"),
                            rows.getString("execution_status"), rows.getString("executed_at"),
                            rows.getString("environment"), rows.getString("actual_result"),
                            rows.getString("failure_message"), rows.getString("evidence_location")));
                }
            }
            return List.copyOf(result);
        } catch (SQLException exception) {
            throw new IllegalStateException("无法读取测试结果 Claim", exception);
        }
    }

    /** 幂等重导：删除指定项目/版本的旧数据再写入。 */
    public synchronized void replaceProjectVersion(String projectId, String version) {
        try (Connection connection = open()) {
            for (String table : List.of("multi_source_parameter", "multi_source_doubt",
                    "multi_source_test_case", "multi_source_test_result")) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "delete from " + table + " where project_id=? and version=?")) {
                    statement.setString(1, projectId);
                    statement.setString(2, version);
                    statement.executeUpdate();
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("无法清理多源知识", exception);
        }
    }

    private String decimal(BigDecimal value) {
        return value == null ? null : value.toPlainString();
    }

    private BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String json(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化存疑备选方案", exception);
        }
    }

    private List<String> list(String value) {
        if (value == null || value.isBlank()) return List.of();
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }
}