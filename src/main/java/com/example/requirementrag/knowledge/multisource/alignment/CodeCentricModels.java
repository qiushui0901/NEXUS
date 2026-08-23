package com.example.requirementrag.knowledge.multisource.alignment;

import java.util.List;

/**
 * 代码事实基线驱动的跨源对齐领域模型（改进方案 Phase 1-4）：
 * 版本上下文、业务概念、概念别名、概念成员、对齐关系与漂移结论。
 *
 * <p>核心原则：来源事实永不合并覆盖；跨源只通过 BusinessConcept / 稳定 key 对齐；
 * 关系必须携带 matchMethod / status / evidence / 版本上下文；LLM 只产候选。
 */
public final class CodeCentricModels {
    private CodeCentricModels() {
    }

    /** 事实角色：记录回答什么类型的问题（与“来自哪个来源”分离）。 */
    public enum TruthRole {
        /** 当前代码实现的行为。 */
        IMPLEMENTATION,
        /** 已部署或受控配置的值。 */
        CONFIGURATION,
        /** 某环境下测试/运行观测到的行为。 */
        OBSERVATION,
        /** 需求文档表达的预期或历史意图。 */
        INTENT,
        /** 待确认问题。 */
        QUESTION,
        /** 规则或 LLM 推导的结论。 */
        DERIVED
    }

    /** 跨源对齐关系类型。 */
    public enum AlignmentRelationType {
        READS_CONFIG,
        USES_PARAMETER,
        CONFIG_DRIFT,
        VERIFIES,
        CONFIRMS,
        TEST_DRIFT,
        ALIGNED_WITH,
        DOCUMENT_DRIFT,
        UNMAPPED,
        IMPLEMENTED_BY
    }

    /** 匹配方法：关系必须记录“为什么匹配”，便于审计与重建。 */
    public enum MatchMethod {
        NORMALIZED_NAME_EXACT,
        NORMALIZED_NAME_CONTAINS,
        CONFIG_KEY_EXACT,
        TEST_SYMBOL_EXACT,
        TEST_CASE_ID_EXACT,
        REQUIREMENT_ID_EXACT,
        VALUE_COMPARISON,
        HEURISTIC
    }

    /** 关系确认状态。 */
    public enum RelationStatus {
        RULE_CONFIRMED,
        LLM_CANDIDATE,
        LLM_CONFIRMED,
        HUMAN_CONFIRMED,
        REJECTED,
        UNRESOLVED,
        STALE
    }

    /** 漂移结论类型。 */
    public enum DriftType {
        ALIGNED,
        DOCUMENT_DRIFT,
        UNMAPPED,
        /** 名称已映射但缺少确定性实现证据，不宣称已实现。 */
        MAPPED_NO_IMPLEMENTATION_ASSERTION,
        IMPLEMENTATION_REVIEW_REQUIRED,
        CONFIG_DRIFT,
        TEST_DRIFT
    }

    /** 漂移严重级别。 */
    public enum DriftSeverity {
        INFO,
        WARNING,
        ERROR
    }

    /** 版本上下文：所有“当前实现”结论必须绑定 repository + commit（+环境）。 */
    public record VersionContext(
            String contextId,
            String projectId,
            String businessVersion,
            String repositoryId,
            String commitSha,
            String environment,
            String status,
            String createdAt,
            String updatedAt
    ) {
        public VersionContext {
            if (contextId == null || contextId.isBlank()) throw new IllegalArgumentException("contextId 不能为空");
            if (projectId == null || projectId.isBlank()) throw new IllegalArgumentException("projectId 不能为空");
            if (businessVersion == null || businessVersion.isBlank()) {
                throw new IllegalArgumentException("businessVersion 不能为空");
            }
            environment = environment == null || environment.isBlank() ? "default" : environment;
            status = status == null || status.isBlank() ? "ACTIVE" : status;
            String now = java.time.Instant.now().toString();
            createdAt = createdAt == null ? now : createdAt;
            updatedAt = updatedAt == null ? createdAt : updatedAt;
        }
    }

