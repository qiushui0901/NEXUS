package com.example.requirementrag.requirement.graph;

import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.RagWarning;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Versioned, reviewable requirement graph contracts. Claims remain evidence-backed projections. */
public final class RequirementGraphModels {
    private RequirementGraphModels() {
    }

    public enum SnapshotStatus {
        DRAFT, BUILDING, REVIEW_REQUIRED, VERIFIED, PUBLISHED, PARTIAL_FAILED, FAILED, STALE, REJECTED
    }

    public enum ClaimStatus {
        EXTRACTED, INFERRED, VERIFIED, REJECTED, CONFLICTED, STALE, UNAVAILABLE
    }

    public enum WindowStatus { PENDING, RUNNING, SUCCEEDED, FAILED, SKIPPED }

    public enum EvidenceResolutionStatus { RESOLVED, AMBIGUOUS, LEGACY_PARENT_ONLY, UNAVAILABLE, STALE }

    public enum EntityType {
        REQUIREMENT, FEATURE, MODULE, ACTOR, BUSINESS_OBJECT, STATE, EVENT, PROCESS, RULE,
        INTERFACE, DATA_ENTITY, CONFIGURATION, EXCEPTION, ACCEPTANCE_CRITERION, VERSION,
        EXTERNAL_SYSTEM, VALUE_OR_PARAMETER
    }

    /** Legacy relation status remains readable; claimStatus is the publication contract. */
    public enum EntityStatus { EXTRACTED, NORMALIZED, AMBIGUOUS, VERIFIED, REJECTED, STALE }

    public enum RelationType {
        DEPENDS_ON, REFINES, CONFLICTS_WITH, AFFECTS_MODULE, PERFORMED_BY, OPERATES_ON,
        CHANGES_STATE, TRIGGERS_EVENT, PRECEDES, REQUIRES_RULE, EXPOSES_INTERFACE,
        HAS_EXCEPTION, HAS_ACCEPTANCE_CRITERION, INTRODUCED_IN_VERSION,
        CONTAINS, TRANSITIONS_TO, REQUIRES, VERIFIED_BY, EXCEPTION_TO, USES
    }

    public enum RelationStatus { EXTRACTED, NORMALIZED, AMBIGUOUS, VERIFIED, REJECTED, STALE }

