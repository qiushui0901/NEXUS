package com.example.requirementrag.requirement.graph;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;

/** 需求语义图的版本化、可审阅数据契约。图关系是派生事实，必须绑定需求证据。 */
public final class RequirementGraphModels {
    private RequirementGraphModels() {
    }

    public enum SnapshotStatus { BUILDING, DRAFT, REVIEW_REQUIRED, VERIFIED, PUBLISHED, FAILED, STALE }

    public enum EntityType {
        REQUIREMENT, FEATURE, MODULE, ACTOR, BUSINESS_OBJECT, STATE, EVENT, PROCESS, RULE,
        INTERFACE, DATA_ENTITY, CONFIGURATION, EXCEPTION, ACCEPTANCE_CRITERION, VERSION
    }

    public enum EntityStatus { EXTRACTED, NORMALIZED, AMBIGUOUS, VERIFIED, REJECTED, STALE }

    public enum RelationType {
        DEPENDS_ON, REFINES, CONFLICTS_WITH, AFFECTS_MODULE, PERFORMED_BY, OPERATES_ON,
        CHANGES_STATE, TRIGGERS_EVENT, PRECEDES, REQUIRES_RULE, EXPOSES_INTERFACE,
        HAS_EXCEPTION, HAS_ACCEPTANCE_CRITERION, INTRODUCED_IN_VERSION
    }

    public enum RelationStatus { EXTRACTED, NORMALIZED, AMBIGUOUS, VERIFIED, REJECTED, STALE }

    public enum SearchMode { LOCAL, GLOBAL }

    public record GraphSnapshot(
            String id,
            String businessProjectId,
            String documentId,
            String requirementVersion,
            String sourceRevision,
            String extractionModel,
            String promptVersion,
            SnapshotStatus status,
            int entityCount,
            int relationCount,
            Instant createdAt,
            Instant updatedAt,
            Instant publishedAt
    ) {
    }

    public record Entity(
            String id,
            String snapshotId,
            EntityType type,
            String canonicalName,
            String displayName,
            List<String> aliases,
            String description,
            List<String> sourceEvidenceIds,
            List<String> sourceParentIds,
            List<String> sourceContentHashes,
            double confidence,
            EntityStatus status
    ) {
        public Entity {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            sourceEvidenceIds = sourceEvidenceIds == null ? List.of() : List.copyOf(sourceEvidenceIds);
            sourceParentIds = sourceParentIds == null ? List.of() : List.copyOf(sourceParentIds);
            sourceContentHashes = sourceContentHashes == null ? List.of() : List.copyOf(sourceContentHashes);
        }
    }

    public record Relation(
            String id,
            String snapshotId,
            String sourceEntityId,
            RelationType type,
            String targetEntityId,
            String statement,
            List<String> sourceEvidenceIds,
            double confidence,
            RelationStatus status,
            String reviewer,
            Instant reviewedAt
    ) {
        public Relation {
            sourceEvidenceIds = sourceEvidenceIds == null ? List.of() : List.copyOf(sourceEvidenceIds);
        }
    }

    public record ExtractedEntity(
            String localId,
            String type,
            String name,
            List<String> aliases,
            String description,
            List<String> evidenceQuotes,
            double confidence
    ) {
        public ExtractedEntity {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            evidenceQuotes = evidenceQuotes == null ? List.of() : List.copyOf(evidenceQuotes);
        }
    }

    public record ExtractedRelation(
            String sourceLocalId,
            String type,
            String targetLocalId,
            String statement,
            List<String> evidenceQuotes,
            double confidence
    ) {
        public ExtractedRelation {
            evidenceQuotes = evidenceQuotes == null ? List.of() : List.copyOf(evidenceQuotes);
        }
    }

    public record ExtractionResult(
            List<ExtractedEntity> entities,
            List<ExtractedRelation> relations,
            List<String> uncertainties
    ) {
        public ExtractionResult {
            entities = entities == null ? List.of() : List.copyOf(entities);
            relations = relations == null ? List.of() : List.copyOf(relations);
            uncertainties = uncertainties == null ? List.of() : List.copyOf(uncertainties);
        }
    }

    public record ExtractionInput(
            String filename,
            String parentId,
            int parentOrder,
            String sectionPath,
            String heading,
            String contentHash,
            String text
    ) {
    }

    public record BuildRequest(
            @NotBlank String projectId,
            @NotBlank String documentId,
            @NotBlank String requirementVersion,
            String collection
    ) {
    }

    public record SearchRequest(
            @NotBlank String projectId,
            @NotBlank String documentId,
            @NotBlank String requirementVersion,
            @NotBlank String query,
            SearchMode mode,
            @Min(1) @Max(50) Integer limit,
            @Min(0) @Max(4) Integer maxHops
    ) {
    }

    public record SearchResponse(
            GraphSnapshot snapshot,
            List<Entity> entities,
            List<Relation> relations,
            List<Evidence> evidence
    ) {
        public SearchResponse {
            entities = entities == null ? List.of() : List.copyOf(entities);
            relations = relations == null ? List.of() : List.copyOf(relations);
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    public record Evidence(
            String evidenceId,
            String filename,
            String parentId,
            int parentOrder,
            String version,
            String excerpt,
            String contentHash
    ) {
    }

    public record GraphPage<T>(List<T> items, int total) {
        public GraphPage {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
