package com.example.requirementrag.project;

import com.example.requirementrag.code.CodeKnowledgeService;
import com.example.requirementrag.model.CodeChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BusinessProjectCodeSearchServiceTest {

    @Test
    void partialRepositoryFailureReturnsDegradedOutcomeWithFailedRepositoryIds() {
        BusinessProjectCatalogService catalog = mock(BusinessProjectCatalogService.class);
        CodeKnowledgeService code = mock(CodeKnowledgeService.class);
        CodeRepository first = new CodeRepository("repo-a", "API", CodeRepository.Kind.PROJECT,
                "business", "server", "code-repo-a", "/tmp/repo-a", "", "", "", true, true, "", "");
        CodeRepository second = new CodeRepository("repo-b", "Worker", CodeRepository.Kind.PROJECT,
                "business", "server", "code-repo-b", "/tmp/repo-b", "", "", "", true, true, "", "");
        CodeChunk hit = new CodeChunk("id", "repo-a", "sha", "src/A.java", "method", "run", 1, 2,
                "return;", "hash");
        when(catalog.resolveProjectId("business")).thenReturn("business");
        when(catalog.repositoryScope("business", List.of())).thenReturn(List.of(first, second));
        when(code.searchInCollection("query", "repo-a", "code-repo-a-live", 8)).thenReturn(List.of(hit));
        when(code.searchInCollection("query", "repo-b", "code-repo-b-live", 8))
                .thenThrow(new IllegalStateException("unavailable"));

        var outcome = new BusinessProjectCodeSearchService(catalog, code)
                .searchWithOutcome("query", "business", List.of(), 8);

        assertThat(outcome.status()).isEqualTo(com.example.requirementrag.model.RagOutcomeStatus.DEGRADED);
        assertThat(outcome.data()).extracting(CodeChunk::repositoryId).containsExactly("repo-a");
        assertThat(outcome.warnings()).singleElement().satisfies(warning -> {
            assertThat(warning.code()).isEqualTo("CODE_REPOSITORY_PARTIAL_FAILURE");
            assertThat(warning.message()).contains("repo-b");
        });
        assertThat(outcome.stageDiagnostics()).singleElement()
                .satisfies(diagnostic -> assertThat(diagnostic.status())
                        .isEqualTo(com.example.requirementrag.model.RagOutcomeStatus.DEGRADED));
    }

    @Test
    void countUsesRepositoryCollectionAndLiveAliasInsteadOfLegacyProjectResolution() {
        BusinessProjectCatalogService catalog = mock(BusinessProjectCatalogService.class);
        CodeKnowledgeService code = mock(CodeKnowledgeService.class);
        CodeRepository repository = new CodeRepository("repo-a", "API", CodeRepository.Kind.PROJECT,
                "business", "server", "code-repo-a", "/tmp/repo-a", "", "", "", true, true, "", "");
        when(catalog.repositoryScope("business", List.of())).thenReturn(List.of(repository));
        when(code.countInCollection("code-repo-a-live", "repo-a")).thenReturn(7L);

        long count = new BusinessProjectCodeSearchService(catalog, code).count("business");

        assertThat(count).isEqualTo(7L);
        verify(code).countInCollection("code-repo-a-live", "repo-a");
        verify(code, never()).count("repo-a");
    }

    @Test
    void searchAddsRepositoryIdentityToEachHit() {
        BusinessProjectCatalogService catalog = mock(BusinessProjectCatalogService.class);
        CodeKnowledgeService code = mock(CodeKnowledgeService.class);
        CodeRepository repository = new CodeRepository("repo-a", "API", CodeRepository.Kind.SHARED,
                "business", "server", "code-repo-a", "/tmp/repo-a", "", "", "", false, true, "", "");
        CodeChunk hit = new CodeChunk("id", "repo-a", "sha", "src/A.java", "method", "run", 1, 2,
                "return;", "hash");
        when(catalog.resolveProjectId("business")).thenReturn("business");
        when(catalog.repositoryScope("business", List.of())).thenReturn(List.of(repository));
        when(code.searchInCollection("query", "repo-a", "code-repo-a", 8)).thenReturn(List.of(hit));

        CodeChunk result = new BusinessProjectCodeSearchService(catalog, code)
                .search("query", "business", List.of(), 8).getFirst();

        assertThat(result.repositoryId()).isEqualTo("repo-a");
        assertThat(result.repositoryName()).isEqualTo("API");
        assertThat(result.repositoryKind()).isEqualTo("SHARED");
    }
}