    public enum SearchMode {
        NAIVE,
        LOCAL,
        GLOBAL,
        HYBRID,
        MIX
    }

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
            Instant publishedAt,
            int schemaVersion,
            String ontologyVersion,
            double coverageRatio,
            int windowCount,
            int succeededWindowCount,
            int failedWindowCount,
            int warningCount,
            String buildId,
            String publishedBy,
            String publicationReason,
            Instant staleAt
    ) {
        /** Compatibility constructor for schema-v1 snapshots. */
        public GraphSnapshot(String id, String businessProjectId, String documentId, String requirementVersion,
                             String sourceRevision, String extractionModel, String promptVersion,
                             SnapshotStatus status, int entityCount, int relationCount,
                             Instant createdAt, Instant updatedAt, Instant publishedAt) {
            this(id, businessProjectId, documentId, requirementVersion, sourceRevision, extractionModel,
                    promptVersion, status, entityCount, relationCount, createdAt, updatedAt, publishedAt,
                    1, "v1", 1.0, 0, 0, 0, 0, null, null, null, null);
        }
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
            EntityStatus status,
            ClaimStatus claimStatus,
            String normalizedBy,
            String contextKey,
            String firstSeenWindowId,
            String lastSeenWindowId,
            List<String> uncertaintyIds,
            List<String> conflictSetIds,
            String reviewer,
            Instant reviewedAt,
            String reviewReason
    ) {
        public Entity {
            aliases = immutable(aliases);
            sourceEvidenceIds = immutable(sourceEvidenceIds);
            sourceParentIds = immutable(sourceParentIds);
            sourceContentHashes = immutable(sourceContentHashes);
            uncertaintyIds = immutable(uncertaintyIds);
            conflictSetIds = immutable(conflictSetIds);
            claimStatus = claimStatus == null ? claimStatusFrom(status) : claimStatus;
        }

        /** Compatibility constructor for legacy parent-level claims. */
        public Entity(String id, String snapshotId, EntityType type, String canonicalName, String displayName,
                      List<String> aliases, String description, List<String> sourceEvidenceIds,
                      List<String> sourceParentIds, List<String> sourceContentHashes, double confidence,
                      EntityStatus status) {
            this(id, snapshotId, type, canonicalName, displayName, aliases, description, sourceEvidenceIds,
                    sourceParentIds, sourceContentHashes, confidence, status, claimStatusFrom(status), null,
                    "", null, null, List.of(), List.of(), null, null, null);
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
            Instant reviewedAt,
            ClaimStatus claimStatus,
            String condition,
            String scenario,
            List<String> statementVariants,
            List<String> uncertaintyIds,
            List<String> conflictSetIds,
            String reviewReason
    ) {
        public Relation {
            sourceEvidenceIds = immutable(sourceEvidenceIds);
            statementVariants = immutable(statementVariants);
            uncertaintyIds = immutable(uncertaintyIds);
            conflictSetIds = immutable(conflictSetIds);
            claimStatus = claimStatus == null ? claimStatusFrom(status) : claimStatus;
        }

        /** Compatibility constructor for legacy relation claims. */
        public Relation(String id, String snapshotId, String sourceEntityId, RelationType type,
                        String targetEntityId, String statement, List<String> sourceEvidenceIds,
                        double confidence, RelationStatus status, String reviewer, Instant reviewedAt) {
            this(id, snapshotId, sourceEntityId, type, targetEntityId, statement, sourceEvidenceIds,
                    confidence, status, reviewer, reviewedAt, claimStatusFrom(status), "", "",
                    statement == null ? List.of() : List.of(statement), List.of(), List.of(), null);
        }
    }

    public record Evidence(
            String evidenceId,
            String filename,
            String parentId,
            int parentOrder,
            String version,
            String excerpt,
            String contentHash,
            String sectionPath,
            String quote,
            int startOffset,
            int endOffset,
            EvidenceResolutionStatus resolutionStatus
    ) {
        /** Compatibility constructor for legacy parent-level evidence. */
        public Evidence(String evidenceId, String filename, String parentId, int parentOrder,
                        String version, String excerpt, String contentHash) {
            this(evidenceId, filename, parentId, parentOrder, version, excerpt, contentHash, "",
                    excerpt, -1, -1, EvidenceResolutionStatus.LEGACY_PARENT_ONLY);
        }
    }

    public record ClaimEvidence(
            String snapshotId,
            String claimId,
            String evidenceId,
            String supportType,
            double confidence,
            Instant createdAt
    ) {
    }

    public record Uncertainty(
            String id,
            String snapshotId,
            String windowId,
            String code,
            String message,
            List<String> claimIds,
            ClaimStatus status,
            Instant createdAt
    ) {
        public Uncertainty {
            claimIds = immutable(claimIds);
        }
    }

    public record Conflict(
            String id,
            String snapshotId,
            String kind,
            List<String> claimIds,
            String description,
            ClaimStatus status,
            Instant createdAt
    ) {
        public Conflict {
            claimIds = immutable(claimIds);
        }
    }

    public record RequirementGraphWindowView(
            String id,
            String snapshotId,
            String filename,
            String parentId,
            String sectionPath,
            String heading,
            int windowIndex,
            int startOffset,
            int endOffset,
            String contentHash,
            WindowStatus status,
            int attemptCount,
            String lastErrorCode,
            Instant startedAt,
            Instant completedAt,
            String continuationOf
    ) {
    }

    public record AuditEntry(
            String id,
            String snapshotId,
            String claimId,
            String action,
            String actor,
            String reason,
            Instant occurredAt
    ) {
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
            aliases = immutable(aliases);
            evidenceQuotes = immutable(evidenceQuotes);
        }
    }

    public record ExtractedRelation(
            String sourceLocalId,
            String type,
            String targetLocalId,
            String statement,
            List<String> evidenceQuotes,
            double confidence,
            String condition,
            String scenario
    ) {
        public ExtractedRelation {
            evidenceQuotes = immutable(evidenceQuotes);
        }

        /** Compatibility constructor for the original extraction schema. */
        public ExtractedRelation(String sourceLocalId, String type, String targetLocalId, String statement,
                                 List<String> evidenceQuotes, double confidence) {
            this(sourceLocalId, type, targetLocalId, statement, evidenceQuotes, confidence, "", "");
        }
    }

    public record ExtractionResult(
            List<ExtractedEntity> entities,
            List<ExtractedRelation> relations,
            List<String> uncertainties
    ) {
        public ExtractionResult {
            entities = immutable(entities);
            relations = immutable(relations);
            uncertainties = immutable(uncertainties);
        }
    }

    public record ExtractionInput(
            String filename,
            String parentId,
            int parentOrder,
            String sectionPath,
            String heading,
            String contentHash,
            String text,
            String windowId,
            int startOffset,
            int endOffset
    ) {
        /** Compatibility constructor for parent-level extraction. */
        public ExtractionInput(String filename, String parentId, int parentOrder, String sectionPath,
                               String heading, String contentHash, String text) {
            this(filename, parentId, parentOrder, sectionPath, heading, contentHash, text,
                    null, 0, text == null ? 0 : text.length());
        }
    }

    public record BuildRequest(
            @NotBlank String projectId,
            @NotBlank String documentId,
            @NotBlank String requirementVersion,
            String collection,
            String resumeSnapshotId,
            Boolean allowPartial,
            String buildId
    ) {
        public BuildRequest(String projectId, String documentId, String requirementVersion, String collection) {
            this(projectId, documentId, requirementVersion, collection, null, null, null);
        }

        public BuildRequest(String projectId, String documentId, String requirementVersion, String collection,
                            String resumeSnapshotId, Boolean allowPartial) {
            this(projectId, documentId, requirementVersion, collection, resumeSnapshotId, allowPartial, null);
        }
    }

    public enum BuildJobState { QUEUED, RUNNING, SUCCEEDED, PARTIAL_FAILED, FAILED, CANCELLED }

    public record BuildJob(
            String buildId, String snapshotId, String projectId, String documentId,
            String requirementVersion, BuildJobState state, int completedWindows, int totalWindows,
            String errorCode, String errorMessage, Instant createdAt, Instant startedAt, Instant finishedAt
    ) {
    }

    public record SearchRequest(
            @NotBlank String projectId,
            @NotBlank String documentId,
            @NotBlank String requirementVersion,
            @NotBlank String query,
            SearchMode mode,
            @Min(1) @Max(50) Integer limit,
            @Min(0) @Max(4) Integer maxHops,
            List<ClaimStatus> statuses,
            Boolean includeUnresolved,
            @Min(0) Integer page
    ) {
        public SearchRequest(String projectId, String documentId, String requirementVersion, String query,
                             SearchMode mode, Integer limit, Integer maxHops) {
            this(projectId, documentId, requirementVersion, query, mode, limit, maxHops,
                    List.of(ClaimStatus.values()), false, 0);
        }
    }

    public record QueryPlan(
            SearchMode mode,
            List<String> entityKeywords,
            List<String> relationKeywords,
            List<String> sectionKeywords,
            int maxHops,
            int maxEntities,
            int maxRelations,
            int maxEvidence,
            Set<ClaimStatus> allowedStatuses
    ) {
        public QueryPlan {
            entityKeywords = immutable(entityKeywords);
            relationKeywords = immutable(relationKeywords);
            sectionKeywords = immutable(sectionKeywords);
            allowedStatuses = allowedStatuses == null ? Set.of() : Set.copyOf(allowedStatuses);
        }
    }

    public record SearchResponse(
            GraphSnapshot snapshot,
            List<Entity> entities,
            List<Relation> relations,
            List<Evidence> evidence,
            List<RagWarning> warnings,
            int total,
            boolean truncated,
            int page,
            int pageSize,
            List<ChunkRecord> sourceChunks,
            List<GraphPath> paths,
            QueryPlan plan,
            Map<String, Double> channelScores
    ) {
        public SearchResponse {
            entities = immutable(entities);
            relations = immutable(relations);
            evidence = immutable(evidence);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            sourceChunks = immutable(sourceChunks);
            paths = immutable(paths);
            channelScores = channelScores == null ? Map.of() : Map.copyOf(channelScores);
        }

        public SearchResponse(GraphSnapshot snapshot, List<Entity> entities, List<Relation> relations,
                              List<Evidence> evidence, List<RagWarning> warnings,
                              int total, boolean truncated, int page, int pageSize) {
            this(snapshot, entities, relations, evidence, warnings, total, truncated, page, pageSize,
                    List.of(), List.of(), null, Map.of());
        }

        public SearchResponse(GraphSnapshot snapshot, List<Entity> entities, List<Relation> relations,
                              List<Evidence> evidence) {
            this(snapshot, entities, relations, evidence, List.of(),
                    Math.max(entities == null ? 0 : entities.size(), relations == null ? 0 : relations.size()),
                    false, 0, 0);
        }

        }

    public record ClaimPage(List<Entity> entities, List<Relation> relations, int total, boolean truncated) {
        public ClaimPage {
            entities = immutable(entities);
            relations = immutable(relations);
        }
    }

    public record ReviewAction(
            String reviewer,
            String reason,
            String targetClaimId,
            String newName,
            String newStatement,
            String newType,
            String newRelationType,
            String newTargetEntityId,
            ClaimStatus status
    ) {
    }

    public record ClaimPatch(
            String displayName,
            String description,
            String statement,
            String condition,
            String scenario,
            String reason
    ) {
    }

    public record ClaimDecision(String reason) {
    }

    public record NeighborhoodResponse(
            GraphSnapshot snapshot,
            String centerEntityId,
            List<Entity> entities,
            List<Relation> relations,
            List<Evidence> evidence,
            List<RagWarning> warnings,
            int total,
            boolean truncated,
            int maxHops
    ) {
        public NeighborhoodResponse {
            entities = immutable(entities);
            relations = immutable(relations);
            evidence = immutable(evidence);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    public record GraphPath(
            List<String> entityIds,
            List<String> relationIds,
            int hops,
            double score
    ) {
        public GraphPath {
            entityIds = immutable(entityIds);
            relationIds = immutable(relationIds);
        }
    }

    public record PathResponse(
            GraphSnapshot snapshot,
            List<GraphPath> paths,
            List<Entity> entities,
            List<Relation> relations,
            List<Evidence> evidence,
            List<RagWarning> warnings,
            int total,
            boolean truncated
    ) {
        public PathResponse {
            paths = immutable(paths);
            entities = immutable(entities);
            relations = immutable(relations);
            evidence = immutable(evidence);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }
    }

    public record GraphPage<T>(List<T> items, int total) {
        public GraphPage {
            items = immutable(items);
        }
    }

    private static ClaimStatus claimStatusFrom(EntityStatus status) {
        if (status == null) return ClaimStatus.EXTRACTED;
        return switch (status) {
            case VERIFIED -> ClaimStatus.VERIFIED;
            case REJECTED -> ClaimStatus.REJECTED;
            case STALE -> ClaimStatus.STALE;
            default -> ClaimStatus.EXTRACTED;
        };
    }

    private static ClaimStatus claimStatusFrom(RelationStatus status) {
        if (status == null) return ClaimStatus.EXTRACTED;
        return switch (status) {
            case VERIFIED -> ClaimStatus.VERIFIED;
            case REJECTED -> ClaimStatus.REJECTED;
            case STALE -> ClaimStatus.STALE;
            default -> ClaimStatus.EXTRACTED;
        };
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