    /** 业务概念：跨源对齐锚点，canonicalKey 使用可稳定生成的规范化路径。 */
    public record BusinessConcept(
            String conceptId,
            String projectId,
            String canonicalKey,
            String displayName,
            String conceptType,
            String module,
            String description,
            String status,
            String createdAt,
            String updatedAt
    ) {
        public BusinessConcept {
            if (conceptId == null || conceptId.isBlank()) throw new IllegalArgumentException("conceptId 不能为空");
            if (projectId == null || projectId.isBlank()) throw new IllegalArgumentException("projectId 不能为空");
            if (canonicalKey == null || canonicalKey.isBlank()) throw new IllegalArgumentException("canonicalKey 不能为空");
            if (displayName == null || displayName.isBlank()) throw new IllegalArgumentException("displayName 不能为空");
            conceptType = conceptType == null || conceptType.isBlank() ? "CONCEPT" : conceptType;
            status = status == null || status.isBlank() ? "ACTIVE" : status;
            String now = java.time.Instant.now().toString();
            createdAt = createdAt == null ? now : createdAt;
            updatedAt = updatedAt == null ? createdAt : updatedAt;
        }
    }

    /** 概念别名：必须标记来源；LLM 推断的别名不得直接变成全局 canonical 名称。 */
    public record ConceptAlias(
            String aliasId,
            String projectId,
            String conceptId,
            String alias,
            String sourceType,
            String normalizationMethod,
            Double confidence,
            String createdAt
    ) {
        public ConceptAlias {
            if (aliasId == null || aliasId.isBlank()) throw new IllegalArgumentException("aliasId 不能为空");
            if (projectId == null || projectId.isBlank()) throw new IllegalArgumentException("projectId 不能为空");
            if (conceptId == null || conceptId.isBlank()) throw new IllegalArgumentException("conceptId 不能为空");
            if (alias == null || alias.isBlank()) throw new IllegalArgumentException("alias 不能为空");
            normalizationMethod = normalizationMethod == null || normalizationMethod.isBlank()
                    ? "NORMALIZED_NAME" : normalizationMethod;
            createdAt = createdAt == null ? java.time.Instant.now().toString() : createdAt;
        }
    }

    /** 概念成员：一个来源事实（Claim 或代码符号）挂到业务概念下，携带 truthRole 与版本证据。 */
    public record ConceptMember(
            String memberId,
            String projectId,
            String conceptId,
            String claimId,
            String sourceType,
            String truthRole,
            String externalId,
            String displayName,
            String repositoryId,
            String commitSha,
            String evidenceId,
            String businessVersion,
            String versionContextId,
            String createdAt
    ) {
        public ConceptMember {
            if (memberId == null || memberId.isBlank()) throw new IllegalArgumentException("memberId 不能为空");
            if (projectId == null || projectId.isBlank()) throw new IllegalArgumentException("projectId 不能为空");
            if (conceptId == null || conceptId.isBlank()) throw new IllegalArgumentException("conceptId 不能为空");
            if (sourceType == null || sourceType.isBlank()) throw new IllegalArgumentException("sourceType 不能为空");
            if (truthRole == null || truthRole.isBlank()) throw new IllegalArgumentException("truthRole 不能为空");
            if (displayName == null || displayName.isBlank()) throw new IllegalArgumentException("displayName 不能为空");
            createdAt = createdAt == null ? java.time.Instant.now().toString() : createdAt;
        }
    }

