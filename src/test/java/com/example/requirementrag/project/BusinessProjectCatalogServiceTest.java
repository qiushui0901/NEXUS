package com.example.requirementrag.project;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.retrieval.pipeline.RetrievalResultCache;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BusinessProjectCatalogServiceTest {

    @Test
    void sharedReferenceChangesInvalidateAllProjectRetrievalCaches() {
        BusinessProjectCatalogStore store = mock(BusinessProjectCatalogStore.class);
        RetrievalResultCache cache = mock(RetrievalResultCache.class);
        BusinessProjectCatalogService service = new BusinessProjectCatalogService(
                store, mock(ProjectRegistry.class), mock(RepositoryVersionResolver.class), cache);
        String now = Instant.now().toString();
        BusinessProject project = new BusinessProject(
                "immortal", "Immortal", "main", "requirements", "fengshen",
                "legacy", "legacy", "5.1", BusinessProject.Status.ACTIVE, now, now);
        CodeRepository shared = new CodeRepository(
                "common", "Common", CodeRepository.Kind.SHARED, null, "server",
                "common_code", "/repo/common", "group/common", "MAVEN_POM", "pom.xml",
                true, true, now, now);
        when(store.resolveAlias("immortal")).thenReturn(Optional.empty());
        when(store.project("immortal")).thenReturn(Optional.of(project));
        when(store.repository("common")).thenReturn(Optional.of(shared));

        service.addSharedReference("immortal", "common");
        service.removeSharedReference("immortal", "common");

        verify(cache, org.mockito.Mockito.times(2)).invalidateProject("immortal");
    }
}
