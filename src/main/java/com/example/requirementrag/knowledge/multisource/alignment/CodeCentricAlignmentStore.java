package com.example.requirementrag.knowledge.multisource.alignment;

import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.AlignmentRelation;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.BusinessConcept;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.ConceptAlias;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.ConceptMember;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.DoubtImpact;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.DriftItem;
import com.example.requirementrag.knowledge.multisource.alignment.CodeCentricModels.VersionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

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
import java.util.List;
import java.util.Optional;

/**
 * 代码事实基线驱动的跨源对齐 SQLite 存储（改进方案 Phase 1-4）。
 *
 * <p>与多源知识库共用同一 SQLite 文件，独立维护对齐层数据：
 * 版本上下文、业务概念、概念别名/成员、对齐关系与漂移结论。
 * 来源事实仍由 {@code MultiSourceKnowledgeStore} 主表持有，本层只做跨源映射。
 */
@Component
public class CodeCentricAlignmentStore {
    private final String jdbcUrl;

    /** Spring 默认数据库路径（与多源知识库一致）。 */
    @Autowired
    public CodeCentricAlignmentStore() {
        this("data/multi-source-knowledge.db");
    }

    public CodeCentricAlignmentStore(String databasePath) {
        try {
            Path database = Path.of(databasePath).toAbsolutePath().normalize();
            if (database.getParent() != null) Files.createDirectories(database.getParent());
            this.jdbcUrl = "jdbc:sqlite:" + database;
            initialize();
        } catch (IOException exception) {
            throw new IllegalStateException("无法初始化跨源对齐库目录", exception);
        }
    }