    /** 跨源对齐关系：可回查、带匹配方法与版本上下文（按 versionContextId 作用域隔离）。 */
    public record AlignmentRelation(
            String relationId,
            String projectId,
            String version,
            String versionContextId,
            String sourceClaimId,
            String sourceExternalId,
            String sourceType,
            String targetClaimId,
            String targetExternalId,
            String targetType,
            String relationType,
            String matchMethod,
            String status,
            Double confidence,
            String evidenceId,
            String sourceVersionContextId,
            String targetVersionContextId,
            String detail,
            String createdAt,
            String updatedAt
    ) {
        public AlignmentRelation {
            if (relationId == null || relationId.isBlank()) throw new IllegalArgumentException("relationId 不能为空");
            if (projectId == null || projectId.isBlank()) throw new IllegalArgumentException("projectId 不能为空");
            if (version == null || version.isBlank()) throw new IllegalArgumentException("version 不能为空");
            if (versionContextId == null || versionContextId.isBlank()) {
                throw new IllegalArgumentException("versionContextId 不能为空");
            }
            if (sourceType == null || sourceType.isBlank()) throw new IllegalArgumentException("sourceType 不能为空");
            if (targetType == null || targetType.isBlank()) throw new IllegalArgumentException("targetType 不能为空");
            if (relationType == null || relationType.isBlank()) throw new IllegalArgumentException("relationType 不能为空");
            matchMethod = matchMethod == null || matchMethod.isBlank() ? "HEURISTIC" : matchMethod;
            status = status == null || status.isBlank() ? "RULE_CONFIRMED" : status;
            String now = java.time.Instant.now().toString();
            createdAt = createdAt == null ? now : createdAt;
            updatedAt = updatedAt == null ? createdAt : updatedAt;
        }
    }

    /** 漂移/对齐结论项：按业务概念输出“已对齐/文档过期/未映射/待审核”。 */
    public record DriftItem(
            String driftId,
            String projectId,
            String version,
            String versionContextId,
            String conceptId,
            String conceptKey,
            String driftType,
            String severity,
            String truthRole,
            String sourceClaimId,
            String targetClaimId,
            String sourceValue,
            String targetValue,
            String detail,
            String status,
            String createdAt,
            String updatedAt
    ) {
        public DriftItem {
            if (driftId == null || driftId.isBlank()) throw new IllegalArgumentException("driftId 不能为空");
            if (projectId == null || projectId.isBlank()) throw new IllegalArgumentException("projectId 不能为空");
            if (version == null || version.isBlank()) throw new IllegalArgumentException("version 不能为空");
            if (versionContextId == null || versionContextId.isBlank()) {
                throw new IllegalArgumentException("versionContextId 不能为空");
            }
            if (conceptId == null || conceptId.isBlank()) throw new IllegalArgumentException("conceptId 不能为空");
            if (conceptKey == null || conceptKey.isBlank()) throw new IllegalArgumentException("conceptKey 不能为空");
            if (driftType == null || driftType.isBlank()) throw new IllegalArgumentException("driftType 不能为空");
            severity = severity == null || severity.isBlank() ? "WARNING" : severity;
            status = status == null || status.isBlank() ? "OPEN" : status;
            String now = java.time.Instant.now().toString();
            createdAt = createdAt == null ? now : createdAt;
            updatedAt = updatedAt == null ? createdAt : updatedAt;
        }
    }

    /** 构建结果汇总。 */
    public record BuildResult(int concepts, int aliases, int members, int relations, int drifts) {
    }

    /** 漂移报告：按模块/概念分组，输出各状态清单与统计。 */
    public record DriftReport(
            String projectId,
            String version,
            String commitSha,
            int aligned,
            int documentDrift,
            int unmapped,
            int mappedNoAssertion,
            int reviewRequired,
            List<DriftItem> items
    ) {
        public DriftReport {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /** 代码符号加载结果：绑定代码项目与 commit 的全部符号。 */
    public record LoadedCode(String codeProjectId, String commitSha, List<CodeSymbolView> symbols) {
        public LoadedCode {
            symbols = symbols == null ? List.of() : List.copyOf(symbols);
        }
    }

    /** 代码符号视图：从代码图谱投影的轻量符号，保留实现证据位置。 */
    public record CodeSymbolView(
            String id,
            String projectId,
            String commitSha,
            String kind,
            String qualifiedName,
            String simpleName,
            String filePath,
            int startLine,
            int endLine,
            boolean entryPoint,
            boolean testSymbol
    ) {
    }
}
