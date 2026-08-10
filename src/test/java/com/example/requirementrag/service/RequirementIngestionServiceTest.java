package com.example.requirementrag.service;

import com.example.requirementrag.model.KnowledgeEntry;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.observability.RagObservability;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import com.example.requirementrag.retrieval.pipeline.RetrievalResultCache;
import com.example.requirementrag.service.RequirementIngestionService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
}
