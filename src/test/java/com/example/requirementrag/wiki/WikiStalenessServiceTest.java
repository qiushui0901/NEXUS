package com.example.requirementrag.wiki;

import com.example.requirementrag.code.GitDiffService;
import com.example.requirementrag.code.GitDiffService.GitDiffResult;
import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.WikiProperties;
import com.example.requirementrag.model.ChunkRecord;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WikiStalenessServiceTest {
    @TempDir
    Path temp;

    private final ObjectMapper mapper = new ObjectMapper();
    private final GitDiffService gitDiffService = mock(GitDiffService.class);
    private final QdrantHybridStore documentStore = mock(QdrantHybridStore.class);
    private final ProjectRegistry projectRegistry = mock(ProjectRegistry.class);

    private WikiStalenessService service(Path root) {
        WikiProperties properties = new WikiProperties(root.toString(), temp.resolve("sources").toString());
        return new WikiStalenessService(new WikiRepository(mapper, properties), gitDiffService,
                documentStore, projectRegistry);
    }

    private void publish(Path root, String versionJson, String pageJson) throws Exception {
        Path pages = root.resolve("game/5.1/pages");
        Files.createDirectories(pages);
        Files.writeString(root.resolve("game/5.1/index.json"), versionJson);
        Files.writeString(pages.resolve("supply-records.json"), pageJson);
    }

    @Test
    void marksPagesStaleWhenCodeCommitMovedAndDiffHitsTheirFiles() throws Exception {
        Path root = temp.resolve("wiki");
        String index = """
                {"schemaVersion":2,"projectId":"game","projectName":"Game","version":"5.1",
                "requirementVersion":"5.1","baseCodeCommit":"old","codeCommit":"old",
                "generatedAt":"2026-08-01T00:00:00+08:00","pages":[{"featureId":"supply-records",
                "title":"物资记录","category":"记录","introducedVersion":"5.1","status":"CODE_VERIFIED",
                "summary":"s","aliases":[],"evidenceCount":1,"pageType":"FEATURE"}]}
                """;
        String page = """
                {"projectId":"game","projectName":"Game","version":"5.1","requirementVersion":"5.1",
                "baseCodeCommit":"old","codeCommit":"old","generatedAt":"2026-08-01T00:00:00+08:00",
                "featureId":"supply-records","title":"物资记录","category":"记录","introducedVersion":"5.1",
                "status":"CODE_VERIFIED","aliases":[],"summary":"s","requirementSources":[],
                "productRules":[],"processSteps":[],"codeEntries":[{"role":"业务服务",
                "filePath":"src/main/java/com/game/SupplyRecordService.java","symbol":"query",
                "commit":"old","changeType":"MODIFIED","verificationStatus":"VERIFIED"}],
                "codeSymbols":[],"dataImpacts":[],"boundaryConditions":[],"acceptanceCriteria":[],
                "testPoints":[],"testKnowledge":{"executionStatus":"NOT_AVAILABLE","executionReference":"",
                "summary":"没有真实执行快照","cases":[]},"versionChange":{"changeType":"MODIFIED",
                "baseVersion":"5.0","version":"5.1","summary":""},"quality":{"reviewStatus":"PENDING_REVIEW",
                "requirementEvidenceCount":0,"codeEvidenceCount":1,"realTestExecution":false,"missing":[]},
                "risks":[],"relations":[],"evidence":[],"pageType":"FEATURE","claims":[],
                "markdownPath":"pages/supply-records.md"}
                """;
        publish(root, index, page);
        when(gitDiffService.latestCommit("game")).thenReturn("new");
        when(gitDiffService.diff("game", "old", "new")).thenReturn(
                new GitDiffResult(GitDiffService.Availability.AVAILABLE, 1, 0, 1, 0, 0,
                        1, 0, 0, List.of(new GitDiffService.GitFileChange(
                                GitDiffService.ChangeType.MODIFIED,
                                "src/main/java/com/game/SupplyRecordService.java",
                                "src/main/java/com/game/SupplyRecordService.java"))));

        var report = service(root).staleness("game", "5.1");

        assertThat(report.stale()).isTrue();
        assertThat(report.currentCodeCommit()).isEqualTo("new");
        assertThat(report.pages()).singleElement().satisfies(stale -> {
            assertThat(stale.featureId()).isEqualTo("supply-records");
            assertThat(stale.reasons()).anyMatch(reason -> reason.contains("命中页面代码入口"));
        });
    }

    @Test
    void marksPagesStaleWhenRequirementContentHashChanges() throws Exception {
        Path root = temp.resolve("wiki");
        String index = """
                {"schemaVersion":2,"projectId":"game","projectName":"Game","version":"5.1",
                "requirementVersion":"5.1","baseCodeCommit":"head","codeCommit":"head",
                "generatedAt":"2026-08-01T00:00:00+08:00","pages":[{"featureId":"supply-records",
                "title":"物资记录","category":"记录","introducedVersion":"5.1","status":"CODE_VERIFIED",
                "summary":"s","aliases":[],"evidenceCount":1,"pageType":"FEATURE"}]}
                """;
        String page = """
                {"projectId":"game","projectName":"Game","version":"5.1","requirementVersion":"5.1",
                "baseCodeCommit":"head","codeCommit":"head","generatedAt":"2026-08-01T00:00:00+08:00",
                "featureId":"supply-records","title":"物资记录","category":"记录","introducedVersion":"5.1",
                "status":"CODE_VERIFIED","aliases":[],"summary":"s",
                "requirementSources":[{"documentId":"requirements","entryId":"e1",
                "filename":"物资记录.html","version":"5.1","location":"parentOrder=1",
                "contentHash":"published-hash","verificationStatus":"PENDING_REVIEW"}],
                "productRules":[],"processSteps":[],"codeEntries":[],
                "codeSymbols":[],"dataImpacts":[],"boundaryConditions":[],"acceptanceCriteria":[],
                "testPoints":[],"testKnowledge":{"executionStatus":"NOT_AVAILABLE","executionReference":"",
                "summary":"没有真实执行快照","cases":[]},"versionChange":{"changeType":"MODIFIED",
                "baseVersion":"5.0","version":"5.1","summary":""},"quality":{"reviewStatus":"PENDING_REVIEW",
                "requirementEvidenceCount":1,"codeEvidenceCount":0,"realTestExecution":false,"missing":[]},
                "risks":[],"relations":[],"evidence":[],"pageType":"FEATURE","claims":[],
                "markdownPath":"pages/supply-records.md"}
                """;
        publish(root, index, page);
        when(gitDiffService.latestCommit("game")).thenReturn("head");
        when(projectRegistry.resolveRequirementCollection("game")).thenReturn("requirements_game");
        when(documentStore.scrollVersion("requirements_game", "requirements", "5.1"))
                .thenReturn(List.of(new ChunkRecord("c1", "requirements", "5.1", "物资记录.html",
                        "p1", "新规则文本", "新规则文本", "current-hash", 1, 1)));

        var report = service(root).staleness("game", "5.1");

        assertThat(report.stale()).isTrue();
        assertThat(report.pages()).singleElement().satisfies(stale ->
                assertThat(stale.reasons()).anyMatch(reason -> reason.contains("内容哈希已变化")));
    }

    @Test
    void reportsFreshWhenNothingChanged() throws Exception {
        Path root = temp.resolve("wiki");
        String index = """
                {"schemaVersion":2,"projectId":"game","projectName":"Game","version":"5.1",
                "requirementVersion":"5.1","baseCodeCommit":"head","codeCommit":"head",
                "generatedAt":"2026-08-01T00:00:00+08:00","pages":[{"featureId":"supply-records",
                "title":"物资记录","category":"记录","introducedVersion":"5.1","status":"CODE_VERIFIED",
                "summary":"s","aliases":[],"evidenceCount":1,"pageType":"FEATURE"}]}
                """;
        String page = """
                {"projectId":"game","projectName":"Game","version":"5.1","requirementVersion":"5.1",
                "baseCodeCommit":"head","codeCommit":"head","generatedAt":"2026-08-01T00:00:00+08:00",
                "featureId":"supply-records","title":"物资记录","category":"记录","introducedVersion":"5.1",
                "status":"CODE_VERIFIED","aliases":[],"summary":"s","requirementSources":[],
                "productRules":[],"processSteps":[],"codeEntries":[],
                "codeSymbols":[],"dataImpacts":[],"boundaryConditions":[],"acceptanceCriteria":[],
                "testPoints":[],"testKnowledge":{"executionStatus":"NOT_AVAILABLE","executionReference":"",
                "summary":"没有真实执行快照","cases":[]},"versionChange":{"changeType":"MODIFIED",
                "baseVersion":"5.0","version":"5.1","summary":""},"quality":{"reviewStatus":"PENDING_REVIEW",
                "requirementEvidenceCount":0,"codeEvidenceCount":0,"realTestExecution":false,"missing":[]},
                "risks":[],"relations":[],"evidence":[],"pageType":"FEATURE","claims":[],
                "markdownPath":"pages/supply-records.md"}
                """;
        publish(root, index, page);
        when(gitDiffService.latestCommit("game")).thenReturn("head");

        var report = service(root).staleness("game", "5.1");

        assertThat(report.stale()).isFalse();
        assertThat(report.pages()).isEmpty();
    }
}
