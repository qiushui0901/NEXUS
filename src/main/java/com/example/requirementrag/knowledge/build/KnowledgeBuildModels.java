package com.example.requirementrag.knowledge.build;

import com.example.requirementrag.model.RagWarning;
import com.example.requirementrag.wiki.WikiModels.Evidence;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Public contracts for building reviewable version-knowledge drafts. */
public final class KnowledgeBuildModels {
    private KnowledgeBuildModels() {}

    public enum BuildStatus {
        DRAFT,
        NO_CHANGES
    }

    public record BuildRequest(
            @NotBlank @Size(max = 100) String projectId,
            @NotBlank @Size(max = 100) String version,
            @Size(max = 100) String baseVersion,
            @NotBlank @Size(max = 160) String documentId,
            @Size(max = 128) String baseCodeCommit,
            @Size(max = 128) String codeCommit
    ) {}

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
            String reviewStatus
    ) {
        public FeatureFactDraft {
            productRules = copy(productRules);
            codeSymbols = copy(codeSymbols);
            testPoints = copy(testPoints);
            requirementEvidence = copy(requirementEvidence);
            codeEvidence = copy(codeEvidence);
            testEvidence = copy(testEvidence);
            conflicts = copy(conflicts);
        }

        private static <T> List<T> copy(List<T> values) {
            return values == null ? List.of() : List.copyOf(values);
        }
    }

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