    private void initialize() {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            // 对齐层是派生数据（可重建），旧 schema 直接重建，避免旧唯一约束阻止新作用域隔离。
            dropTableIfOldSchema(statement, "version_context",
                    "unique(project_id, business_version, environment, repository_id, commit_sha)");
            dropTableIfOldSchema(statement, "business_concept_member",
                    "unique(project_id, concept_id, source_type, external_id, business_version)");
            dropTableIfOldSchema(statement, "alignment_relation",
                    "unique(project_id, version, version_context_id, source_claim_id");
            dropTableIfOldSchema(statement, "drift_item",
                    "unique(project_id, version, version_context_id, concept_id, drift_type)");
            statement.executeUpdate("""
                    create table if not exists version_context(
                      context_id text primary key,
                      project_id text not null,
                      business_version text not null,
                      repository_id text,
                      commit_sha text,
                      environment text not null default 'default',
                      status text not null default 'ACTIVE',
                      created_at text not null,
                      updated_at text not null,
                      unique(project_id, business_version, environment, repository_id, commit_sha)
                    )
                    """);
            statement.executeUpdate("""
                    create table if not exists business_concept(
                      concept_id text primary key,
                      project_id text not null,
                      canonical_key text not null,
                      display_name text not null,
                      concept_type text not null default 'CONCEPT',
                      module text,
                      description text,
                      status text not null default 'ACTIVE',
                      created_at text not null,
                      updated_at text not null,
                      unique(project_id, canonical_key)
                    )
                    """);
            statement.executeUpdate("""
                    create table if not exists business_concept_alias(
                      alias_id text primary key,
                      project_id text not null,
                      concept_id text not null,
                      alias text not null,
                      source_type text,
                      normalization_method text not null default 'NORMALIZED_NAME',
                      confidence real,
                      created_at text not null,
                      unique(project_id, concept_id, alias, source_type),
                      foreign key(concept_id) references business_concept(concept_id)
                    )
                    """);
            statement.executeUpdate("""
                    create table if not exists business_concept_member(
                      member_id text primary key,
                      project_id text not null,
                      concept_id text not null,
                      claim_id text,
                      source_type text not null,
                      truth_role text not null,
                      external_id text,
                      display_name text not null,
                      repository_id text,
                      commit_sha text,
                      evidence_id text,
                      business_version text not null default '',
                      version_context_id text,
                      created_at text not null,
                      unique(project_id, concept_id, source_type, external_id, business_version),
                      foreign key(concept_id) references business_concept(concept_id)
                    )
                    """);
            statement.executeUpdate("""
                    create table if not exists alignment_relation(
                      relation_id text primary key,
                      project_id text not null,
                      version text not null,
                      version_context_id text not null,
                      source_claim_id text,
                      source_external_id text,
                      source_type text not null,
                      target_claim_id text,
                      target_external_id text,
                      target_type text not null,
                      relation_type text not null,
                      match_method text not null,
                      status text not null default 'RULE_CONFIRMED',
                      confidence real,
                      evidence_id text,
                      source_version_context_id text,
                      target_version_context_id text,
                      detail text,
                      created_at text not null,
                      updated_at text not null,
                      unique(project_id, version, version_context_id, source_claim_id, source_external_id,
                             source_type, target_claim_id, target_external_id, target_type, relation_type)
                    )
                    """);
            statement.executeUpdate("""
                    create table if not exists drift_item(
                      drift_id text primary key,
                      project_id text not null,
                      version text not null,
                      version_context_id text not null,
                      concept_id text not null,
                      concept_key text not null,
                      drift_type text not null,
                      severity text not null default 'WARNING',
                      truth_role text,
                      source_claim_id text,
                      target_claim_id text,
                      source_value text,
                      target_value text,
                      detail text,
                      status text not null default 'OPEN',
                      created_at text not null,
                      updated_at text not null,
                      unique(project_id, version, version_context_id, concept_id, drift_type)
                    )
                    """);
            statement.executeUpdate("""
                    create table if not exists doubt_impact(
                      impact_id text primary key,
                      project_id text not null,
                      version text not null,
                      version_context_id text not null,
                      doubt_id text not null,
                      question text,
                      concept_id text not null,
                      concept_key text,
                      target_type text not null,
                      target_claim_id text,
                      target_external_id text,
                      target_name text not null,
                      severity text,
                      owner text,
                      due_date text,
                      status text not null default 'OPEN',
                      resolution_evidence_id text,
                      resolution_conclusion text,
                      created_at text not null,
                      resolved_at text
                    )
                    """);
            statement.executeUpdate("create index if not exists idx_concept_scope on business_concept(project_id)");
            statement.executeUpdate("create index if not exists idx_concept_member_scope on business_concept_member(project_id,concept_id)");
            statement.executeUpdate("create index if not exists idx_concept_member_external on business_concept_member(project_id,source_type,external_id)");
            statement.executeUpdate("create index if not exists idx_alignment_relation_scope on alignment_relation(project_id,version,relation_type)");
            statement.executeUpdate("""
                    create unique index if not exists ux_alignment_relation_scope on alignment_relation(
                      project_id, version, version_context_id, ifnull(source_claim_id,''),
                      ifnull(source_external_id,''), source_type, ifnull(target_claim_id,''),
                      ifnull(target_external_id,''), target_type, relation_type)
                    """);
            statement.executeUpdate("create index if not exists idx_drift_scope on drift_item(project_id,version,drift_type)");
            statement.executeUpdate("""
                    create unique index if not exists ux_doubt_impact_scope on doubt_impact(
                      project_id, version, version_context_id, doubt_id, target_type,
                      ifnull(target_claim_id,''), ifnull(target_external_id,''))
                    """);
            statement.executeUpdate("create index if not exists idx_doubt_impact_scope on doubt_impact(project_id,version,status)");
            addColumnIfMissing(statement, "business_concept_member", "business_version", "text not null default ''");
            addColumnIfMissing(statement, "business_concept_member", "version_context_id", "text");
            addColumnIfMissing(statement, "alignment_relation", "version_context_id", "text not null default ''");
            addColumnIfMissing(statement, "drift_item", "version_context_id", "text not null default ''");
        } catch (SQLException exception) {
            throw new IllegalStateException("初始化跨源对齐库失败", exception);
        }
    }

    // ===== VersionContext =====

    public String upsertVersionContext(VersionContext context) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     insert into version_context(context_id,project_id,business_version,repository_id,commit_sha,
                       environment,status,created_at,updated_at)
                     values(?,?,?,?,?,?,?,?,?)
                     on conflict(project_id, business_version, environment, repository_id, commit_sha) do update set
                       status=excluded.status, updated_at=excluded.updated_at
                     """)) {
            statement.setString(1, context.contextId());
            statement.setString(2, context.projectId());
            statement.setString(3, context.businessVersion());
            statement.setString(4, context.repositoryId());
            statement.setString(5, context.commitSha());
            statement.setString(6, context.environment());
            statement.setString(7, context.status());
            statement.setString(8, context.createdAt());
            statement.setString(9, context.updatedAt());
            statement.executeUpdate();
            return context.contextId();
        } catch (SQLException exception) {
            throw new IllegalStateException("保存版本上下文失败", exception);
        }
    }

    public Optional<VersionContext> findVersionContext(String projectId, String businessVersion, String environment) {
        String env = environment == null || environment.isBlank() ? "default" : environment;
        return queryOne("""
                select context_id,project_id,business_version,repository_id,commit_sha,environment,status,
                  created_at,updated_at from version_context
                where project_id=? and business_version=? and environment=?
                order by updated_at desc limit 1
                """, projectId, businessVersion, env);
    }

    public List<VersionContext> listVersionContexts(String projectId, String businessVersion) {
        return queryAll("""
                select context_id,project_id,business_version,repository_id,commit_sha,environment,status,
                  created_at,updated_at from version_context
                where project_id=? and business_version=?
                order by updated_at desc
                """, projectId, businessVersion);
    }

    // ===== BusinessConcept / Alias / Member =====

    public String upsertConcept(BusinessConcept concept) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     insert into business_concept(concept_id,project_id,canonical_key,display_name,concept_type,
                       module,description,status,created_at,updated_at)
                     values(?,?,?,?,?,?,?,?,?,?)
                     on conflict(project_id, canonical_key) do update set
                       display_name=excluded.display_name, concept_type=excluded.concept_type,
                       module=excluded.module, description=excluded.description,
                       status=excluded.status, updated_at=excluded.updated_at
                     """)) {
            statement.setString(1, concept.conceptId());
            statement.setString(2, concept.projectId());
            statement.setString(3, concept.canonicalKey());
            statement.setString(4, concept.displayName());
            statement.setString(5, concept.conceptType());
            statement.setString(6, concept.module());
            statement.setString(7, concept.description());
            statement.setString(8, concept.status());
            statement.setString(9, concept.createdAt());
            statement.setString(10, concept.updatedAt());
            statement.executeUpdate();
            return concept.conceptId();
        } catch (SQLException exception) {
            throw new IllegalStateException("保存业务概念失败", exception);
        }
    }

    public Optional<BusinessConcept> findConceptByKey(String projectId, String canonicalKey) {
        return queryOne("""
                select concept_id,project_id,canonical_key,display_name,concept_type,module,description,status,
                  created_at,updated_at from business_concept where project_id=? and canonical_key=?
                """, projectId, canonicalKey);
    }

    public List<BusinessConcept> findConcepts(String projectId) {
        return queryAll("""
                select concept_id,project_id,canonical_key,display_name,concept_type,module,description,status,
                  created_at,updated_at from business_concept where project_id=? order by canonical_key
                """, projectId);
    }

    public String upsertAlias(ConceptAlias alias) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     insert into business_concept_alias(alias_id,project_id,concept_id,alias,source_type,
                       normalization_method,confidence,created_at)
                     values(?,?,?,?,?,?,?,?)
                     on conflict(project_id, concept_id, alias, source_type) do update set
                       normalization_method=excluded.normalization_method, confidence=excluded.confidence
                     """)) {
            statement.setString(1, alias.aliasId());
            statement.setString(2, alias.projectId());
            statement.setString(3, alias.conceptId());
            statement.setString(4, alias.alias());
            statement.setString(5, alias.sourceType());
            statement.setString(6, alias.normalizationMethod());
            statement.setObject(7, alias.confidence(), java.sql.Types.DOUBLE);
            statement.setString(8, alias.createdAt());
            statement.executeUpdate();
            return alias.aliasId();
        } catch (SQLException exception) {
            throw new IllegalStateException("保存概念别名失败", exception);
        }
    }

    public List<ConceptAlias> findAliases(String projectId, String conceptId) {
        return queryAll("""
                select alias_id,project_id,concept_id,alias,source_type,normalization_method,confidence,created_at
                from business_concept_alias where project_id=? and concept_id=? order by alias
                """, projectId, conceptId);
    }

    public String upsertMember(ConceptMember member) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     insert into business_concept_member(member_id,project_id,concept_id,claim_id,source_type,
                       truth_role,external_id,display_name,repository_id,commit_sha,evidence_id,
                       business_version,version_context_id,created_at)
                     values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                     on conflict(project_id, concept_id, source_type, external_id, business_version) do update set
                       claim_id=excluded.claim_id, truth_role=excluded.truth_role,
                       display_name=excluded.display_name, repository_id=excluded.repository_id,
                       commit_sha=excluded.commit_sha, evidence_id=excluded.evidence_id,
                       version_context_id=excluded.version_context_id
                     """)) {
            statement.setString(1, member.memberId());
            statement.setString(2, member.projectId());
            statement.setString(3, member.conceptId());
            statement.setString(4, member.claimId());
            statement.setString(5, member.sourceType());
            statement.setString(6, member.truthRole());
            statement.setString(7, member.externalId());
            statement.setString(8, member.displayName());
            statement.setString(9, member.repositoryId());
            statement.setString(10, member.commitSha());
            statement.setString(11, member.evidenceId());
            statement.setString(12, member.businessVersion());
            statement.setString(13, member.versionContextId());
            statement.setString(14, member.createdAt());
            statement.executeUpdate();
            return member.memberId();
        } catch (SQLException exception) {
            throw new IllegalStateException("保存概念成员失败", exception);
        }
    }

        /** 批量保存概念成员（单事务，供大规模重建使用）。 */
    public int upsertMembers(List<ConceptMember> members) {
        if (members == null || members.isEmpty()) return 0;
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                     insert into business_concept_member(member_id,project_id,concept_id,claim_id,source_type,
                       truth_role,external_id,display_name,repository_id,commit_sha,evidence_id,
                       business_version,version_context_id,created_at)
                     values(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                     on conflict(project_id, concept_id, source_type, external_id, business_version) do update set
                       claim_id=excluded.claim_id, truth_role=excluded.truth_role,
                       display_name=excluded.display_name, repository_id=excluded.repository_id,
                       commit_sha=excluded.commit_sha, evidence_id=excluded.evidence_id,
                       version_context_id=excluded.version_context_id
                     """)) {
                for (ConceptMember member : members) {
                    statement.setString(1, member.memberId());
                    statement.setString(2, member.projectId());
                    statement.setString(3, member.conceptId());
                    statement.setString(4, member.claimId());
                    statement.setString(5, member.sourceType());
                    statement.setString(6, member.truthRole());
                    statement.setString(7, member.externalId());
                    statement.setString(8, member.displayName());
                    statement.setString(9, member.repositoryId());
                    statement.setString(10, member.commitSha());
                    statement.setString(11, member.evidenceId());
                    statement.setString(12, member.businessVersion());
                    statement.setString(13, member.versionContextId());
                    statement.setString(14, member.createdAt());
                    statement.addBatch();
                }
                int[] results = statement.executeBatch();
                connection.commit();
                return results.length;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("批量保存概念成员失败", exception);
        }
    }

    /** 按业务版本查询概念成员；businessVersion 为空时不过滤版本。 */
    public List<ConceptMember> findMembers(String projectId, String conceptId, String businessVersion) {
        if (businessVersion == null || businessVersion.isBlank()) {
            return queryAll("""
                    select member_id,project_id,concept_id,claim_id,source_type,truth_role,external_id,display_name,
                      repository_id,commit_sha,evidence_id,business_version,version_context_id,created_at
                    from business_concept_member where project_id=? and concept_id=? order by source_type,external_id
                    """, projectId, conceptId);
        }
        return queryAll("""
                select member_id,project_id,concept_id,claim_id,source_type,truth_role,external_id,display_name,
                  repository_id,commit_sha,evidence_id,business_version,version_context_id,created_at
                from business_concept_member
                where project_id=? and concept_id=? and business_version=?
                order by source_type,external_id
                """, projectId, conceptId, businessVersion);
    }

    public List<ConceptMember> findMembersBySource(String projectId, String sourceType, String externalId,
                                                   String businessVersion) {
        if (businessVersion == null || businessVersion.isBlank()) {
            return queryAll("""
                    select member_id,project_id,concept_id,claim_id,source_type,truth_role,external_id,display_name,
                      repository_id,commit_sha,evidence_id,business_version,version_context_id,created_at
                    from business_concept_member
                    where project_id=? and source_type=? and external_id=?
                    """, projectId, sourceType, externalId);
        }
        return queryAll("""
                select member_id,project_id,concept_id,claim_id,source_type,truth_role,external_id,display_name,
                  repository_id,commit_sha,evidence_id,business_version,version_context_id,created_at
                from business_concept_member
                where project_id=? and source_type=? and external_id=? and business_version=?
                """, projectId, sourceType, externalId, businessVersion);
    }

    /** 重建成员前清理：按项目+业务版本原子替换成员作用域，避免旧 commit 成员残留。 */
    public void deleteMembersByVersion(String projectId, String businessVersion) {
        execute("delete from business_concept_member where project_id=? and business_version=?",
                projectId, businessVersion);
    }

    // ===== AlignmentRelation =====

    public String saveAlignmentRelation(AlignmentRelation relation) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     insert or replace into alignment_relation(
                       relation_id,project_id,version,version_context_id,source_claim_id,source_external_id,source_type,
                       target_claim_id,target_external_id,target_type,relation_type,match_method,status,
                       confidence,evidence_id,source_version_context_id,target_version_context_id,detail,
                       created_at,updated_at)
                     values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                     """)) {
            statement.setString(1, relation.relationId());
            statement.setString(2, relation.projectId());
            statement.setString(3, relation.version());
            statement.setString(4, relation.versionContextId());
            statement.setString(5, relation.sourceClaimId());
            statement.setString(6, relation.sourceExternalId());
            statement.setString(7, relation.sourceType());
            statement.setString(8, relation.targetClaimId());
            statement.setString(9, relation.targetExternalId());
            statement.setString(10, relation.targetType());
            statement.setString(11, relation.relationType());
            statement.setString(12, relation.matchMethod());
            statement.setString(13, relation.status());
            statement.setObject(14, relation.confidence(), java.sql.Types.DOUBLE);
            statement.setString(15, relation.evidenceId());
            statement.setString(16, relation.sourceVersionContextId());
            statement.setString(17, relation.targetVersionContextId());
            statement.setString(18, relation.detail());
            statement.setString(19, relation.createdAt());
            statement.setString(20, relation.updatedAt());
            statement.executeUpdate();
            return relation.relationId();
        } catch (SQLException exception) {
            throw new IllegalStateException("保存对齐关系失败", exception);
        }
    }

    /** 批量保存对齐关系（单事务）。 */
    public int saveAlignmentRelations(List<AlignmentRelation> relations) {
        if (relations == null || relations.isEmpty()) return 0;
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                     insert or replace into alignment_relation(
                       relation_id,project_id,version,version_context_id,source_claim_id,source_external_id,source_type,
                       target_claim_id,target_external_id,target_type,relation_type,match_method,status,
                       confidence,evidence_id,source_version_context_id,target_version_context_id,detail,
                       created_at,updated_at)
                     values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                     """)) {
                for (AlignmentRelation relation : relations) {
                    statement.setString(1, relation.relationId());
                    statement.setString(2, relation.projectId());
                    statement.setString(3, relation.version());
                    statement.setString(4, relation.versionContextId());
                    statement.setString(5, relation.sourceClaimId());
                    statement.setString(6, relation.sourceExternalId());
                    statement.setString(7, relation.sourceType());
                    statement.setString(8, relation.targetClaimId());
                    statement.setString(9, relation.targetExternalId());
                    statement.setString(10, relation.targetType());
                    statement.setString(11, relation.relationType());
                    statement.setString(12, relation.matchMethod());
                    statement.setString(13, relation.status());
                    statement.setObject(14, relation.confidence(), java.sql.Types.DOUBLE);
                    statement.setString(15, relation.evidenceId());
                    statement.setString(16, relation.sourceVersionContextId());
                    statement.setString(17, relation.targetVersionContextId());
                    statement.setString(18, relation.detail());
                    statement.setString(19, relation.createdAt());
                    statement.setString(20, relation.updatedAt());
                    statement.addBatch();
                }
                int[] results = statement.executeBatch();
                connection.commit();
                return results.length;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("批量保存对齐关系失败", exception);
        }
    }

    public List<AlignmentRelation> findAlignmentRelations(String projectId, String version,
                                                          String versionContextId, String relationType) {
        if (relationType == null || relationType.isBlank()) {
            return queryAll("""
                    select * from alignment_relation
                    where project_id=? and version=? and version_context_id=?
                    order by relation_type,source_type
                    """, projectId, version, versionContextId);
        }
        return queryAll("""
                select * from alignment_relation
                where project_id=? and version=? and version_context_id=? and relation_type=?
                order by source_type,target_type
                """, projectId, version, versionContextId, relationType);
    }

    public List<AlignmentRelation> findAlignmentRelationsForExternal(
            String projectId, String version, String versionContextId, String externalId) {
        return queryAll("""
                select * from alignment_relation
                where project_id=? and version=? and version_context_id=?
                  and (source_external_id=? or target_external_id=?)
                order by relation_type
                """, projectId, version, versionContextId, externalId, externalId);
    }

    public void deleteAlignmentRelations(String projectId, String version, String versionContextId) {
        execute("delete from alignment_relation where project_id=? and version=? and version_context_id=?",
                projectId, version, versionContextId);
    }

    public void deleteAlignmentRelationsByType(String projectId, String version, String versionContextId,
                                               String relationType) {
        execute("delete from alignment_relation where project_id=? and version=? and version_context_id=? and relation_type=?",
                projectId, version, versionContextId, relationType);
    }

    // ===== DriftItem =====

    public String saveDriftItem(DriftItem item) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     insert into drift_item(drift_id,project_id,version,version_context_id,concept_id,concept_key,
                       drift_type,severity,truth_role,source_claim_id,target_claim_id,source_value,target_value,
                       detail,status,created_at,updated_at)
                     values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                     on conflict(project_id, version, version_context_id, concept_id, drift_type) do update set
                       concept_key=excluded.concept_key, severity=excluded.severity, truth_role=excluded.truth_role,
                       source_claim_id=excluded.source_claim_id, target_claim_id=excluded.target_claim_id,
                       source_value=excluded.source_value, target_value=excluded.target_value,
                       detail=excluded.detail, status=excluded.status, updated_at=excluded.updated_at
                     """)) {
            statement.setString(1, item.driftId());
            statement.setString(2, item.projectId());
            statement.setString(3, item.version());
            statement.setString(4, item.versionContextId());
            statement.setString(5, item.conceptId());
            statement.setString(6, item.conceptKey());
            statement.setString(7, item.driftType());
            statement.setString(8, item.severity());
            statement.setString(9, item.truthRole());
            statement.setString(10, item.sourceClaimId());
            statement.setString(11, item.targetClaimId());
            statement.setString(12, item.sourceValue());
            statement.setString(13, item.targetValue());
            statement.setString(14, item.detail());
            statement.setString(15, item.status());
            statement.setString(16, item.createdAt());
            statement.setString(17, item.updatedAt());
            statement.executeUpdate();
            return item.driftId();
        } catch (SQLException exception) {
            throw new IllegalStateException("保存漂移结论失败", exception);
        }
    }

    /** 批量保存漂移结论（单事务）。 */
    public int saveDriftItems(List<DriftItem> items) {
        if (items == null || items.isEmpty()) return 0;
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                     insert into drift_item(drift_id,project_id,version,version_context_id,concept_id,concept_key,
                       drift_type,severity,truth_role,source_claim_id,target_claim_id,source_value,target_value,
                       detail,status,created_at,updated_at)
                     values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                     on conflict(project_id, version, version_context_id, concept_id, drift_type) do update set
                       concept_key=excluded.concept_key, severity=excluded.severity, truth_role=excluded.truth_role,
                       source_claim_id=excluded.source_claim_id, target_claim_id=excluded.target_claim_id,
                       source_value=excluded.source_value, target_value=excluded.target_value,
                       detail=excluded.detail, status=excluded.status, updated_at=excluded.updated_at
                     """)) {
                for (DriftItem item : items) {
                    statement.setString(1, item.driftId());
                    statement.setString(2, item.projectId());
                    statement.setString(3, item.version());
                    statement.setString(4, item.versionContextId());
                    statement.setString(5, item.conceptId());
                    statement.setString(6, item.conceptKey());
                    statement.setString(7, item.driftType());
                    statement.setString(8, item.severity());
                    statement.setString(9, item.truthRole());
                    statement.setString(10, item.sourceClaimId());
                    statement.setString(11, item.targetClaimId());
                    statement.setString(12, item.sourceValue());
                    statement.setString(13, item.targetValue());
                    statement.setString(14, item.detail());
                    statement.setString(15, item.status());
                    statement.setString(16, item.createdAt());
                    statement.setString(17, item.updatedAt());
                    statement.addBatch();
                }
                int[] results = statement.executeBatch();
                connection.commit();
                return results.length;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("批量保存漂移结论失败", exception);
        }
    }

    public List<DriftItem> findDriftItems(String projectId, String version, String versionContextId,
                                          String driftType) {
        if (driftType == null || driftType.isBlank()) {
            return queryAll("""
                    select * from drift_item
                    where project_id=? and version=? and version_context_id=?
                    order by drift_type,concept_key
                    """, projectId, version, versionContextId);
        }
        return queryAll("""
                select * from drift_item
                where project_id=? and version=? and version_context_id=? and drift_type=?
                order by concept_key
                """, projectId, version, versionContextId, driftType);
    }

    public void deleteDriftItems(String projectId, String version, String versionContextId) {
        execute("delete from drift_item where project_id=? and version=? and version_context_id=?",
                projectId, version, versionContextId);
    }

    public void deleteDriftItemsByType(String projectId, String version, String versionContextId,
                                       String driftType) {
        execute("delete from drift_item where project_id=? and version=? and version_context_id=? and drift_type=?",
                projectId, version, versionContextId, driftType);
    }

    // ===== DoubtImpact =====

    public String saveDoubtImpact(DoubtImpact impact) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     insert or replace into doubt_impact(
                       impact_id,project_id,version,version_context_id,doubt_id,question,concept_id,concept_key,
                       target_type,target_claim_id,target_external_id,target_name,severity,owner,due_date,
                       status,resolution_evidence_id,resolution_conclusion,created_at,resolved_at)
                     values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                     """)) {
            statement.setString(1, impact.impactId());
            statement.setString(2, impact.projectId());
            statement.setString(3, impact.version());
            statement.setString(4, impact.versionContextId());
            statement.setString(5, impact.doubtId());
            statement.setString(6, impact.question());
            statement.setString(7, impact.conceptId());
            statement.setString(8, impact.conceptKey());
            statement.setString(9, impact.targetType());
            statement.setString(10, impact.targetClaimId());
            statement.setString(11, impact.targetExternalId());
            statement.setString(12, impact.targetName());
            statement.setString(13, impact.severity());
            statement.setString(14, impact.owner());
            statement.setString(15, impact.dueDate());
            statement.setString(16, impact.status());
            statement.setString(17, impact.resolutionEvidenceId());
            statement.setString(18, impact.resolutionConclusion());
            statement.setString(19, impact.createdAt());
            statement.setString(20, impact.resolvedAt());
            statement.executeUpdate();
            return impact.impactId();
        } catch (SQLException exception) {
            throw new IllegalStateException("保存存疑影响失败", exception);
        }
    }

    /** 批量保存存疑影响（单事务）。 */
    public int saveDoubtImpacts(List<DoubtImpact> impacts) {
        if (impacts == null || impacts.isEmpty()) return 0;
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement("""
                     insert or replace into doubt_impact(
                       impact_id,project_id,version,version_context_id,doubt_id,question,concept_id,concept_key,
                       target_type,target_claim_id,target_external_id,target_name,severity,owner,due_date,
                       status,resolution_evidence_id,resolution_conclusion,created_at,resolved_at)
                     values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                     """)) {
                for (DoubtImpact impact : impacts) {
                    statement.setString(1, impact.impactId());
                    statement.setString(2, impact.projectId());
                    statement.setString(3, impact.version());
                    statement.setString(4, impact.versionContextId());
                    statement.setString(5, impact.doubtId());
                    statement.setString(6, impact.question());
                    statement.setString(7, impact.conceptId());
                    statement.setString(8, impact.conceptKey());
                    statement.setString(9, impact.targetType());
                    statement.setString(10, impact.targetClaimId());
                    statement.setString(11, impact.targetExternalId());
                    statement.setString(12, impact.targetName());
                    statement.setString(13, impact.severity());
                    statement.setString(14, impact.owner());
                    statement.setString(15, impact.dueDate());
                    statement.setString(16, impact.status());
                    statement.setString(17, impact.resolutionEvidenceId());
                    statement.setString(18, impact.resolutionConclusion());
                    statement.setString(19, impact.createdAt());
                    statement.setString(20, impact.resolvedAt());
                    statement.addBatch();
                }
                int[] results = statement.executeBatch();
                connection.commit();
                return results.length;
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("批量保存存疑影响失败", exception);
        }
    }

    public List<DoubtImpact> findDoubtImpacts(
            String projectId, String version, String versionContextId, String status) {
        if (status == null || status.isBlank()) {
            return queryAll("""
                    select * from doubt_impact
                    where project_id=? and version=? and version_context_id=?
                    order by doubt_id,target_type
                    """, projectId, version, versionContextId);
        }
        return queryAll("""
                select * from doubt_impact
                where project_id=? and version=? and version_context_id=? and status=?
                order by doubt_id,target_type
                """, projectId, version, versionContextId, status);
    }

    public List<DoubtImpact> findDoubtImpactsByDoubt(
            String projectId, String version, String versionContextId, String doubtId) {
        return queryAll("""
                select * from doubt_impact
                where project_id=? and version=? and version_context_id=? and doubt_id=?
                order by target_type
                """, projectId, version, versionContextId, doubtId);
    }

    public void resolveDoubtImpacts(String projectId, String version, String versionContextId, String doubtId,
                                    String conclusion, String evidenceId) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement("""
                     update doubt_impact set
                       status='RESOLVED', resolution_conclusion=?, resolution_evidence_id=?, resolved_at=?
                     where project_id=? and version=? and version_context_id=? and doubt_id=? and status='OPEN'
                     """)) {
            statement.setString(1, conclusion);
            statement.setString(2, evidenceId);
            statement.setString(3, java.time.Instant.now().toString());
            statement.setString(4, projectId);
            statement.setString(5, version);
            statement.setString(6, versionContextId);
            statement.setString(7, doubtId);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("关闭存疑影响失败", exception);
        }
    }

    public void deleteDoubtImpactsByVersion(String projectId, String version, String versionContextId) {
        execute("delete from doubt_impact where project_id=? and version=? and version_context_id=?",
                projectId, version, versionContextId);
    }

    // ===== helpers =====

    private Connection open() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    private void addColumnIfMissing(Statement statement, String table, String column,
                                    String definition) throws SQLException {
        try (ResultSet columns = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (columns.next()) {
                if (column.equals(columns.getString("name"))) return;
            }
        }
        statement.executeUpdate("alter table " + table + " add column " + column + " " + definition);
    }

    /** 检测对齐表是否为旧 schema（缺少 requiredMarker），是则重建（派生数据可直接重建）。 */
    private void dropTableIfOldSchema(Statement statement, String table, String requiredMarker) throws SQLException {
        String sql = null;
        try (ResultSet rows = statement.executeQuery(
                "select sql from sqlite_master where type='table' and name='" + table + "'")) {
            if (rows.next()) {
                sql = rows.getString(1);
            }
        }
        if (sql == null || sql.contains(requiredMarker)) {
            return;
        }
        statement.executeUpdate("drop table " + table);
    }

    private void execute(String sql, String... args) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) statement.setString(i + 1, args[i]);
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("对齐库写操作失败", exception);
        }
    }

    private <T> Optional<T> queryOne(String sql, String... args) {
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) statement.setString(i + 1, args[i]);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                @SuppressWarnings("unchecked")
                T value = (T) mapRow(rows);
                return Optional.ofNullable(value);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("对齐库查询失败", exception);
        }
    }

    private <T> List<T> queryAll(String sql, String... args) {
        List<T> result = new ArrayList<>();
        try (Connection connection = open();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) statement.setString(i + 1, args[i]);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    @SuppressWarnings("unchecked")
                    T value = (T) mapRow(rows);
                    result.add(value);
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("对齐库查询失败", exception);
        }
        return result;
    }

    private Object mapRow(ResultSet rows) throws SQLException {
        String table = rows.getMetaData().getTableName(1);
        if ("version_context".equals(table)) {
            return versionContext(rows);
        }
        if ("business_concept".equals(table)) {
            return concept(rows);
        }
        if ("business_concept_alias".equals(table)) {
            return alias(rows);
        }
        if ("business_concept_member".equals(table)) {
            return member(rows);
        }
        if ("alignment_relation".equals(table)) {
            return relation(rows);
        }
        if ("drift_item".equals(table)) {
            return drift(rows);
        }
        if ("doubt_impact".equals(table)) {
            return doubtImpact(rows);
        }
        throw new IllegalStateException("未知对齐表: " + table);
    }

    private VersionContext versionContext(ResultSet rows) throws SQLException {
        return new VersionContext(
                rows.getString("context_id"), rows.getString("project_id"),
                rows.getString("business_version"), rows.getString("repository_id"),
                rows.getString("commit_sha"), rows.getString("environment"), rows.getString("status"),
                rows.getString("created_at"), rows.getString("updated_at"));
    }

    private BusinessConcept concept(ResultSet rows) throws SQLException {
        return new BusinessConcept(
                rows.getString("concept_id"), rows.getString("project_id"), rows.getString("canonical_key"),
                rows.getString("display_name"), rows.getString("concept_type"), rows.getString("module"),
                rows.getString("description"), rows.getString("status"),
                rows.getString("created_at"), rows.getString("updated_at"));
    }

    private ConceptAlias alias(ResultSet rows) throws SQLException {
        return new ConceptAlias(
                rows.getString("alias_id"), rows.getString("project_id"), rows.getString("concept_id"),
                rows.getString("alias"), rows.getString("source_type"), rows.getString("normalization_method"),
                rows.getObject("confidence") == null ? null : rows.getDouble("confidence"),
                rows.getString("created_at"));
    }

    private ConceptMember member(ResultSet rows) throws SQLException {
        return new ConceptMember(
                rows.getString("member_id"), rows.getString("project_id"), rows.getString("concept_id"),
                rows.getString("claim_id"), rows.getString("source_type"), rows.getString("truth_role"),
                rows.getString("external_id"), rows.getString("display_name"), rows.getString("repository_id"),
                rows.getString("commit_sha"), rows.getString("evidence_id"),
                rows.getString("business_version"), rows.getString("version_context_id"),
                rows.getString("created_at"));
    }

    private AlignmentRelation relation(ResultSet rows) throws SQLException {
        return new AlignmentRelation(
                rows.getString("relation_id"), rows.getString("project_id"), rows.getString("version"),
                rows.getString("version_context_id"),
                rows.getString("source_claim_id"), rows.getString("source_external_id"),
                rows.getString("source_type"), rows.getString("target_claim_id"),
                rows.getString("target_external_id"), rows.getString("target_type"),
                rows.getString("relation_type"), rows.getString("match_method"), rows.getString("status"),
                rows.getObject("confidence") == null ? null : rows.getDouble("confidence"),
                rows.getString("evidence_id"), rows.getString("source_version_context_id"),
                rows.getString("target_version_context_id"), rows.getString("detail"),
                rows.getString("created_at"), rows.getString("updated_at"));
    }

    private DriftItem drift(ResultSet rows) throws SQLException {
        return new DriftItem(
                rows.getString("drift_id"), rows.getString("project_id"), rows.getString("version"),
                rows.getString("version_context_id"),
                rows.getString("concept_id"), rows.getString("concept_key"), rows.getString("drift_type"),
                rows.getString("severity"), rows.getString("truth_role"), rows.getString("source_claim_id"),
                rows.getString("target_claim_id"), rows.getString("source_value"), rows.getString("target_value"),
                rows.getString("detail"), rows.getString("status"),
                rows.getString("created_at"), rows.getString("updated_at"));
    }

    private DoubtImpact doubtImpact(ResultSet rows) throws SQLException {
        return new DoubtImpact(
                rows.getString("impact_id"), rows.getString("project_id"), rows.getString("version"),
                rows.getString("version_context_id"), rows.getString("doubt_id"), rows.getString("question"),
                rows.getString("concept_id"), rows.getString("concept_key"), rows.getString("target_type"),
                rows.getString("target_claim_id"), rows.getString("target_external_id"), rows.getString("target_name"),
                rows.getString("severity"), rows.getString("owner"), rows.getString("due_date"),
                rows.getString("status"), rows.getString("resolution_evidence_id"),
                rows.getString("resolution_conclusion"), rows.getString("created_at"), rows.getString("resolved_at"));
    }
}
