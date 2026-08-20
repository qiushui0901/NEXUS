package com.example.requirementrag.knowledge.management;

import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.*;
import com.example.requirementrag.model.ChunkRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** 导入状态旁路写入器。状态库失败只记录告警，不覆盖索引主流程异常。 */
@Component
public class KnowledgeIngestionTracker {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestionTracker.class);
    private final SQLiteKnowledgeManagementStore store;
    private final KnowledgeErrorSanitizer sanitizer;

    public KnowledgeIngestionTracker(ObjectProvider<SQLiteKnowledgeManagementStore> store,
                                     KnowledgeErrorSanitizer sanitizer) {
        this.store = store.getIfAvailable();
        this.sanitizer = sanitizer;
    }

    public Context start(String projectId, String name, String collection, String revision, TriggerType trigger) {
        return start(projectId, name, collection, revision, trigger, SourceType.ZIP);
    }

    public Context start(String projectId, String name, String collection, String revision,
                         TriggerType trigger, SourceType sourceType) {
        if (store == null) return Context.disabled();
        return safe(() -> {
            KnowledgeBaseView base = store.ensureBase(projectId, name, BaseType.REQUIREMENT, collection,
                    sourceType == null ? SourceType.ZIP : sourceType, revision);
            RunView run = store.startRun(base.id(), trigger, revision);
            return new Context(base.id(), run.id(), revision, true);
        }, Context.disabled());
    }

    public String documentId(Context context, String sourcePath) {
        return sha256(context.knowledgeBaseId() + ":" + normalize(sourcePath));
    }

    public void progress(Context context, Stage stage, int total, int processed, String currentFile) {
        if (!context.enabled()) return;
        safe(() -> store.updateRun(context.runId(), stage, total, processed, currentFile));
    }

    public void chunkProgress(Context context, Stage stage, int total, int ready, int failed) {
        if (!context.enabled()) return;
        safe(() -> store.updateRunChunks(context.runId(), stage, total, ready, failed));
    }

    public void document(Context context, String sourcePath, String sourceHash, EntityStatus status,
                         Stage phase, int chunks, int excluded, Throwable error) {
        if (!context.enabled()) return;
        safe(() -> {
            String id = documentId(context, sourcePath);
            Instant now = Instant.now();
            store.upsertDocument(new DocumentView(id, context.knowledgeBaseId(), context.runId(),
                    normalize(sourcePath), sourceHash, context.revision(), status, phase, chunks, excluded,
                    sanitizer.sanitize(error), now, terminal(status) ? now : null, now));
        });
    }

    public void chunks(Context context, String sourcePath, List<ChunkRecord> chunks, EntityStatus status, Stage phase) {
        boolean ready = status == EntityStatus.READY;
        chunks(context, sourcePath, chunks, status, phase, ready, ready, ready, null);
    }

    public void chunks(Context context, String sourcePath, List<ChunkRecord> chunks,
                       EntityStatus status, Stage phase, boolean denseReady,
                       boolean sparseReady, boolean qdrantVerified, Throwable error) {
        if (!context.enabled()) return;
        safe(() -> {
            String documentId = documentId(context, sourcePath);
            SafeError safeError = sanitizer.sanitize(error);
            List<ChunkView> views = chunks.stream().map(chunk -> new ChunkView(
                    chunk.id(), documentId, context.runId(), chunk.parentId(), chunk.parentOrder(),
                    chunk.childOrder(), chunk.contentHash(), status, phase,
                    denseReady, sparseReady, qdrantVerified, 0, safeError,
                    qdrantVerified ? Instant.now() : null)).toList();
            store.upsertChunks(views);
        });
    }

    public void event(Context context, String entityType, String entityId, Stage stage, EventStatus status,
                      int input, int output, int excluded, Throwable error) {
        if (!context.enabled()) return;
        safe(() -> store.event(context.runId(), entityType, entityId, stage, status,
                input, output, excluded, sanitizer.sanitize(error)));
    }

    public void complete(Context context, int chunks) {
        if (!context.enabled()) return;
        safe(() -> store.finishRun(context.knowledgeBaseId(), context.runId(), context.revision(), chunks));
    }

    public void fail(Context context, Throwable error) {
        if (!context.enabled()) return;
        safe(() -> store.failRun(context.knowledgeBaseId(), context.runId(), sanitizer.sanitize(error)));
    }

    private boolean terminal(EntityStatus status) {
        return status == EntityStatus.READY || status == EntityStatus.FAILED
                || status == EntityStatus.EXCLUDED || status == EntityStatus.INTERRUPTED;
    }
    private String normalize(String sourcePath) {
        return sourcePath == null ? "unknown" : sourcePath.replace('\\', '/').replaceAll("^/+", "");
    }
    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
    private void safe(ThrowingRunnable action) {
        try { action.run(); } catch (RuntimeException exception) {
            log.warn("Knowledge management status update failed: {}", exception.getClass().getSimpleName());
        }
    }
    private <T> T safe(java.util.concurrent.Callable<T> action, T fallback) {
        try { return action.call(); } catch (Exception exception) {
            log.warn("Knowledge management status initialization failed: {}", exception.getClass().getSimpleName());
            return fallback;
        }
    }
    @FunctionalInterface private interface ThrowingRunnable { void run(); }
    public record Context(String knowledgeBaseId, String runId, String revision, boolean enabled) {
        public static Context disabled() { return new Context("", "", "", false); }
    }
}
