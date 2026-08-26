package com.example.requirementrag.knowledge.management;

import com.example.requirementrag.model.RagOutcomeStatus;
import com.example.requirementrag.model.RagStageDiagnostic;
import com.example.requirementrag.model.RagWarning;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;

/** 知识管理 API 与持久化层共享的稳定契约。 */
public final class KnowledgeManagementModels {
    private KnowledgeManagementModels() {}

    public enum BaseType { REQUIREMENT, CODE, WIKI }
    public enum SourceType { ZIP, GITLAB, GENERATED, UPLOAD }
    public enum SummaryStatus { IDLE, QUEUED, RUNNING, READY, PARTIAL, FAILED, STALE, DISABLED, UNAVAILABLE }
    public enum EntityStatus { PENDING, RUNNING, CHUNKED, EMBEDDING, INDEXING, READY, FAILED, EXCLUDED, STALE, INTERRUPTED }
    public enum Stage { DISCOVER, PARSE, CLEAN, CHUNK, DEDUPLICATE, EMBED, INDEX, VERIFY, PUBLISH }
    public enum TriggerType { BOOTSTRAP, MANUAL, GITLAB, RETRY }
    public enum EventStatus { PENDING, RUNNING, SUCCEEDED, FAILED, SKIPPED }

    public record Page<T>(List<T> items, int page, int size, long total) {
        public Page {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record SafeError(String code, String message) {}

    public record KnowledgeBaseView(
            String id, String projectId, String name, BaseType type, String collection,
            SourceType sourceType, SummaryStatus status, String publishedRevision,
            String targetRevision, long documentCount, long readyDocumentCount,
            long failedDocumentCount, long chunkCount, Instant lastPublishedAt,
            Instant createdAt, Instant updatedAt,
            String requirementDocumentId, String latestRequirementVersion
    ) {
        /** 兼容旧构造：非业务项目知识库没有需求文档标识。 */
        public KnowledgeBaseView(String id, String projectId, String name, BaseType type, String collection,
                                 SourceType sourceType, SummaryStatus status, String publishedRevision,
                                 String targetRevision, long documentCount, long readyDocumentCount,
                                 long failedDocumentCount, long chunkCount, Instant lastPublishedAt,
                                 Instant createdAt, Instant updatedAt) {
            this(id, projectId, name, type, collection, sourceType, status, publishedRevision, targetRevision,
                    documentCount, readyDocumentCount, failedDocumentCount, chunkCount,
                    lastPublishedAt, createdAt, updatedAt, null, null);
        }
    }

    public record RunView(
            String id, String knowledgeBaseId, TriggerType triggerType, EntityStatus status,
            Stage phase, String targetRevision, int filesTotal, int filesProcessed,
            int chunksTotal, int chunksReady, int chunksFailed, String currentFile,
            SafeError error, Instant startedAt, Instant finishedAt, String correlationId
    ) {}

    public record DocumentView(
            String id, String knowledgeBaseId, String runId, String sourcePath, String sourceHash,
            String revision, EntityStatus status, Stage phase, int chunkCount,
            int excludedChunkCount, SafeError error, Instant startedAt, Instant finishedAt,
            Instant updatedAt
    ) {}

    public record ChunkView(
            String chunkId, String documentId, String runId, String parentId,
            int parentOrder, int childOrder, String contentHash, EntityStatus status,
            Stage phase, boolean denseReady, boolean sparseReady, boolean qdrantVerified,
            int retryCount, SafeError error, Instant indexedAt
    ) {}

    public record StageEventView(
            String id, String runId, String entityType, String entityId, Stage stage,
            EventStatus status, int inputCount, int outputCount, int excludedCount,
            SafeError error, Instant occurredAt
    ) {}

    public record RetrievalTestRequest(
            @NotBlank String query,
            String documentId,
            String version,
            @Min(1) @Max(50) Integer limit
    ) {}

    public record RetrievalTestResponse(
            RagOutcomeStatus status,
            String projectId,
            String documentId,
            String version,
            List<RetrievalHit> hits,
            List<CodeHit> codeHits,
            List<RagWarning> warnings,
            List<RagStageDiagnostic> stageDiagnostics
    ) {
        public RetrievalTestResponse {
            hits = hits == null ? List.of() : List.copyOf(hits);
            codeHits = codeHits == null ? List.of() : List.copyOf(codeHits);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            stageDiagnostics = stageDiagnostics == null ? List.of() : List.copyOf(stageDiagnostics);
        }
    }

    public record RetrievalHit(
            int rank,
            String chunkId,
            String documentId,
            String version,
            String sourcePath,
            String sectionPath,
            String heading,
            String requirementId,
            String module,
            String acceptanceCriteria,
            String parentId,
            int parentOrder,
            int childOrder,
            String contentHash,
            String childText,
            String parentText
    ) {}

    public record CodeHit(
            int rank,
            String chunkId,
            String projectId,
            String commitSha,
            String filePath,
            String symbolType,
            String symbolName,
            int startLine,
            int endLine,
            String text,
            String contentHash,
            String language,
            String repositoryId,
            String repositoryName,
            String repositoryKind
    ) {}

    public record ActionAccepted(String status, String mode, String projectId) {}
}
