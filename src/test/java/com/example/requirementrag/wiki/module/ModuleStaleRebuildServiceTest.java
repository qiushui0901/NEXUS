package com.example.requirementrag.wiki.module;

import com.example.requirementrag.code.CodeSymbol;
import com.example.requirementrag.config.WikiProperties;
import com.example.requirementrag.knowledge.build.KnowledgeDraftLifecycleService;
import com.example.requirementrag.knowledge.build.KnowledgeDraftModels;
import com.example.requirementrag.wiki.WikiModels;
import com.example.requirementrag.wiki.WikiRepository;
import com.example.requirementrag.wiki.module.ModuleFactModels.ModuleEvidence;
import com.example.requirementrag.wiki.module.ModuleFactModels.ModuleFactBundle;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModuleStaleRebuildServiceTest {
    @TempDir
    Path temp;

    private final ObjectMapper mapper = new ObjectMapper();
    private final ModuleFactExtractor extractor = mock(ModuleFactExtractor.class);
    private final KnowledgeDraftLifecycleService draftLifecycleService = mock(KnowledgeDraftLifecycleService.class);

    private ModuleStaleRebuildService service() {
        WikiProperties properties = new WikiProperties(temp.resolve("wiki").toString(),
                temp.resolve("sources").toString(), temp.resolve("drafts").toString());
        return new ModuleStaleRebuildService(mapper, properties, new WikiRepository(mapper, properties),
                extractor, new ModuleWikiPlanner(), ModuleClaimQualityGate.lenient(), draftLifecycleService);
    }

    private void publishModulePage(String oldClaimText) throws Exception {
        Path pages = temp.resolve("wiki").resolve("game").resolve("5.1").resolve("pages");
        Files.createDirectories(pages);
        Files.writeString(temp.resolve("wiki").resolve("game").resolve("5.1").resolve("index.json"), """
                {"schemaVersion":2,"projectId":"game","projectName":"Game","version":"5.1",
                "requirementVersion":"5.1","baseCodeCommit":"old","codeCommit":"old",
                "generatedAt":"2026-08-01T00:00:00+08:00","pages":[{"featureId":"module-auth",
                "title":"auth 模块","category":"模块","introducedVersion":"5.1","status":"DRAFT",
                "summary":"s","aliases":[],"evidenceCount":2,"pageType":"MODULE"}]}
                """);
        Files.writeString(pages.resolve("module-auth.json"), """
                {"projectId":"game","projectName":"Game","version":"5.1","requirementVersion":"5.1",
                "baseCodeCommit":"old","codeCommit":"old","generatedAt":"2026-08-01T00:00:00+08:00",
                "featureId":"module-auth","title":"auth 模块","category":"模块","introducedVersion":"5.1",
                "status":"DRAFT","aliases":[],"summary":"s","requirementSources":[],
                "productRules":[],"processSteps":[],"codeEntries":[],"codeSymbols":[],"dataImpacts":[],
                "boundaryConditions":[],"acceptanceCriteria":[],"testPoints":[],
                "testKnowledge":{"executionStatus":"NOT_AVAILABLE","executionReference":"",
                "summary":"没有真实执行快照","cases":[]},"versionChange":{"changeType":"MODULE_FACT",
                "baseVersion":"","version":"5.1","summary":""},"quality":{"reviewStatus":"PENDING_REVIEW",
                "requirementEvidenceCount":0,"codeEvidenceCount":1,"realTestExecution":false,"missing":[]},
                "risks":[],"relations":[],"evidence":[{"type":"CODE","title":"com.game.auth.AuthService",
                "source":"game","version":"5.1","location":"lines=1-50","excerpt":"",
                "commit":"old","filePath":"src/auth/AuthService.java","symbol":"com.game.auth.AuthService",
                "verificationStatus":"PENDING_REVIEW"}],"pageType":"MODULE","claims":[
                {"claimId":"auth-responsibility","section":"responsibility","text":"%s",
                "support":"PARTIAL","evidenceIds":["code:auth:0"]}],
                "markdownPath":"pages/module-auth.md"}
                """.formatted(oldClaimText));
    }

    private ModuleFactBundle newBundle() {
        return new ModuleFactBundle("game", "new", "auth", "认证模块", "src/auth",
                List.of("src/auth"), List.of("com.game.auth"),
                List.of(new CodeSymbol("s1", "game", "new", "java", "CLASS",
                        "com.game.auth.AuthService", "AuthService", "src/auth/AuthService.java", 1, 50,
                        false, false)),
                List.of(new CodeSymbol("s2", "game", "new", "java", "METHOD",
                        "com.game.auth.AuthService.revoke", "revoke", "src/auth/AuthService.java", 10, 20,
                        true, false)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new ModuleEvidence("code:auth:0", "CODE", "game", "5.1", "new",
                        "src/auth/AuthService.java", "com.game.auth.AuthService", 1, 50, "s1"),
                        new ModuleEvidence("code:auth:1", "CODE", "game", "5.1", "new",
                                "src/auth/AuthService.java", "com.game.auth.AuthService.revoke", 10, 20, "s2")),
                List.of());
    }

    @Test
    void rebuildsModulePageAndReportsClaimLevelDiff() throws Exception {
        publishModulePage("旧的职责描述");
        when(extractor.extract("game", "src/auth", "5.1")).thenReturn(newBundle());
        when(draftLifecycleService.initializeDraft(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> new KnowledgeDraftModels.DraftMetadata(
                        invocation.getArgument(2), invocation.getArgument(0), invocation.getArgument(1),
                        KnowledgeDraftModels.DraftStatus.DRAFT, 0, invocation.getArgument(4),
                        invocation.getArgument(4), invocation.getArgument(3), List.of(), null));

        var result = service().rebuild("game", "5.1", "src/auth", "module-auth", "new", "reviewer");

        assertThat(result.draft().buildId()).isNotBlank();
        assertThat(result.claimChanges()).isNotEmpty();
        assertThat(result.claimChanges()).anyMatch(change -> change.changeType().equals("ADDED"));
        assertThat(result.claimChanges()).anyMatch(change -> change.changeType().equals("MODIFIED"));
        assertThat(result.claimChanges()).anyMatch(change -> change.claimId().equals("auth-responsibility")
                && change.changeType().equals("MODIFIED"));
        Path draftRoot = temp.resolve("drafts").resolve("game").resolve("5.1");
        try (var dirs = Files.list(draftRoot)) {
            Path written = dirs.findFirst().orElseThrow();
            assertThat(written.resolve("claim-diff.json")).isRegularFile();
        }
    }
}
