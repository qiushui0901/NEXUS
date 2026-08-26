package com.example.requirementrag.knowledge.multisource;

import com.example.requirementrag.conflict.KnowledgeConflictModels.Authority;
import com.example.requirementrag.conflict.KnowledgeConflictModels.SourceType;
import com.example.requirementrag.knowledge.multisource.KnowledgeGraphModels.KnowledgeEntity;
import com.example.requirementrag.knowledge.multisource.KnowledgeGraphModels.KnowledgeEntityRelation;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.ExtractionRun;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeClaimEvidence;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeClaimRecord;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeDocument;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeDocumentVersion;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeEvidence;
import com.example.requirementrag.knowledge.multisource.KnowledgeCatalogModels.KnowledgeRelation;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

            // 0.9.3 Phase B：统一 Claim 主表与 Claim-Evidence 关联
            statement.executeUpdate("""
                    create table if not exists knowledge_claim(
                      claim_id text primary key,
                      project_id text not null,
                      document_version_id text not null,
                      source_type text not null,
                      authority text not null,
                      fact_key text not null,
                      subject text not null,
                      predicate text not null,
                      object_value text,
                      value_type text,
                      unit text,
                      status text not null,
                      confidence real,
                      effective_from text,
                      effective_to text,
                      extraction_method text not null,
                      extraction_run_id text,
                      created_at text not null,
                      updated_at text not null,
                      foreign key(document_version_id) references knowledge_document_version(document_version_id),
                      unique(project_id, document_version_id, source_type, fact_key, object_value)
                    )
                    """);
            statement.executeUpdate("""
                    create table if not exists knowledge_claim_evidence(
                      claim_id text not null,
                      evidence_id text not null,
                      role text not null default 'SUPPORTS',
                      created_at text not null,
                      primary key(claim_id, evidence_id),
                      foreign key(claim_id) references knowledge_claim(claim_id),
                      foreign key(evidence_id) references knowledge_evidence(evidence_id)
                    )
                    """);
            statement.executeUpdate("create index if not exists idx_knowledge_claim_fact on knowledge_claim(project_id,document_version_id,fact_key)");

            // 0.9.3 Phase C：统一关系表 + 抽取运行审计表
            statement.executeUpdate("""
                    create table if not exists knowledge_relation(
                      relation_id text primary key,
                      project_id text not null,
                      version text not null,
                      source_claim_id text not null,
                      target_claim_id text not null,
                      relation_type text not null,
                      status text not null,
                      confidence real,
                      evidence_id text,
                      extraction_method text not null,
                      confirmation_method text,
                      confirmation_reason text,
                      created_at text not null,
                      updated_at text not null,
                      foreign key(source_claim_id) references knowledge_claim(claim_id),
                      foreign key(target_claim_id) references knowledge_claim(claim_id),
                      foreign key(evidence_id) references knowledge_evidence(evidence_id),
                      unique(project_id, version, source_claim_id, target_claim_id, relation_type)
                    )
                    """);
            statement.executeUpdate("""
                    create table if not exists knowledge_extraction_run(
                      extraction_run_id text primary key,
                      project_id text not null,
                      document_version_id text not null,
                      parser_name text not null,
                      parser_version text not null,
                      model_name text,
                      prompt_version text,
                      input_hash text not null,
                      output_hash text,
                      status text not null,
                      prompt_tokens integer,
                      completion_tokens integer,
                      error_message text,
                      started_at text not null,
                      finished_at text,
                      foreign key(document_version_id) references knowledge_document_version(document_version_id)
                    )
                    """);
            statement.executeUpdate("create index if not exists idx_knowledge_relation_scope on knowledge_relation(project_id,version)");
            statement.executeUpdate("""
                    create table if not exists knowledge_active_version(
                      project_id text not null,
                      business_version text not null,
                      document_version_id text not null,
                      status text not null,
                      published_at text not null,
                      updated_at text not null,
                      primary key(project_id, business_version)
                    )
                    """);
            statement.executeUpdate("""
                    create table if not exists knowledge_entity(
                      entity_id text primary key,
                      project_id text not null,
                      version text not null,
                      name text not null,
                      normalized_name text not null,
                      entity_type text not null,
                      source_type text not null,
                      summary text,
                      evidence_id text,
                      source_claim_ids text not null default '[]',
                      created_at text not null,
                      updated_at text not null
                    )
                    """);
            statement.executeUpdate("""
                    create table if not exists knowledge_entity_relation(
                      relation_id text primary key,
                      project_id text not null,
                      version text not null,
                      source_entity_id text not null,
                      target_entity_id text not null,
                      relation_type text not null,
                      status text not null,
                      confidence real,
                      extraction_method text not null,
                      evidence_ids text not null default '[]',
                      created_at text not null,
                      updated_at text not null
                    )
                    """);
            statement.executeUpdate("create index if not exists idx_knowledge_entity_scope on knowledge_entity(project_id,version)");
            statement.executeUpdate("create index if not exists idx_knowledge_entity_rel_scope on knowledge_entity_relation(project_id,version,relation_type)");

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

    /** 人工关闭存疑：更新答案与状态（Resolution Evidence 由对齐层 doubt_impact 记录）。 */
    public synchronized void updateDoubtResolution(String doubtId, String answer, String status) {
        try (Connection connection = open(); PreparedStatement statement = connection.prepareStatement(
                "update multi_source_doubt set answer=?, status=? where doubt_id=?")) {
            statement.setString(1, answer);
            statement.setString(2, status);
            statement.setString(3, doubtId);
            int updated = statement.executeUpdate();
            if (updated == 0) {
                throw new IllegalArgumentException("未找到可关闭的存疑: " + doubtId);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("关闭存疑失败", exception);
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

    // ===== 0.9.3 Phase B：统一 Claim 主表 =====

    /** 幂等保存统一 Claim：同 claim_id 存在时更新非空字段与 updated_at。 */
    public void saveClaim(KnowledgeClaimRecord claim) {
        try (Connection connection = open()) {
            insertClaim(connection, claim);
        } catch (SQLException exception) {
            throw new IllegalStateException("保存统一 Claim 失败", exception);
        }
    }

    /** 关联 Claim 与 Evidence（幂等）。 */
    public void linkClaimEvidence(String claimId, String evidenceId, String role) {
        try (Connection connection = open()) {
            linkEvidence(connection, claimId, evidenceId, role);
        } catch (SQLException exception) {
            throw new IllegalStateException("关联 Claim Evidence 失败", exception);
        }
    }

    /** 按 claimId 批量查询统一 Claim（向量命中水化用）。 */
    public List<KnowledgeClaimRecord> findClaimsByIds(java.util.Collection<String> claimIds) {
        if (claimIds == null || claimIds.isEmpty()) return List.of();
        List<String> ids = claimIds.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
        if (ids.isEmpty()) return List.of();
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) placeholders.append(',');
            placeholders.append('?');
        }
        List<KnowledgeClaimRecord> result = new ArrayList<>();
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     select claim_id,project_id,document_version_id,source_type,authority,fact_key,
                       subject,predicate,object_value,value_type,unit,status,confidence,
                       effective_from,effective_to,extraction_method,extraction_run_id,created_at,updated_at
                     from knowledge_claim where claim_id in (PLACEHOLDERS)
                     """.replace("PLACEHOLDERS", placeholders.toString()))) {
            for (int i = 0; i < ids.size(); i++) {
                statement.setString(i + 1, ids.get(i));
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(claim(rows));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("批量查询 Claim 失败", exception);
        }
        return result;
    }

    /** 按 claimId 查询统一 Claim。 */
    public Optional<KnowledgeClaimRecord> findClaimById(String claimId) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     select claim_id,project_id,document_version_id,source_type,authority,fact_key,
                       subject,predicate,object_value,value_type,unit,status,confidence,
                       effective_from,effective_to,extraction_method,extraction_run_id,created_at,updated_at
                     from knowledge_claim where claim_id=?
                     """)) {
            statement.setString(1, claimId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(claim(rows)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("查询统一 Claim 失败", exception);
        }
    }

    /** 按项目/资料版本/事实键查询统一 Claim（可命中多来源）。 */
    public List<KnowledgeClaimRecord> findClaimsByFactKey(String projectId, String documentVersionId, String factKey) {
        List<KnowledgeClaimRecord> result = new ArrayList<>();
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     select claim_id,project_id,document_version_id,source_type,authority,fact_key,
                       subject,predicate,object_value,value_type,unit,status,confidence,
                       effective_from,effective_to,extraction_method,extraction_run_id,created_at,updated_at
                     from knowledge_claim
                     where project_id=? and document_version_id=? and fact_key=?
                     order by source_type,claim_id
                     """)) {
            statement.setString(1, projectId);
            statement.setString(2, documentVersionId);
            statement.setString(3, factKey);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(claim(rows));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("按事实键查询 Claim 失败", exception);
        }
        return result;
    }

    /** 查询某条 Claim 关联的 Evidence ID 列表。 */
    public List<String> findEvidenceIdsByClaimId(String claimId) {
        List<String> result = new ArrayList<>();
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     select evidence_id from knowledge_claim_evidence
                     where claim_id=? order by evidence_id
                     """)) {
            statement.setString(1, claimId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(rows.getString("evidence_id"));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("查询 Claim Evidence 失败", exception);
        }
        return result;
    }

    /**
     * 把一次快照中的四类业务表行批量同步为统一 Claim。
     * 同一事务内：生成 Claim、关联 Evidence、并把业务表可空列回填 document_version_id/evidence_id。
     */
    public synchronized void syncSnapshotClaims(String projectId, String version, String documentVersionId,
                                                Map<String, String> evidenceIdByClaimId) {
        syncClaims(projectId, version, documentVersionId, evidenceIdByClaimId, Set.of());
    }

    /** 同步指定范围内 Claim 到统一主表：空集合表示该版本全部行。 */
    public synchronized void syncClaims(String projectId, String version, String documentVersionId,
                                        Map<String, String> evidenceIdByClaimId, Set<String> claimIds) {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try {
                syncTableClaims(connection, projectId, version, documentVersionId, evidenceIdByClaimId, claimIds,
                        "multi_source_parameter", "claim_id", "parameter", "value",
                        "normalized_value", "value_type", "unit", "status", "module",
                        SourceType.PARAMETER_TABLE, Authority.PRIMARY);
                syncTableClaims(connection, projectId, version, documentVersionId, evidenceIdByClaimId, claimIds,
                        "multi_source_doubt", "doubt_id", "module", "question",
                        "answer", null, null, "status", "module",
                        SourceType.DOUBT, Authority.PRIMARY);
                syncTableClaims(connection, projectId, version, documentVersionId, evidenceIdByClaimId, claimIds,
                        "multi_source_test_case", "claim_id", "title", "expectedResult",
                        "expected_result", null, null, "status", "module",
                        SourceType.TEST_CASE, Authority.SECONDARY);
                syncTableClaims(connection, projectId, version, documentVersionId, evidenceIdByClaimId, claimIds,
                        "multi_source_test_result", "claim_id", "test_case_id", "executionStatus",
                        "execution_status", null, null, "status", "test_case_id",
                        SourceType.TEST_RESULT, Authority.SECONDARY);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("同步快照 Claim 失败", exception);
        }
    }

    private void syncTableClaims(Connection connection, String projectId, String version,
                                 String documentVersionId, Map<String, String> evidenceIdByClaimId,
                                 Set<String> scope, String table, String keyColumn, String subjectColumn,
                                 String predicateLiteral, String objectColumn, String valueTypeColumn,
                                 String unitColumn, String statusColumn, String moduleColumn,
                                 SourceType sourceType, Authority authority) throws SQLException {
        String valueTypeSelect = valueTypeColumn == null ? "''" : valueTypeColumn;
        String unitSelect = unitColumn == null ? "''" : unitColumn;
        String scopeSql = "";
        if (scope != null && !scope.isEmpty()) {
            String placeholders = String.join(",", java.util.Collections.nCopies(scope.size(), "?"));
            scopeSql = " and " + keyColumn + " in (" + placeholders + ")";
        }
        String sql = "select " + keyColumn + "," + moduleColumn + "," + subjectColumn + "," + objectColumn + ","
                + valueTypeSelect + "," + unitSelect + "," + statusColumn
                + " from " + table + " where project_id=? and version=?" + scopeSql;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, projectId);
            statement.setString(index++, version);
            if (scope != null && !scope.isEmpty()) {
                for (String claimId : scope) {
                    statement.setString(index++, claimId);
                }
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String claimId = rows.getString(1);
                    String module = rows.getString(2);
                    String rawSubject = rows.getString(3);
                    String subject = (rawSubject != null && !rawSubject.isBlank())
                            ? rawSubject
                            : (module != null && !module.isBlank() ? module : claimId);
                    String objectValue = rows.getString(4);
                    String valueType = rows.getString(5);
                    String unit = rows.getString(6);
                    String status = rows.getString(7);
                    KnowledgeClaimRecord claim = new KnowledgeClaimRecord(
                            claimId, projectId, documentVersionId, sourceType, authority,
                            KnowledgeFactKeyGenerator.generate(projectId, version, module, subject, predicateLiteral),
                            subject, predicateLiteral, objectValue,
                            valueType, unit, fallbackStatus(status), null, null, null,
                            "RULE", null, null, null);
                    insertClaim(connection, claim);
                    String evidenceId = evidenceIdByClaimId == null ? null : evidenceIdByClaimId.get(claimId);
                    linkEvidence(connection, claimId, evidenceId, "SUPPORTS");
                    updateBusinessCatalogRef(connection, table, keyColumn, claimId, documentVersionId, evidenceId);
                }
            }
        }
    }

    private void insertClaim(Connection connection, KnowledgeClaimRecord claim) throws SQLException {
        // 先按 claim_id 更新；不存在则 INSERT OR IGNORE。
        // 同 project/documentVersion/sourceType/factKey/objectValue 的完全重复由唯一键静默去重。
        try (PreparedStatement update = connection.prepareStatement("""
                update knowledge_claim set
                  document_version_id=?, source_type=?, authority=?, fact_key=?,
                  subject=?, predicate=?, object_value=?, value_type=?, unit=?, status=?,
                  confidence=?, effective_from=?, effective_to=?, extraction_method=?,
                  extraction_run_id=?, updated_at=?
                where claim_id=?
                """)) {
            update.setString(1, claim.documentVersionId());
            update.setString(2, claim.sourceType().name());
            update.setString(3, claim.authority().name());
            update.setString(4, claim.factKey());
            update.setString(5, claim.subject());
            update.setString(6, claim.predicate());
            update.setString(7, claim.objectValue());
            update.setString(8, claim.valueType());
            update.setString(9, claim.unit());
            update.setString(10, claim.status());
            update.setObject(11, claim.confidence(), java.sql.Types.DOUBLE);
            update.setString(12, claim.effectiveFrom());
            update.setString(13, claim.effectiveTo());
            update.setString(14, claim.extractionMethod());
            update.setString(15, claim.extractionRunId());
            update.setString(16, claim.updatedAt());
            update.setString(17, claim.claimId());
            if (update.executeUpdate() > 0) {
                return;
            }
        }
        try (PreparedStatement insert = connection.prepareStatement("""
                insert or ignore into knowledge_claim(
                  claim_id,project_id,document_version_id,source_type,authority,fact_key,
                  subject,predicate,object_value,value_type,unit,status,confidence,
                  effective_from,effective_to,extraction_method,extraction_run_id,created_at,updated_at)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            insert.setString(1, claim.claimId());
            insert.setString(2, claim.projectId());
            insert.setString(3, claim.documentVersionId());
            insert.setString(4, claim.sourceType().name());
            insert.setString(5, claim.authority().name());
            insert.setString(6, claim.factKey());
            insert.setString(7, claim.subject());
            insert.setString(8, claim.predicate());
            insert.setString(9, claim.objectValue());
            insert.setString(10, claim.valueType());
            insert.setString(11, claim.unit());
            insert.setString(12, claim.status());
            insert.setObject(13, claim.confidence(), java.sql.Types.DOUBLE);
            insert.setString(14, claim.effectiveFrom());
            insert.setString(15, claim.effectiveTo());
            insert.setString(16, claim.extractionMethod());
            insert.setString(17, claim.extractionRunId());
            insert.setString(18, claim.createdAt());
            insert.setString(19, claim.updatedAt());
            insert.executeUpdate();
        }
    }

    private void linkEvidence(Connection connection, String claimId, String evidenceId, String role) throws SQLException {
        if (evidenceId == null || evidenceId.isBlank()) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                insert or ignore into knowledge_claim_evidence(claim_id,evidence_id,role,created_at)
                values(?,?,?,?)
                """)) {
            statement.setString(1, claimId);
            statement.setString(2, evidenceId);
            statement.setString(3, role == null || role.isBlank() ? "SUPPORTS" : role);
            statement.setString(4, Instant.now().toString());
            statement.executeUpdate();
        }
    }

    private void updateBusinessCatalogRef(Connection connection, String table, String keyColumn,
                                          String claimId, String documentVersionId, String evidenceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "update " + table + " set document_version_id=?, evidence_id=? where " + keyColumn + "=?")) {
            statement.setString(1, documentVersionId);
            statement.setString(2, evidenceId);
            statement.setString(3, claimId);
            statement.executeUpdate();
        }
    }

    private KnowledgeClaimRecord claim(ResultSet rows) throws SQLException {
        Double confidence = rows.getObject("confidence") == null ? null : rows.getDouble("confidence");
        return new KnowledgeClaimRecord(
                rows.getString("claim_id"), rows.getString("project_id"),
                rows.getString("document_version_id"),
                SourceType.valueOf(rows.getString("source_type")),
                Authority.valueOf(rows.getString("authority")),
                rows.getString("fact_key"), rows.getString("subject"),
                rows.getString("predicate"), rows.getString("object_value"),
                rows.getString("value_type"), rows.getString("unit"),
                rows.getString("status"), confidence,
                rows.getString("effective_from"), rows.getString("effective_to"),
                rows.getString("extraction_method"), rows.getString("extraction_run_id"),
                rows.getString("created_at"), rows.getString("updated_at"));
    }

    private String fallbackStatus(String status) {
        return status == null || status.isBlank() ? "SUPPORTED" : status;
    }

    // ===== 0.9.3 Phase C：统一关系 + 抽取运行审计 =====

    /** 幂等保存统一关系：命中唯一键时更新状态/置信度/审计字段。 */
    public void saveRelation(KnowledgeRelation relation) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     insert into knowledge_relation(
                       relation_id,project_id,version,source_claim_id,target_claim_id,relation_type,
                       status,confidence,evidence_id,extraction_method,confirmation_method,confirmation_reason,
                       created_at,updated_at)
                     values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                     on conflict(project_id, version, source_claim_id, target_claim_id, relation_type) do update set
                       relation_id=excluded.relation_id,
                       status=excluded.status, confidence=excluded.confidence, evidence_id=excluded.evidence_id,
                       extraction_method=excluded.extraction_method, confirmation_method=excluded.confirmation_method,
                       confirmation_reason=excluded.confirmation_reason, updated_at=excluded.updated_at
                     """)) {
            statement.setString(1, relation.relationId());
            statement.setString(2, relation.projectId());
            statement.setString(3, relation.version());
            statement.setString(4, relation.sourceClaimId());
            statement.setString(5, relation.targetClaimId());
            statement.setString(6, relation.relationType());
            statement.setString(7, relation.status());
            statement.setObject(8, relation.confidence(), java.sql.Types.DOUBLE);
            statement.setString(9, relation.evidenceId());
            statement.setString(10, relation.extractionMethod());
            statement.setString(11, relation.confirmationMethod());
            statement.setString(12, relation.confirmationReason());
            statement.setString(13, relation.createdAt());
            statement.setString(14, relation.updatedAt());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("保存统一关系失败", exception);
        }
    }

    /** 查询当前页命中 Claim 的一跳关系：source 或 target 任一在页内。 */
    public List<KnowledgeRelation> findRelationsForClaims(String projectId, String version, Set<String> claimIds) {
        if (claimIds == null || claimIds.isEmpty()) {
            return List.of();
        }
        List<KnowledgeRelation> result = new ArrayList<>();
        String placeholders = String.join(",", java.util.Collections.nCopies(claimIds.size(), "?"));
        String sql = "select relation_id,project_id,version,source_claim_id,target_claim_id,relation_type," +
                "status,confidence,evidence_id,extraction_method,confirmation_method,confirmation_reason," +
                "created_at,updated_at from knowledge_relation where project_id=? and version=?" +
                " and (source_claim_id in (" + placeholders + ") or target_claim_id in (" + placeholders + "))" +
                " order by source_claim_id,target_claim_id,relation_type";
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, projectId);
            statement.setString(index++, version);
            for (String id : claimIds) {
                statement.setString(index++, id);
            }
            for (String id : claimIds) {
                statement.setString(index++, id);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(relation(rows));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("查询页内关系失败", exception);
        }
        return result;
    }

    /** 开始抽取运行审计记录。 */
    public void startExtractionRun(ExtractionRun run) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     insert into knowledge_extraction_run(
                       extraction_run_id,project_id,document_version_id,parser_name,parser_version,
                       model_name,prompt_version,input_hash,output_hash,status,prompt_tokens,
                       completion_tokens,error_message,started_at,finished_at)
                     values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                     """)) {
            statement.setString(1, run.extractionRunId());
            statement.setString(2, run.projectId());
            statement.setString(3, run.documentVersionId());
            statement.setString(4, run.parserName());
            statement.setString(5, run.parserVersion());
            statement.setString(6, run.modelName());
            statement.setString(7, run.promptVersion());
            statement.setString(8, run.inputHash());
            statement.setString(9, run.outputHash());
            statement.setString(10, run.status());
            statement.setObject(11, run.promptTokens(), java.sql.Types.INTEGER);
            statement.setObject(12, run.completionTokens(), java.sql.Types.INTEGER);
            statement.setString(13, run.errorMessage());
            statement.setString(14, run.startedAt());
            statement.setString(15, run.finishedAt());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("开始抽取运行审计失败", exception);
        }
    }

    /** 结束抽取运行，回写状态、输出 hash 与 token。 */
    public void finishExtractionRun(String extractionRunId, String status, String outputHash,
                                    Integer promptTokens, Integer completionTokens,
                                    String errorMessage, String finishedAt) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     update knowledge_extraction_run
                     set status=?, output_hash=?, prompt_tokens=?, completion_tokens=?, error_message=?, finished_at=?
                     where extraction_run_id=?
                     """)) {
            statement.setString(1, status);
            statement.setString(2, outputHash);
            statement.setObject(3, promptTokens, java.sql.Types.INTEGER);
            statement.setObject(4, completionTokens, java.sql.Types.INTEGER);
            statement.setString(5, errorMessage);
            statement.setString(6, finishedAt);
            statement.setString(7, extractionRunId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("结束抽取运行审计失败", exception);
        }
    }

    /** 查询抽取运行记录。 */
    public Optional<ExtractionRun> findExtractionRun(String extractionRunId) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     select extraction_run_id,project_id,document_version_id,parser_name,parser_version,
                       model_name,prompt_version,input_hash,output_hash,status,prompt_tokens,
                       completion_tokens,error_message,started_at,finished_at
                     from knowledge_extraction_run where extraction_run_id=?
                     """)) {
            statement.setString(1, extractionRunId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(extractionRun(rows)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("查询抽取运行记录失败", exception);
        }
    }

    private ExtractionRun extractionRun(ResultSet rows) throws SQLException {
        Integer promptTokens = rows.getObject("prompt_tokens") == null ? null : rows.getInt("prompt_tokens");
        Integer completionTokens = rows.getObject("completion_tokens") == null ? null : rows.getInt("completion_tokens");
        return new ExtractionRun(
                rows.getString("extraction_run_id"), rows.getString("project_id"),
                rows.getString("document_version_id"), rows.getString("parser_name"),
                rows.getString("parser_version"), rows.getString("model_name"),
                rows.getString("prompt_version"), rows.getString("input_hash"),
                rows.getString("output_hash"), rows.getString("status"),
                promptTokens, completionTokens, rows.getString("error_message"),
                rows.getString("started_at"), rows.getString("finished_at"));
    }

    /** 人工审核关系：更新状态、确认方式与原因。 */
    public void reviewRelation(String relationId, String status, String confirmationMethod, String reason) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     update knowledge_relation
                     set status=?, confirmation_method=?, confirmation_reason=?, updated_at=?
                     where relation_id=?
                     """)) {
            statement.setString(1, status);
            statement.setString(2, confirmationMethod);
            statement.setString(3, reason);
            statement.setString(4, Instant.now().toString());
            statement.setString(5, relationId);
            int updated = statement.executeUpdate();
            if (updated == 0) {
                throw new IllegalArgumentException("未找到关系: " + relationId);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("审核关系失败", exception);
        }
    }

    private KnowledgeRelation relation(ResultSet rows) throws SQLException {
        Double confidence = rows.getObject("confidence") == null ? null : rows.getDouble("confidence");
        return new KnowledgeRelation(
                rows.getString("relation_id"), rows.getString("project_id"), rows.getString("version"),
                rows.getString("source_claim_id"), rows.getString("target_claim_id"),
                rows.getString("relation_type"), rows.getString("status"), confidence,
                rows.getString("evidence_id"), rows.getString("extraction_method"),
                rows.getString("confirmation_method"), rows.getString("confirmation_reason"),
                rows.getString("created_at"), rows.getString("updated_at"));
    }

    // ===== 0.9.3 Phase D：发布目录（active document-version manifest）=====

    /** 发布某业务版本的 active document-version：主库 manifest 更新为 PUBLISHED。 */
    public void publishDocumentVersion(String projectId, String businessVersion, String documentVersionId) {
        String now = Instant.now().toString();
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     insert into knowledge_active_version(project_id,business_version,document_version_id,status,published_at,updated_at)
                     values(?,?,?,?,?,?)
                     on conflict(project_id,business_version) do update set
                       document_version_id=excluded.document_version_id,
                       status=excluded.status, published_at=excluded.published_at, updated_at=excluded.updated_at
                     """)) {
            statement.setString(1, projectId);
            statement.setString(2, businessVersion);
            statement.setString(3, documentVersionId);
            statement.setString(4, "PUBLISHED");
            statement.setString(5, now);
            statement.setString(6, now);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("发布文档版本失败", exception);
        }
    }

    /** 回滚 active manifest 到目标版本。 */
    public void rollbackActiveVersion(String projectId, String businessVersion, String documentVersionId) {
        String now = Instant.now().toString();
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     update knowledge_active_version
                     set document_version_id=?, status=?, updated_at=?
                     where project_id=? and business_version=?
                     """)) {
            statement.setString(1, documentVersionId);
            statement.setString(2, "ROLLED_BACK");
            statement.setString(3, now);
            statement.setString(4, projectId);
            statement.setString(5, businessVersion);
            int updated = statement.executeUpdate();
            if (updated == 0) {
                throw new IllegalArgumentException("未找到可回滚的 active version: "
                        + projectId + "|" + businessVersion);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("回滚文档版本失败", exception);
        }
    }

    /** 查询某业务版本的 active document-version。 */
    public Optional<String> activeDocumentVersion(String projectId, String businessVersion) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     select document_version_id from knowledge_active_version
                     where project_id=? and business_version=?
                     """)) {
            statement.setString(1, projectId);
            statement.setString(2, businessVersion);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(rows.getString("document_version_id")) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("查询 active version 失败", exception);
        }
    }

    // ===== 0.9.3 跨源总实体关系图 =====

    public void saveEntity(KnowledgeEntity entity) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     insert into knowledge_entity(
                       entity_id,project_id,version,name,normalized_name,entity_type,source_type,
                       summary,evidence_id,source_claim_ids,created_at,updated_at)
                     values(?,?,?,?,?,?,?,?,?,?,?,?)
                     on conflict(entity_id) do update set
                       name=excluded.name, normalized_name=excluded.normalized_name,
                       entity_type=excluded.entity_type, source_type=excluded.source_type,
                       summary=excluded.summary, evidence_id=excluded.evidence_id,
                       source_claim_ids=excluded.source_claim_ids, updated_at=excluded.updated_at
                     """)) {
            statement.setString(1, entity.entityId());
            statement.setString(2, entity.projectId());
            statement.setString(3, entity.version());
            statement.setString(4, entity.name());
            statement.setString(5, entity.normalizedName());
            statement.setString(6, entity.entityType());
            statement.setString(7, entity.sourceType().name());
            statement.setString(8, entity.summary());
            statement.setString(9, entity.evidenceId());
            statement.setString(10, json(entity.sourceClaimIds()));
            statement.setString(11, entity.createdAt());
            statement.setString(12, entity.updatedAt());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("保存实体失败", exception);
        }
    }

    public void saveEntityRelation(KnowledgeEntityRelation relation) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     insert into knowledge_entity_relation(
                       relation_id,project_id,version,source_entity_id,target_entity_id,relation_type,
                       status,confidence,extraction_method,evidence_ids,created_at,updated_at)
                     values(?,?,?,?,?,?,?,?,?,?,?,?)
                     on conflict(relation_id) do update set
                       status=excluded.status, confidence=excluded.confidence,
                       extraction_method=excluded.extraction_method, evidence_ids=excluded.evidence_ids,
                       updated_at=excluded.updated_at
                     """)) {
            statement.setString(1, relation.relationId());
            statement.setString(2, relation.projectId());
            statement.setString(3, relation.version());
            statement.setString(4, relation.sourceEntityId());
            statement.setString(5, relation.targetEntityId());
            statement.setString(6, relation.relationType());
            statement.setString(7, relation.status());
            statement.setObject(8, relation.confidence(), java.sql.Types.DOUBLE);
            statement.setString(9, relation.extractionMethod());
            statement.setString(10, json(relation.evidenceIds()));
            statement.setString(11, relation.createdAt());
            statement.setString(12, relation.updatedAt());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("保存实体关系失败", exception);
        }
    }

    public List<KnowledgeEntity> findEntities(String projectId, String version) {
        List<KnowledgeEntity> result = new ArrayList<>();
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     select entity_id,project_id,version,name,normalized_name,entity_type,source_type,
                       summary,evidence_id,source_claim_ids,created_at,updated_at
                     from knowledge_entity where project_id=? and version=? order by name
                     """)) {
            statement.setString(1, projectId);
            statement.setString(2, version);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(entity(rows));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("查询实体失败", exception);
        }
        return result;
    }

    public List<KnowledgeEntityRelation> findEntityRelations(String projectId, String version) {
        List<KnowledgeEntityRelation> result = new ArrayList<>();
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     select relation_id,project_id,version,source_entity_id,target_entity_id,relation_type,
                       status,confidence,extraction_method,evidence_ids,created_at,updated_at
                     from knowledge_entity_relation where project_id=? and version=? order by relation_type,source_entity_id
                     """)) {
            statement.setString(1, projectId);
            statement.setString(2, version);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(entityRelation(rows));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("查询实体关系失败", exception);
        }
        return result;
    }

    /** 清空项目/版本的总实体关系图（幂等重建用）。 */
    public void deleteGraph(String projectId, String version) {
        try (Connection connection = open();
             PreparedStatement relations = connection.prepareStatement(
                     "delete from knowledge_entity_relation where project_id=? and version=?");
             PreparedStatement entities = connection.prepareStatement(
                     "delete from knowledge_entity where project_id=? and version=?")) {
            relations.setString(1, projectId);
            relations.setString(2, version);
            relations.executeUpdate();
            entities.setString(1, projectId);
            entities.setString(2, version);
            entities.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("清空实体关系图失败", exception);
        }
    }

    /** 按项目/业务版本列出全部统一 Claim（用于图构建聚合）。 */
    public List<KnowledgeClaimRecord> findClaimsByProjectVersion(String projectId, String version) {
        List<KnowledgeClaimRecord> result = new ArrayList<>();
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     select claim_id,project_id,document_version_id,source_type,authority,fact_key,
                       subject,predicate,object_value,value_type,unit,status,confidence,
                       effective_from,effective_to,extraction_method,extraction_run_id,created_at,updated_at
                     from knowledge_claim where project_id=? order by source_type,subject
                     """)) {
            statement.setString(1, projectId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(claim(rows));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("查询统一 Claim 列表失败", exception);
        }
        return result;
    }

    private KnowledgeEntity entity(ResultSet rows) throws SQLException {
        return new KnowledgeEntity(
                rows.getString("entity_id"), rows.getString("project_id"), rows.getString("version"),
                rows.getString("name"), rows.getString("normalized_name"), rows.getString("entity_type"),
                SourceType.valueOf(rows.getString("source_type")), rows.getString("summary"),
                rows.getString("evidence_id"), list(rows.getString("source_claim_ids")),
                rows.getString("created_at"), rows.getString("updated_at"));
    }

    private KnowledgeEntityRelation entityRelation(ResultSet rows) throws SQLException {
        Double confidence = rows.getObject("confidence") == null ? null : rows.getDouble("confidence");
        return new KnowledgeEntityRelation(
                rows.getString("relation_id"), rows.getString("project_id"), rows.getString("version"),
                rows.getString("source_entity_id"), rows.getString("target_entity_id"),
                rows.getString("relation_type"), rows.getString("status"), confidence,
                rows.getString("extraction_method"), list(rows.getString("evidence_ids")),
                rows.getString("created_at"), rows.getString("updated_at"));
    }

    /** 按资料版本 ID 查询。 */
    public Optional<KnowledgeDocumentVersion> findDocumentVersionById(String documentVersionId) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     select document_version_id,document_id,project_id,business_version,content_hash,
                       parser_version,extraction_version,source_commit_sha,status,imported_at,published_at
                     from knowledge_document_version where document_version_id=?
                     """)) {
            statement.setString(1, documentVersionId);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(documentVersion(rows)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("查询资料版本失败", exception);
        }
    }

    /** 按资料 ID 查询。 */
    public Optional<KnowledgeCatalogModels.KnowledgeDocument> findDocumentById(String documentId) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     select document_id,project_id,source_type,logical_name,original_name,storage_uri,authority,created_at
                     from knowledge_document where document_id=?
                     """)) {
            statement.setString(1, documentId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                return Optional.of(new KnowledgeCatalogModels.KnowledgeDocument(
                        rows.getString("document_id"), rows.getString("project_id"),
                        SourceType.valueOf(rows.getString("source_type")),
                        rows.getString("logical_name"), rows.getString("original_name"),
                        rows.getString("storage_uri"), Authority.valueOf(rows.getString("authority")),
                        rows.getString("created_at")));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("查询资料失败", exception);
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