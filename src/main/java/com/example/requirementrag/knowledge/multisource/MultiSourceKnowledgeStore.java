package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeDocument;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeDocumentVersion;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeEvidence;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeModels.CrossSourceRelation;
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
import java.util.Optional;

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
                      status text not null default 'SUPPORTED',
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
                      status text not null default 'SUPPORTED',
                      created_at text not null
                    )
                    """);
            statement.executeUpdate("create index if not exists idx_multi_source_tc_scope on multi_source_test_case(project_id,version,covered_requirement_id)");
            statement.executeUpdate("create index if not exists idx_multi_source_tr_scope on multi_source_test_result(project_id,version,test_case_id)");
            statement.executeUpdate("""
                    create table if not exists multi_source_relation(
                      relation_id text primary key,
                      project_id text not null,
                      version text not null,
                      source_claim_id text not null,
                      target_claim_id text not null,
                      relation_type text not null,
                      evidence_location text,
                      metadata text,
                      created_at text not null
                    )
                    """);
            statement.executeUpdate("create index if not exists idx_multi_source_rel_scope on multi_source_relation(project_id,version,relation_type)");
            addColumnIfMissing(statement, "multi_source_test_case", "status", "text not null default 'SUPPORTED'");
            addColumnIfMissing(statement, "multi_source_test_result", "status", "text not null default 'SUPPORTED'");

            // 0.9.3 统一资料目录：Document / DocumentVersion / Evidence
            statement.executeUpdate("""
                    create table if not exists knowledge_document(
                      document_id text primary key,
                      project_id text not null,
                      source_type text not null,
                      logical_name text not null,
                      original_name text,
                      storage_uri text not null,
                      authority text not null,
                      created_at text not null,
                      unique(project_id, source_type, logical_name)
                    )
                    """);
            statement.executeUpdate("""
                    create table if not exists knowledge_document_version(
                      document_version_id text primary key,
                      document_id text not null,
                      project_id text not null,
                      business_version text not null,
                      content_hash text not null,
                      parser_version text not null,
                      extraction_version text not null,
                      source_commit_sha text,
                      status text not null,
                      imported_at text not null,
                      published_at text,
                      foreign key(document_id) references knowledge_document(document_id),
                      unique(document_id, business_version, content_hash, parser_version, extraction_version)
                    )
                    """);
            statement.executeUpdate("""
                    create table if not exists knowledge_evidence(
                      evidence_id text primary key,
                      document_version_id text not null,
                      project_id text not null,
                      source_type text not null,
                      locator text not null,
                      excerpt text not null,
                      excerpt_hash text not null,
                      start_line integer,
                      end_line integer,
                      sheet_name text,
                      row_number integer,
                      column_range text,
                      repository_id text,
                      commit_sha text,
                      symbol_name text,
                      created_at text not null,
                      foreign key(document_version_id) references knowledge_document_version(document_version_id),
                      unique(document_version_id, locator, excerpt_hash)
                    )
                    """);
            statement.executeUpdate("create index if not exists idx_knowledge_doc_version_scope on knowledge_document_version(project_id,business_version)");
            statement.executeUpdate("create index if not exists idx_knowledge_evidence_scope on knowledge_evidence(project_id,document_version_id)");

            // 现有业务表关联 catalog 的可空列
            addColumnIfMissing(statement, "multi_source_parameter", "document_version_id", "text");
            addColumnIfMissing(statement, "multi_source_parameter", "evidence_id", "text");
            addColumnIfMissing(statement, "multi_source_doubt", "document_version_id", "text");
            addColumnIfMissing(statement, "multi_source_doubt", "evidence_id", "text");
            addColumnIfMissing(statement, "multi_source_test_case", "document_version_id", "text");
            addColumnIfMissing(statement, "multi_source_test_case", "evidence_id", "text");
            addColumnIfMissing(statement, "multi_source_test_result", "document_version_id", "text");
            addColumnIfMissing(statement, "multi_source_test_result", "evidence_id", "text");
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
                if (!version.equals(claim.version())) {
                    throw new IllegalArgumentException("参数 Claim 版本与导入版本不一致: " + claim.version() + " != " + version);
                }
                statement.setString(1, claim.claimId());
                statement.setString(2, projectId);
                statement.setString(3, claim.version());
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
                statement.setString(20, claim.status() == null ? KnowledgeStatus.SUPPORTED.name() : claim.status().name());
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
                            rows.getString("fact_key"), rows.getString("evidence_location"),
                            KnowledgeStatus.valueOf(rows.getString("status"))));
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
                  covered_requirement_id,framework,file_path,test_method,evidence_location,status,created_at)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                on conflict(claim_id) do update set
                  title=excluded.title,module=excluded.module,steps=excluded.steps,
                  expected_result=excluded.expected_result,covered_requirement_id=excluded.covered_requirement_id,
                  framework=excluded.framework,test_method=excluded.test_method,evidence_location=excluded.evidence_location,
                  status=excluded.status
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
                statement.setString(15, claim.status() == null ? KnowledgeStatus.SUPPORTED.name() : claim.status().name());
                statement.setString(16, createdAt);
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
                            rows.getString("file_path"), rows.getString("test_method"), rows.getString("evidence_location"),
                            KnowledgeStatus.valueOf(rows.getString("status"))));
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
                  environment,actual_result,failure_message,evidence_location,status,created_at)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?)
                on conflict(claim_id) do update set
                  execution_status=excluded.execution_status,executed_at=excluded.executed_at,
                  environment=excluded.environment,actual_result=excluded.actual_result,
                  failure_message=excluded.failure_message,evidence_location=excluded.evidence_location,
                  status=excluded.status
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
                statement.setString(12, claim.status() == null ? KnowledgeStatus.SUPPORTED.name() : claim.status().name());
                statement.setString(13, createdAt);
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
                            rows.getString("failure_message"), rows.getString("evidence_location"),
                            KnowledgeStatus.valueOf(rows.getString("status"))));
                }
            }
            return List.copyOf(result);
        } catch (SQLException exception) {
            throw new IllegalStateException("无法读取测试结果 Claim", exception);
        }
    }

    public synchronized void saveRelations(String projectId, String version, List<CrossSourceRelation> relations) {
        if (relations == null || relations.isEmpty()) return;
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement("""
                insert into multi_source_relation(
                  relation_id,project_id,version,source_claim_id,target_claim_id,relation_type,
                  evidence_location,metadata,created_at)
                values(?,?,?,?,?,?,?,?,?)
                on conflict(relation_id) do update set
                  source_claim_id=excluded.source_claim_id,target_claim_id=excluded.target_claim_id,
                  relation_type=excluded.relation_type,evidence_location=excluded.evidence_location,
                  metadata=excluded.metadata
                """)) {
            String createdAt = Instant.now().toString();
            for (CrossSourceRelation relation : relations) {
                statement.setString(1, relation.relationId());
                statement.setString(2, projectId);
                statement.setString(3, version);
                statement.setString(4, relation.sourceClaimId());
                statement.setString(5, relation.targetClaimId());
                statement.setString(6, relation.type().name());
                statement.setString(7, relation.evidenceLocation());
                statement.setString(8, relation.metadata());
                statement.setString(9, createdAt);
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException exception) {
            throw new IllegalStateException("无法保存跨来源关系", exception);
        }
    }

    public synchronized List<CrossSourceRelation> findRelations(String projectId, String version) {
        List<CrossSourceRelation> result = new ArrayList<>();
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "select * from multi_source_relation where project_id=? and version=? order by relation_type")) {
            statement.setString(1, projectId);
            statement.setString(2, version);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new CrossSourceRelation(
                            rows.getString("relation_id"), rows.getString("project_id"), rows.getString("version"),
                            rows.getString("source_claim_id"), rows.getString("target_claim_id"),
                            MultiSourceKnowledgeModels.CrossSourceRelationType.valueOf(rows.getString("relation_type")),
                            rows.getString("evidence_location"), rows.getString("metadata")));
                }
            }
            return List.copyOf(result);
        } catch (SQLException exception) {
            throw new IllegalStateException("无法读取跨来源关系", exception);
        }
    }

    /** 幂等重导：删除指定项目/版本的旧数据再写入。 */
    public synchronized void replaceProjectVersion(String projectId, String version) {
        replaceSnapshot(projectId, version, List.of(), List.of(), List.of(), List.of());
    }

    /** 事务性重导：一次调用完成清理 + 参数/存疑/测试用例/测试结果写入，任一步失败整体回滚。 */
    /** 事务性重导（不含关系）：兼容旧调用。 */
    public synchronized void replaceSnapshot(String projectId, String version, List<ParameterClaim> parameters,
                                             List<DoubtClaim> doubts, List<TestCaseClaim> testCases,
                                             List<TestResultClaim> testResults) {
        replaceSnapshot(projectId, version, parameters, doubts, testCases, testResults, List.of());
    }

    /** 事务性重导：一次调用完成清理 + 参数/存疑/测试用例/测试结果/跨源关系写入，任一步失败整体回滚。 */
    public synchronized void replaceSnapshot(String projectId, String version, List<ParameterClaim> parameters,
                                             List<DoubtClaim> doubts, List<TestCaseClaim> testCases,
                                             List<TestResultClaim> testResults, List<CrossSourceRelation> relations) {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                deleteProjectVersion(connection, projectId, version);
                insertParameters(connection, projectId, version, parameters);
                insertDoubts(connection, projectId, version, doubts);
                insertTestCases(connection, projectId, version, testCases);
                insertTestResults(connection, projectId, version, testResults);
                insertRelations(connection, projectId, version, relations);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("事务性重导多源知识失败", exception);
        }
    }

    private void deleteProjectVersion(Connection connection, String projectId, String version) throws SQLException {
        for (String table : List.of("multi_source_parameter", "multi_source_doubt",
                "multi_source_test_case", "multi_source_test_result", "multi_source_relation")) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "delete from " + table + " where project_id=? and version=?")) {
                statement.setString(1, projectId);
                statement.setString(2, version);
                statement.executeUpdate();
            }
        }
    }

    private void insertRelations(Connection connection, String projectId, String version,
                                 List<CrossSourceRelation> relations) throws SQLException {
        if (relations == null || relations.isEmpty()) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into multi_source_relation(
                  relation_id,project_id,version,source_claim_id,target_claim_id,relation_type,
                  evidence_location,metadata,created_at)
                values(?,?,?,?,?,?,?,?,?)
                on conflict(relation_id) do update set
                  source_claim_id=excluded.source_claim_id,target_claim_id=excluded.target_claim_id,
                  relation_type=excluded.relation_type,evidence_location=excluded.evidence_location,
                  metadata=excluded.metadata
                """)) {
            String createdAt = Instant.now().toString();
            for (CrossSourceRelation relation : relations) {
                statement.setString(1, relation.relationId());
                statement.setString(2, projectId);
                statement.setString(3, version);
                statement.setString(4, relation.sourceClaimId());
                statement.setString(5, relation.targetClaimId());
                statement.setString(6, relation.type().name());
                statement.setString(7, relation.evidenceLocation());
                statement.setString(8, relation.metadata());
                statement.setString(9, createdAt);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertParameters(Connection connection, String projectId, String version,
                                  List<ParameterClaim> claims) throws SQLException {
        if (claims == null || claims.isEmpty()) return;
        try (PreparedStatement statement = connection.prepareStatement("""
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
                if (!version.equals(claim.version())) {
                    throw new IllegalArgumentException("参数 Claim 版本与导入版本不一致: " + claim.version() + " != " + version);
                }
                statement.setString(1, claim.claimId());
                statement.setString(2, projectId);
                statement.setString(3, claim.version());
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
                statement.setString(20, claim.status() == null ? KnowledgeStatus.SUPPORTED.name() : claim.status().name());
                statement.setString(21, createdAt);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertDoubts(Connection connection, String projectId, String version,
                              List<DoubtClaim> claims) throws SQLException {
        if (claims == null || claims.isEmpty()) return;
        try (PreparedStatement statement = connection.prepareStatement("""
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
            for (DoubtClaim claim : claims) {
                statement.setString(1, claim.doubtId());
                statement.setString(2, projectId);
                statement.setString(3, version);
                statement.setString(4, claim.module());
                statement.setString(5, claim.question());
                statement.setString(6, claim.answer());
                statement.setString(7, claim.sourceSheet());
                statement.setInt(8, claim.rowNumber());
                statement.setString(9, claim.status().name());
                statement.setString(10, claim.owner());
                statement.setString(11, claim.severity());
                statement.setString(12, claim.dueDate());
                statement.setString(13, json(claim.proposedOptions()));
                statement.setString(14, claim.evidenceLocation());
                statement.setString(15, createdAt);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertTestCases(Connection connection, String projectId, String version,
                                 List<TestCaseClaim> claims) throws SQLException {
        if (claims == null || claims.isEmpty()) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into multi_source_test_case(
                  claim_id,project_id,version,test_case_id,title,module,preconditions,steps,expected_result,
                  covered_requirement_id,framework,file_path,test_method,evidence_location,status,created_at)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                on conflict(claim_id) do update set
                  title=excluded.title,module=excluded.module,steps=excluded.steps,
                  expected_result=excluded.expected_result,covered_requirement_id=excluded.covered_requirement_id,
                  framework=excluded.framework,test_method=excluded.test_method,evidence_location=excluded.evidence_location,
                  status=excluded.status
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
                statement.setString(15, claim.status() == null ? KnowledgeStatus.SUPPORTED.name() : claim.status().name());
                statement.setString(16, createdAt);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void insertTestResults(Connection connection, String projectId, String version,
                                   List<TestResultClaim> claims) throws SQLException {
        if (claims == null || claims.isEmpty()) return;
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into multi_source_test_result(
                  claim_id,project_id,version,test_run_id,test_case_id,execution_status,executed_at,
                  environment,actual_result,failure_message,evidence_location,status,created_at)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?)
                on conflict(claim_id) do update set
                  execution_status=excluded.execution_status,executed_at=excluded.executed_at,
                  environment=excluded.environment,actual_result=excluded.actual_result,
                  failure_message=excluded.failure_message,evidence_location=excluded.evidence_location,
                  status=excluded.status
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
                statement.setString(12, claim.status() == null ? KnowledgeStatus.SUPPORTED.name() : claim.status().name());
                statement.setString(13, createdAt);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void addColumnIfMissing(Statement statement, String table, String column, String definition) {
        try {
            statement.executeUpdate("alter table " + table + " add column " + column + " " + definition);
        } catch (SQLException exception) {
            if (!exception.getMessage().toLowerCase().contains("duplicate column")) {
                throw new IllegalStateException("无法迁移多源知识表 " + table, exception);
            }
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

    // ===== 0.9.3 统一资料目录（Document / DocumentVersion / Evidence）=====

    /** 注册逻辑资料：按 (projectId, sourceType, logicalName) upsert，返回 documentId。 */
    public String registerDocument(KnowledgeDocument document) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     insert into knowledge_document(
                       document_id,project_id,source_type,logical_name,original_name,storage_uri,authority,created_at)
                     values(?,?,?,?,?,?,?,?)
                     on conflict(project_id, source_type, logical_name) do update set
                       original_name=excluded.original_name, storage_uri=excluded.storage_uri
                     """)) {
            statement.setString(1, document.documentId());
            statement.setString(2, document.projectId());
            statement.setString(3, document.sourceType().name());
            statement.setString(4, document.logicalName());
            statement.setString(5, document.originalName());
            statement.setString(6, document.storageUri());
            statement.setString(7, document.authority().name());
            statement.setString(8, document.createdAt());
            statement.executeUpdate();
            return document.documentId();
        } catch (SQLException exception) {
            throw new IllegalStateException("注册资料失败", exception);
        }
    }

    /**
     * 幂等保存不可变资料版本：命中唯一键 (document_id, business_version, content_hash, parser_version, extraction_version)
     * 时返回已有版本，否则插入新版本。
     */
    public KnowledgeDocumentVersion upsertDocumentVersion(KnowledgeDocumentVersion version) {
        Optional<KnowledgeDocumentVersion> existing = findDocumentVersion(
                version.documentId(), version.businessVersion(), version.contentHash(),
                version.parserVersion(), version.extractionVersion());
        if (existing.isPresent()) {
            return existing.get();
        }
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     insert into knowledge_document_version(
                       document_version_id,document_id,project_id,business_version,content_hash,
                       parser_version,extraction_version,source_commit_sha,status,imported_at,published_at)
                     values(?,?,?,?,?,?,?,?,?,?,?)
                     """)) {
            statement.setString(1, version.documentVersionId());
            statement.setString(2, version.documentId());
            statement.setString(3, version.projectId());
            statement.setString(4, version.businessVersion());
            statement.setString(5, version.contentHash());
            statement.setString(6, version.parserVersion());
            statement.setString(7, version.extractionVersion());
            statement.setString(8, version.sourceCommitSha());
            statement.setString(9, version.status());
            statement.setString(10, version.importedAt());
            statement.setString(11, version.publishedAt());
            statement.executeUpdate();
            return version;
        } catch (SQLException exception) {
            throw new IllegalStateException("保存资料版本失败", exception);
        }
    }

    /** 按唯一键查找资料版本。 */
    public Optional<KnowledgeDocumentVersion> findDocumentVersion(String documentId, String businessVersion,
                                                                  String contentHash, String parserVersion,
                                                                  String extractionVersion) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     select document_version_id,document_id,project_id,business_version,content_hash,
                       parser_version,extraction_version,source_commit_sha,status,imported_at,published_at
                     from knowledge_document_version
                     where document_id=? and business_version=? and content_hash=?
                       and parser_version=? and extraction_version=?
                     """)) {
            statement.setString(1, documentId);
            statement.setString(2, businessVersion);
            statement.setString(3, contentHash);
            statement.setString(4, parserVersion);
            statement.setString(5, extractionVersion);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(documentVersion(rows)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("查询资料版本失败", exception);
        }
    }

    /** 幂等保存 Evidence：命中唯一键 (document_version_id, locator, excerpt_hash) 时返回已有 evidenceId。 */
    public String saveEvidence(KnowledgeEvidence evidence) {
        Optional<KnowledgeEvidence> existing = findEvidenceById(evidence.evidenceId());
        if (existing.isPresent()) {
            return existing.get().evidenceId();
        }
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     insert into knowledge_evidence(
                       evidence_id,document_version_id,project_id,source_type,locator,excerpt,excerpt_hash,
                       start_line,end_line,sheet_name,row_number,column_range,repository_id,commit_sha,symbol_name,created_at)
                     values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                     """)) {
            statement.setString(1, evidence.evidenceId());
            statement.setString(2, evidence.documentVersionId());
            statement.setString(3, evidence.projectId());
            statement.setString(4, evidence.sourceType().name());
            statement.setString(5, evidence.locator());
            statement.setString(6, evidence.excerpt());
            statement.setString(7, evidence.excerptHash());
            statement.setObject(8, evidence.startLine(), java.sql.Types.INTEGER);
            statement.setObject(9, evidence.endLine(), java.sql.Types.INTEGER);
            statement.setString(10, evidence.sheetName());
            statement.setObject(11, evidence.rowNumber(), java.sql.Types.INTEGER);
            statement.setString(12, evidence.columnRange());
            statement.setString(13, evidence.repositoryId());
            statement.setString(14, evidence.commitSha());
            statement.setString(15, evidence.symbolName());
            statement.setString(16, evidence.createdAt());
            statement.executeUpdate();
            return evidence.evidenceId();
        } catch (SQLException exception) {
            throw new IllegalStateException("保存 Evidence 失败", exception);
        }
    }

    /** 按 Evidence ID 查询。 */
    public Optional<KnowledgeEvidence> findEvidenceById(String evidenceId) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     select evidence_id,document_version_id,project_id,source_type,locator,excerpt,excerpt_hash,
                       start_line,end_line,sheet_name,row_number,column_range,repository_id,commit_sha,symbol_name,created_at
                     from knowledge_evidence where evidence_id=?
                     """)) {
            statement.setString(1, evidenceId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(evidence(rows)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("查询 Evidence 失败", exception);
        }
    }

    /** 按资料版本列出全部 Evidence。 */
    public List<KnowledgeEvidence> findEvidenceByDocumentVersion(String documentVersionId) {
        List<KnowledgeEvidence> result = new ArrayList<>();
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     select evidence_id,document_version_id,project_id,source_type,locator,excerpt,excerpt_hash,
                       start_line,end_line,sheet_name,row_number,column_range,repository_id,commit_sha,symbol_name,created_at
                     from knowledge_evidence where document_version_id=? order by row_number,locator
                     """)) {
            statement.setString(1, documentVersionId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(evidence(rows));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("查询版本 Evidence 失败", exception);
        }
        return result;
    }

    /**
     * 把一条现有来源 Claim 关联到 catalog 的 documentVersion 与 Evidence。
     * 仅更新对应业务表的可空关联列，不改变 Claim 内容与既有 API。
     *
     * @param sourceType        来源类型（PARAMETER_TABLE/DOUBT/TEST_CASE/TEST_RESULT）
     * @param claimId           Claim ID（对应表的 claim_id 或 doubt_id）
     * @param documentVersionId 资料版本 ID
     * @param evidenceId        结构化 Evidence ID
     */
    public void linkClaimToCatalog(String sourceType, String claimId,
                                   String documentVersionId, String evidenceId) {
        String table = switch (sourceType) {
            case "PARAMETER_TABLE" -> "multi_source_parameter";
            case "DOUBT" -> "multi_source_doubt";
            case "TEST_CASE" -> "multi_source_test_case";
            case "TEST_RESULT" -> "multi_source_test_result";
            default -> throw new IllegalArgumentException("不支持的来源类型关联: " + sourceType);
        };
        String keyColumn = "DOUBT".equals(sourceType) ? "doubt_id" : "claim_id";
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "update " + table + " set document_version_id=?, evidence_id=? where " + keyColumn + "=?")) {
            statement.setString(1, documentVersionId);
            statement.setString(2, evidenceId);
            statement.setString(3, claimId);
            int updated = statement.executeUpdate();
            if (updated == 0) {
                throw new IllegalArgumentException("未找到可关联的 Claim: " + claimId + " (" + sourceType + ")");
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("关联 Claim 到 catalog 失败", exception);
        }
    }

    /** 查询某条业务 Claim 在 catalog 中的可回查关联。 */
    public Optional<KnowledgeCatalogModels.CatalogReference> findCatalogReference(String sourceType, String claimId) {
        String table = switch (sourceType) {
            case "PARAMETER_TABLE" -> "multi_source_parameter";
            case "DOUBT" -> "multi_source_doubt";
            case "TEST_CASE" -> "multi_source_test_case";
            case "TEST_RESULT" -> "multi_source_test_result";
            default -> throw new IllegalArgumentException("不支持的来源类型关联: " + sourceType);
        };
        String keyColumn = "DOUBT".equals(sourceType) ? "doubt_id" : "claim_id";
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(
                     "select document_version_id, evidence_id from " + table + " where " + keyColumn + "=?")) {
            statement.setString(1, claimId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                String documentVersionId = rows.getString("document_version_id");
                String evidenceId = rows.getString("evidence_id");
                if (documentVersionId == null || evidenceId == null) {
                    return Optional.empty();
                }
                return Optional.of(new KnowledgeCatalogModels.CatalogReference(documentVersionId, evidenceId));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("查询 Claim catalog 关联失败", exception);
        }
    }

    private KnowledgeDocumentVersion documentVersion(ResultSet rows) throws SQLException {
        return new KnowledgeDocumentVersion(
                rows.getString("document_version_id"), rows.getString("document_id"),
                rows.getString("project_id"), rows.getString("business_version"),
                rows.getString("content_hash"), rows.getString("parser_version"),
                rows.getString("extraction_version"), rows.getString("source_commit_sha"),
                rows.getString("status"), rows.getString("imported_at"), rows.getString("published_at"));
    }

    private KnowledgeEvidence evidence(ResultSet rows) throws SQLException {
        return new KnowledgeEvidence(
                rows.getString("evidence_id"), rows.getString("document_version_id"),
                rows.getString("project_id"),
                com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType.valueOf(rows.getString("source_type")),
                rows.getString("locator"), rows.getString("excerpt"), rows.getString("excerpt_hash"),
                integerOrNull(rows, "start_line"), integerOrNull(rows, "end_line"),
                rows.getString("sheet_name"), integerOrNull(rows, "row_number"),
                rows.getString("column_range"), rows.getString("repository_id"),
                rows.getString("commit_sha"), rows.getString("symbol_name"),
                rows.getString("created_at"));
    }

    private Integer integerOrNull(ResultSet rows, String column) throws SQLException {
        int value = rows.getInt(column);
        return rows.wasNull() ? null : value;
    }
}