package com.example.requirementrag.versioning;

import java.util.List;

/** Reviewable requirement-version facts stored without embeddings or vector database payloads. */
public final class RequirementSnapshotModels {
    private RequirementSnapshotModels() {}

    public enum Operation {
        UPSERT,
        REMOVE
    }

    public record Source(
            String path,
            String location,
            String contentHash,
            long bytes
    ) {}

    public record Entry(
            String entryId,
            String filename,
            int parentOrder,
            String text,
            String contentHash,
            Operation operation
    ) {
        /** Keeps source and test code compatible with schema-v1 entries that predate explicit operations. */
        public Entry(String entryId, String filename, int parentOrder, String text, String contentHash) {
            this(entryId, filename, parentOrder, text, contentHash, null);
        }

        public Operation effectiveOperation() {
            return operation == null ? Operation.UPSERT : operation;
        }
    }

    public record Snapshot(
            int schemaVersion,
            String projectId,
            String documentId,
            String requirementVersion,
            String baseRequirementVersion,
            List<String> aliases,
            String generatedAt,
            List<Source> sources,
            List<Entry> entries
    ) {
        public Snapshot {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            sources = sources == null ? List.of() : List.copyOf(sources);
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }
}
