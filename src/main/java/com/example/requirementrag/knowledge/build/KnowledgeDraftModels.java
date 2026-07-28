package com.example.requirementrag.knowledge.build;

import com.example.requirementrag.wiki.WikiModels.GenerationResult;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Persisted contracts for reviewing, publishing and rolling back knowledge drafts. */
public final class KnowledgeDraftModels {
    private KnowledgeDraftModels() {}

    public enum DraftStatus {
        DRAFT,
        IN_REVIEW,
        APPROVED,
        REJECTED,
        PUBLISHED,
        SPLIT,
        MERGED
    }

    public record TransitionRequest(
            @NotNull DraftStatus targetStatus,
            @Size(max = 1000) String comment
    ) {}

    public record AuditEntry(
            DraftStatus fromStatus,
            DraftStatus toStatus,
            String actor,
            String occurredAt,
            String comment
    ) {}

    public record Publication(
            String publicationId,
            String publishedAt,
            String publishedBy,
            String previousSnapshotId,
            String rolledBackAt,
            String rolledBackBy,
            String rollbackComment
    ) {}

    public record DraftMetadata(
            String buildId,
            String projectId,
            String version,
            DraftStatus status,
            long revision,
            String createdAt,
            String updatedAt,
            String createdBy,
            List<AuditEntry> history,
            Publication publication
    ) {
        public DraftMetadata {
            history = history == null ? List.of() : List.copyOf(history);
        }
    }

    public record PublishResult(DraftMetadata draft, GenerationResult wiki) {}

    public record RollbackResult(DraftMetadata draft, GenerationResult wiki) {}
}
