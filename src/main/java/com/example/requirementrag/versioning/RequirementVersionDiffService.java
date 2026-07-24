package com.example.requirementrag.versioning;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import com.example.requirementrag.versioning.VersionModels.ChangeType;
import com.example.requirementrag.versioning.VersionModels.RequirementChange;
import com.example.requirementrag.versioning.VersionModels.RequirementDiff;
import com.example.requirementrag.versioning.VersionModels.VersionManifest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Compares requirement parent chunks using payload-only Qdrant scrolls. */
@Service
public class RequirementVersionDiffService {
    private static final int EXCERPT_LIMIT = 360;

    private final QdrantHybridStore store;
    private final ProjectRegistry projectRegistry;

    public RequirementVersionDiffService(QdrantHybridStore store, ProjectRegistry projectRegistry) {
        this.store = store;
        this.projectRegistry = projectRegistry;
    }

    public RequirementDiff compare(String projectId, VersionManifest from, VersionManifest to) {
        if (!hasReference(from) || !hasReference(to)) return RequirementDiff.unavailable();
        String collection = projectRegistry.resolveRequirementCollection(projectId);
        List<RequirementChunkDiff.ParentChange> parentChanges = RequirementChunkDiff.compare(
                store.scrollVersion(collection, from.requirementDocumentId(), from.requirementVersion()),
                store.scrollVersion(collection, to.requirementDocumentId(), to.requirementVersion()));
        List<RequirementChange> changes = parentChanges.stream()
                .map(item -> change(ChangeType.valueOf(item.type().name()), item.before(), item.after()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        changes.sort(Comparator.comparing(RequirementChange::filename,
                        Comparator.nullsFirst(String::compareTo))
                .thenComparingInt(RequirementChange::parentOrder)
                .thenComparing(change -> change.type().name()));
        return new RequirementDiff(VersionModels.Availability.AVAILABLE,
                count(changes, ChangeType.ADDED), count(changes, ChangeType.MODIFIED),
                count(changes, ChangeType.REMOVED), changes);
    }

    private RequirementChange change(ChangeType type, ChunkRecord before, ChunkRecord after) {
        ChunkRecord source = after != null ? after : before;
        return new RequirementChange(type, source.filename(), source.parentId(), source.parentOrder(),
                before == null ? null : RequirementChunkDiff.hash(before), after == null ? null : RequirementChunkDiff.hash(after),
                before == null ? null : excerpt(before.parentText()), after == null ? null : excerpt(after.parentText()));
    }

    private int count(List<RequirementChange> changes, ChangeType type) {
        return (int) changes.stream().filter(change -> change.type() == type).count();
    }

    private boolean hasReference(VersionManifest manifest) {
        return hasText(manifest.requirementDocumentId()) && hasText(manifest.requirementVersion());
    }

    private String excerpt(String text) {
        String normalized = safe(text).replaceAll("\\s+", " ").trim();
        return normalized.length() <= EXCERPT_LIMIT ? normalized : normalized.substring(0, EXCERPT_LIMIT) + "…";
    }

    private String safe(String value) { return value == null ? "" : value; }
    private boolean hasText(String value) { return value != null && !value.isBlank(); }
}
