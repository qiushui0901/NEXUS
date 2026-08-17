package com.example.requirementrag.service;

import com.example.requirementrag.model.KnowledgeEntry;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.model.IngestResponse;
import com.example.requirementrag.knowledge.management.KnowledgeIngestionTracker;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.EventStatus;
import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.Stage;
import com.example.requirementrag.observability.RagObservability;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import com.example.requirementrag.retrieval.pipeline.RetrievalResultCache;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class RequirementIngestionServiceTest {

    private final QdrantHybridStore store = mock(QdrantHybridStore.class);
    private final TextPreprocessor preprocessor = mock(TextPreprocessor.class);
    private final ParentChildChunker chunker = mock(ParentChildChunker.class);
    private final RagObservability observability = mock(RagObservability.class);
    private final RetrievalResultCache resultCache = mock(RetrievalResultCache.class);
    private final com.example.requirementrag.config.ProjectRegistry projectRegistry =
            mock(com.example.requirementrag.config.ProjectRegistry.class);

    private void stubProjectLookup(String collection, String projectId) {
        when(projectRegistry.findProjectIdByRequirementCollection(collection))
                .thenReturn(java.util.Optional.ofNullable(projectId));
    }

    @Test
    void defaultCollectionIngestionInvalidatesRetrievalCache() {
        when(preprocessor.clean(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(observability.observe(any(), any(), any(),
                org.mockito.ArgumentMatchers.<java.util.function.Supplier<?>>any()))
                .thenAnswer(invocation -> invocation.getArgument(3, java.util.function.Supplier.class).get());
        when(chunker.split(any())).thenReturn(java.util.List.of(
                new ParentChildChunker.ParentChunk(1, "规则文本", java.util.List.of("规则文本"))));
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.getArgument(3, Runnable.class).run();
            return null;
        }).when(observability).observe(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Runnable.class));
        stubProjectLookup(null, "project-a");
        RequirementIngestionService service = new RequirementIngestionService(
                store, preprocessor, resultCache, projectRegistry, chunker, observability);

        service.ingestEntries("doc-a", "5.1", List.of(new KnowledgeEntry("spec.html", "规则文本")));

        verify(store).replaceVersion(eq("doc-a"), eq("5.1"), anyList());
        verify(resultCache).invalidate(org.mockito.ArgumentMatchers.anyString(), eq("doc-a"), eq("5.1"));
    }

    @Test
    void ingestsPdfViaTikaTextExtraction() throws Exception {
        when(preprocessor.clean(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(observability.observe(any(), any(), any(),
                org.mockito.ArgumentMatchers.<java.util.function.Supplier<?>>any()))
                .thenAnswer(invocation -> invocation.getArgument(3, java.util.function.Supplier.class).get());
        when(chunker.split(any())).thenAnswer(invocation -> {
            String text = invocation.getArgument(0);
            return java.util.List.of(new ParentChildChunker.ParentChunk(1, text, java.util.List.of(text)));
        });
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.getArgument(3, Runnable.class).run();
            return null;
        }).when(observability).observe(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Runnable.class));
        stubProjectLookup(null, "project-a");
        RequirementIngestionService service = new RequirementIngestionService(
                store, preprocessor, resultCache, projectRegistry, chunker, observability);

        byte[] pdf = createMinimalPdf();
        IngestResponse response = service.ingest(
                new org.springframework.mock.web.MockMultipartFile("file", "requirements.pdf",
                        "application/pdf", pdf),
                "5.1", "doc-pdf");

        assertThat(response.chunks()).isGreaterThan(0);
        org.mockito.ArgumentCaptor<java.util.List> captor =
                org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(store).replaceVersion(eq("doc-pdf"), eq("5.1"), captor.capture());
        assertThat(captor.getValue()).isNotEmpty();
    }

    private byte[] createMinimalPdf() throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument document = new org.apache.pdfbox.pdmodel.PDDocument()) {
            org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage();
            document.addPage(page);
            try (org.apache.pdfbox.pdmodel.PDPageContentStream content =
                         new org.apache.pdfbox.pdmodel.PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText("Hello PDF requirement text");
                content.endText();
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    @Test
    void sharedFallbackCollectionInvalidatesAllProjects() {
        when(preprocessor.clean(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(observability.observe(any(), any(), any(),
                org.mockito.ArgumentMatchers.<java.util.function.Supplier<?>>any()))
                .thenAnswer(invocation -> invocation.getArgument(3, java.util.function.Supplier.class).get());
        when(chunker.split(any())).thenReturn(java.util.List.of(
                new ParentChildChunker.ParentChunk(1, "规则文本", java.util.List.of("规则文本"))));
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.getArgument(3, Runnable.class).run();
            return null;
        }).when(observability).observe(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Runnable.class));
        when(projectRegistry.findProjectIdByRequirementCollection("shared_collection"))
                .thenReturn(java.util.Optional.empty());
        RequirementIngestionService service = new RequirementIngestionService(
                store, preprocessor, resultCache, projectRegistry, chunker, observability);

        service.ingestEntries("shared_collection", "doc-a", "5.1",
                List.of(new KnowledgeEntry("spec.html", "规则文本")));

        verify(resultCache).invalidateAll("doc-a", "5.1");
        verify(resultCache, never()).invalidate(anyString(), eq("doc-a"), eq("5.1"));
    }

    @Test
    void explicitCollectionIngestionAlsoInvalidatesRetrievalCache() {
        when(preprocessor.clean(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(observability.observe(any(), any(), any(),
                org.mockito.ArgumentMatchers.<java.util.function.Supplier<?>>any()))
                .thenAnswer(invocation -> invocation.getArgument(3, java.util.function.Supplier.class).get());
        when(chunker.split(any())).thenReturn(java.util.List.of(
                new ParentChildChunker.ParentChunk(1, "规则文本", java.util.List.of("规则文本"))));
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.getArgument(3, Runnable.class).run();
            return null;
        }).when(observability).observe(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(Runnable.class));
        stubProjectLookup("requirements_custom", "project-a");
        RequirementIngestionService service = new RequirementIngestionService(
                store, preprocessor, resultCache, projectRegistry, chunker, observability);

        service.ingestEntries("requirements_custom", "doc-a", "5.1",
                List.of(new KnowledgeEntry("spec.html", "规则文本")));

        verify(store).replaceVersion(eq("requirements_custom"), eq("doc-a"), eq("5.1"), anyList());
        verify(resultCache).invalidate(org.mockito.ArgumentMatchers.anyString(), eq("doc-a"), eq("5.1"));
    }

    @Test
    void recordsIntermediateQdrantBatchesAsRunningWithoutExcludedChunks() {
        when(preprocessor.clean(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(observability.observe(any(), any(), any(),
                org.mockito.ArgumentMatchers.<java.util.function.Supplier<?>>any()))
                .thenAnswer(invocation -> invocation.getArgument(3, java.util.function.Supplier.class).get());
        when(chunker.split(any())).thenReturn(List.of(
                new ParentChildChunker.ParentChunk(1, "规则一", List.of("规则一")),
                new ParentChildChunker.ParentChunk(2, "规则二", List.of("规则二"))));
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.getArgument(3, Runnable.class).run();
            return null;
        }).when(observability).observe(anyString(), anyString(), anyString(), any(Runnable.class));
        org.mockito.Mockito.doAnswer(invocation -> {
            QdrantHybridStore.ProgressListener listener = invocation.getArgument(3);
            listener.onProgress(QdrantHybridStore.ReplaceStage.EMBED, 1, 2);
            listener.onProgress(QdrantHybridStore.ReplaceStage.EMBED, 2, 2);
            return null;
        }).when(store).replaceVersion(eq("doc-a"), eq("5.1"), anyList(), any());
        KnowledgeIngestionTracker tracker = mock(KnowledgeIngestionTracker.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<KnowledgeIngestionTracker> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(tracker);
        RequirementIngestionService service = new RequirementIngestionService(
                store, preprocessor, resultCache, projectRegistry, chunker, observability, provider);
        KnowledgeIngestionTracker.Context context =
                new KnowledgeIngestionTracker.Context("base-a", "run-a", "5.1", true);

        service.ingestEntries(null, "doc-a", "5.1",
                List.of(new KnowledgeEntry("spec.html", "规则文本")), context);

        verify(tracker).event(eq(context), eq("RUN"), eq("run-a"), eq(Stage.EMBED),
                eq(EventStatus.RUNNING), eq(2), eq(1), eq(0), isNull());
        verify(tracker).event(eq(context), eq("RUN"), eq("run-a"), eq(Stage.EMBED),
                eq(EventStatus.SUCCEEDED), eq(2), eq(2), eq(0), isNull());
        verify(tracker, times(2)).event(eq(context), eq("RUN"), eq("run-a"), eq(Stage.EMBED),
                any(), any(Integer.class), any(Integer.class), eq(0), isNull());
    }
}
