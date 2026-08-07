package com.example.requirementrag.knowledge.build;

import com.example.requirementrag.model.RagWarning;
import com.example.requirementrag.wiki.WikiModels.Evidence;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 构建可审核的版本知识草稿所需的公开契约模型（请求、状态、草稿与结果）。 */
public final class KnowledgeBuildModels {
    private KnowledgeBuildModels() {}

    /** 构建状态：DRAFT 表示生成了可审核草稿，NO_CHANGES 表示版本间无变化。 */
    public enum BuildStatus {
        DRAFT,
        NO_CHANGES
    }

    /** 构建请求：指定项目、版本、基线版本、文档 ID 及可选的基线/当前代码提交号。 */
    public record BuildRequest(
            @NotBlank @Size(max = 100) String projectId,
            @NotBlank @Size(max = 100) String version,
            @Size(max = 100) String baseVersion,
            @NotBlank @Size(max = 160) String documentId,
            @Size(max = 128) String baseCodeCommit,
            @Size(max = 128) String codeCommit
    ) {}

    /** 单个功能事实草稿：变化类型、产品规则、代码符号、测试要点与三类证据列表，附置信度和审核状态。 */
    public record FeatureFactDraft(
            String featureId,
            String title,
            String changeType,
            List<String> productRules,
            List<String> codeSymbols,
            List<String> testPoints,
            List<Evidence> requirementEvidence,
            List<Evidence> codeEvidence,
            List<Evidence> testEvidence,
            List<String> conflicts,
            double confidence,
            String reviewStatus,
            List<String> requirementContentHashes
    ) {
        public FeatureFactDraft {
            productRules = copy(productRules);
            codeSymbols = copy(codeSymbols);
            testPoints = copy(testPoints);
            requirementEvidence = copy(requirementEvidence);
            codeEvidence = copy(codeEvidence);
            testEvidence = copy(testEvidence);
            conflicts = copy(conflicts);
            requirementContentHashes = copy(requirementContentHashes);
        }

        private static <T> List<T> copy(List<T> values) {
            return values == null ? List.of() : List.copyOf(values);
        }
    }

    /** 构建产物：构建 ID、状态、原始请求、功能草稿列表与警告。 */
    public record BuildArtifact(
            String buildId,
            BuildStatus status,
            BuildRequest request,
            String generatedAt,
            List<FeatureFactDraft> features,
            List<RagWarning> warnings
    ) {
        public BuildArtifact {
            features = features == null ? List.of() : List.copyOf(features);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    /** 构建结果摘要：统计功能数、冲突数、缺代码/缺测试数及草稿落盘路径。 */
    public record BuildResult(
            String buildId,
            BuildStatus status,
            int features,
            int conflicts,
            int missingCode,
            int missingTests,
            String draftPath,
            String generatedAt,
            List<RagWarning> warnings
    ) {
        public BuildResult {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }
}
