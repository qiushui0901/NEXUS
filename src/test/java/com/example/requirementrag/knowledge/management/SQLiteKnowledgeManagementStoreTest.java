package com.example.requirementrag.knowledge.management;

import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SQLiteKnowledgeManagementStoreTest {
    @TempDir Path tempDir;

    @Test
    void persistsKnowledgeStateAndMarksRunningJobsInterruptedAfterRestart() {
        Path database = tempDir.resolve("knowledge.db");
        SQLiteKnowledgeManagementStore first = store(database);
        KnowledgeBaseView base = first.ensureBase("orders", "订单需求", BaseType.REQUIREMENT,
                "requirements_orders", SourceType.ZIP, "1.0");
        RunView run = first.startRun(base.id(), TriggerType.BOOTSTRAP, "1.0");
        first.updateRun(run.id(), Stage.CHUNK, 10, 3, "rules/order.html");

        SQLiteKnowledgeManagementStore restarted = store(database);
        RunView recovered = restarted.requireRun(base.id(), run.id());

        assertThat(recovered.status()).isEqualTo(EntityStatus.INTERRUPTED);
        assertThat(recovered.error().code()).isEqualTo("APPLICATION_RESTARTED");
        assertThat(restarted.requireBase(base.id()).status()).isEqualTo(SummaryStatus.FAILED);
        assertThat(restarted.listBases("orders", 0, 20).total()).isEqualTo(1);
    }

    @Test
    void storesDocumentsChunksEventsAndPublishesOnlyOnCompletion() {
        SQLiteKnowledgeManagementStore store = store(tempDir.resolve("knowledge.db"));
        KnowledgeBaseView base = store.ensureBase("orders", "订单需求", BaseType.REQUIREMENT,
                "requirements_orders", SourceType.ZIP, "2.0");
        RunView run = store.startRun(base.id(), TriggerType.MANUAL, "2.0");
        KnowledgeIngestionTracker.Context context = new KnowledgeIngestionTracker.Context(
                base.id(), run.id(), "2.0", true);
        KnowledgeIngestionTracker tracker = new KnowledgeIngestionTracker(
                new org.springframework.beans.factory.support.DefaultListableBeanFactory() {{
                    registerSingleton("store", store);
                }}.getBeanProvider(SQLiteKnowledgeManagementStore.class), new KnowledgeErrorSanitizer());

        tracker.document(context, "rules/order.html", "hash", EntityStatus.CHUNKED,
                Stage.CHUNK, 1, 0, null);
        String documentId = tracker.documentId(context, "rules/order.html");
        store.event(run.id(), "DOCUMENT", documentId, Stage.CHUNK,
                EventStatus.SUCCEEDED, 1, 1, 0, null);
        store.finishRun(base.id(), run.id(), "2.0", 1);

        assertThat(store.listDocuments(base.id(), 0, 20).items()).hasSize(1);
        assertThat(store.events(run.id())).hasSize(1);
        assertThat(store.requireBase(base.id()).status()).isEqualTo(SummaryStatus.READY);
        assertThat(store.requireBase(base.id()).publishedRevision()).isEqualTo("2.0");
    }

    @Test
    void removesDocumentsAndChunksMissingFromTheNewPublishedSnapshot() {
        SQLiteKnowledgeManagementStore store = store(tempDir.resolve("knowledge.db"));
        KnowledgeBaseView base = store.ensureBase("orders", "订单需求", BaseType.REQUIREMENT,
                "requirements_orders", SourceType.ZIP, "1.0");
        RunView firstRun = store.startRun(base.id(), TriggerType.BOOTSTRAP, "1.0");
        Instant firstPublishedAt = Instant.now();
        store.upsertDocument(document("doc-a", base.id(), firstRun.id(), firstPublishedAt));
        store.upsertDocument(document("doc-b", base.id(), firstRun.id(), firstPublishedAt));
        store.upsertChunks(List.of(
                chunk("chunk-a-1", "doc-a", firstRun.id(), 1, 1),
                chunk("chunk-a-2", "doc-a", firstRun.id(), 1, 2),
                chunk("chunk-b-1", "doc-b", firstRun.id(), 1, 1)));
        store.finishRun(base.id(), firstRun.id(), "1.0", 3);

        RunView secondRun = store.startRun(base.id(), TriggerType.MANUAL, "2.0");
        store.upsertDocument(document("doc-a", base.id(), secondRun.id(), Instant.now()));
        store.upsertChunks(List.of(chunk("chunk-a-1", "doc-a", secondRun.id(), 1, 1)));
        store.finishRun(base.id(), secondRun.id(), "2.0", 1);

        assertThat(store.listDocuments(base.id(), 0, 20).items())
                .extracting(DocumentView::id)
                .containsExactly("doc-a");
        assertThat(store.listChunks("doc-a", 0, 20).items())
                .extracting(ChunkView::chunkId)
                .containsExactly("chunk-a-1");
        assertThat(store.requireBase(base.id())).satisfies(published -> {
            assertThat(published.documentCount()).isEqualTo(1);
            assertThat(published.readyDocumentCount()).isEqualTo(1);
            assertThat(published.chunkCount()).isEqualTo(1);
            assertThat(published.publishedRevision()).isEqualTo("2.0");
        });
    }

    @Test
    void usesStableDescendingHistoryAndAscendingChunkOrder() {
        SQLiteKnowledgeManagementStore store = store(tempDir.resolve("knowledge.db"));
        KnowledgeBaseView base = store.ensureBase("orders", "订单需求", BaseType.REQUIREMENT,
                "requirements_orders", SourceType.ZIP, "2.0");
        RunView firstRun = store.startRun(base.id(), TriggerType.MANUAL, "1.0");
        RunView secondRun = store.startRun(base.id(), TriggerType.RETRY, "2.0");
        Instant now = Instant.now();
        DocumentView older = document("doc-old", base.id(), firstRun.id(), now.minusSeconds(10));
        DocumentView newer = document("doc-new", base.id(), secondRun.id(), now);
        store.upsertDocument(older);
        store.upsertDocument(newer);
        store.upsertChunks(List.of(
                chunk("chunk-2", newer.id(), secondRun.id(), 2, 0),
                chunk("chunk-1-2", newer.id(), secondRun.id(), 1, 2),
                chunk("chunk-1-1", newer.id(), secondRun.id(), 1, 1)));

        assertThat(store.listRuns(base.id(), 0, 20).items())
                .extracting(RunView::id)
                .containsExactly(secondRun.id(), firstRun.id());
        assertThat(store.listDocuments(base.id(), 0, 20).items())
                .extracting(DocumentView::id)
                .containsExactly(newer.id(), older.id());
        assertThat(store.listChunks(newer.id(), 0, 20).items())
                .extracting(ChunkView::chunkId)
                .containsExactly("chunk-1-1", "chunk-1-2", "chunk-2");
    }

    @Test
    void filtersServerSideAndKeepsPublishedIndexAvailableAfterRestart() {
        Path database = tempDir.resolve("knowledge.db");
        SQLiteKnowledgeManagementStore store = store(database);
        KnowledgeBaseView base = store.ensureBase("orders", "订单需求", BaseType.REQUIREMENT,
                "requirements_orders", SourceType.ZIP, "1.0");
        RunView published = store.startRun(base.id(), TriggerType.BOOTSTRAP, "1.0");
        store.finishRun(base.id(), published.id(), "1.0", 1);
        RunView interrupted = store.startRun(base.id(), TriggerType.MANUAL, "2.0");
        Instant now = Instant.now();
        store.upsertDocument(new DocumentView("doc-ready", base.id(), published.id(), "rules/order.html",
                "hash-ready", "1.0", EntityStatus.READY, Stage.PUBLISH, 1, 0, null,
                now, now, now));
        store.upsertDocument(new DocumentView("doc-running", base.id(), interrupted.id(), "rules/payment.html",
                "hash-running", "2.0", EntityStatus.RUNNING, Stage.CHUNK, 0, 0, null,
                now, null, now));
        store.upsertChunks(List.of(chunk("chunk-order", "doc-ready", published.id(), 0, 0)));

        SQLiteKnowledgeManagementStore restarted = store(database);

        assertThat(restarted.requireBase(base.id()).status()).isEqualTo(SummaryStatus.STALE);
        assertThat(restarted.requireBase(base.id()).publishedRevision()).isEqualTo("1.0");
        assertThat(restarted.requireDocument(base.id(), "doc-running").status()).isEqualTo(EntityStatus.INTERRUPTED);
        assertThat(restarted.listBases(null, SummaryStatus.STALE, BaseType.REQUIREMENT,
                "requirements_orders", 0, 20).total()).isEqualTo(1);
        assertThat(restarted.listDocuments(base.id(), EntityStatus.READY, Stage.PUBLISH,
                "order", 0, 20).items()).extracting(DocumentView::id).containsExactly("doc-ready");
        assertThat(restarted.listChunks("doc-ready", EntityStatus.READY,
                "chunk-order", 0, 20).total()).isEqualTo(1);
    }

    @Test
    void readsAllBasesForProjectsWithoutThePageSizeCap() {
        SQLiteKnowledgeManagementStore store = store(tempDir.resolve("knowledge.db"));
        List<String> projects = new java.util.ArrayList<>();
        for (int index = 0; index < 205; index++) {
            String projectId = "project-" + index;
            projects.add(projectId);
            store.ensureBase(projectId, "Project " + index, BaseType.REQUIREMENT,
                    "requirements_" + index, SourceType.ZIP, "1.0");
        }

        assertThat(store.listBasesForProjects(projects, 0, 10_000).items()).hasSize(200);
        assertThat(store.allBasesForProjects(projects)).hasSize(205);
    }

    private DocumentView document(String id, String baseId, String runId, Instant updatedAt) {
        return new DocumentView(id, baseId, runId, "rules/" + id + ".html", "hash-" + id, "2.0",
                EntityStatus.READY, Stage.PUBLISH, 3, 0, null,
                updatedAt.minusSeconds(1), updatedAt, updatedAt);
    }

    private ChunkView chunk(String id, String documentId, String runId, int parentOrder, int childOrder) {
        return new ChunkView(id, documentId, runId, "parent-" + parentOrder,
                parentOrder, childOrder, "hash-" + id, EntityStatus.READY, Stage.PUBLISH,
                true, true, true, 0, null, Instant.now());
    }

    private SQLiteKnowledgeManagementStore store(Path database) {
        SQLiteKnowledgeManagementStore store = new SQLiteKnowledgeManagementStore(
                new KnowledgeManagementProperties(true, database.toString()));
        store.initialize();
        return store;
    }
}
