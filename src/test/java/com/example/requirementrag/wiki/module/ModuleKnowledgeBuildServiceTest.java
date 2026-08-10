package com.example.requirementrag.wiki.module;

import com.example.requirementrag.code.CodeSymbol;
import com.example.requirementrag.config.WikiProperties;
import com.example.requirementrag.knowledge.build.KnowledgeDraftLifecycleService;
import com.example.requirementrag.knowledge.build.KnowledgeDraftModels;
import com.example.requirementrag.wiki.module.ModuleFactModels.ModuleBuildRequest;
import com.example.requirementrag.wiki.module.ModuleFactModels.ModuleFactBundle;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModuleKnowledgeBuildServiceTest {
    @TempDir
    Path temp;

    private final ObjectMapper mapper = new ObjectMapper();
    private final ModuleFactExtractor extractor = mock(ModuleFactExtractor.class);
    private final KnowledgeDraftLifecycleService draftLifecycleService = mock(KnowledgeDraftLifecycleService.class);
    private final com.example.requirementrag.config.ProjectRegistry projectRegistry =
            mock(com.example.requirementrag.config.ProjectRegistry.class);

    private ModuleKnowledgeBuildService service() {
        WikiProperties properties = new WikiProperties(temp.resolve("wiki").toString(),
                temp.resolve("sources").toString(), temp.resolve("drafts").toString());
        return new ModuleKnowledgeBuildService(mapper, properties, extractor, new ModuleWikiPlanner(),
                new ModuleClaimQualityGate(projectRegistry), draftLifecycleService);
    }

    private ModuleFactBundle bundle() {
        return new ModuleFactBundle("game", "abc123", "auth", "认证模块", "src/auth",
                List.of("src/auth"), List.of("com.game.auth"),
                List.of(new CodeSymbol("s1", "game", "abc123", "java", "CLASS",
                        "com.game.auth.AuthService", "AuthService", "src/auth/AuthService.java", 1, 50,
                        false, false)),
                List.of(new CodeSymbol("s2", "game", "abc123", "java", "METHOD",
                        "com.game.auth.AuthService.revoke", "revoke", "src/auth/AuthService.java", 10, 20,
                        true, false)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(new ModuleFactModels.ModuleEvidence("code:auth:0", "CODE", "game", "5.1", "abc123",
                        "src/auth/AuthService.java", "com.game.auth.AuthService", 1, 50, "s1"),
                        new ModuleFactModels.ModuleEvidence("code:auth:1", "CODE", "game", "5.1", "abc123",
                                "src/auth/AuthService.java", "com.game.auth.AuthService.revoke", 10, 20, "s2")),
                List.of());
    }

    @org.junit.jupiter.api.BeforeEach
    void stubProject() throws Exception {
        when(projectRegistry.require("game")).thenReturn(new com.example.requirementrag.config.RagProperties.ProjectConfig(
                "game", "Game", "game", "server", "requirements_game", "code_game",
                temp.toString(), "group/game", null, List.of(), List.of(), 1_000_000));
        java.nio.file.Path module = temp.resolve("src/auth");
        java.nio.file.Files.createDirectories(module);
        StringBuilder source = new StringBuilder();
        for (int line = 1; line <= 50; line++) source.append("line ").append(line).append('\n');
        java.nio.file.Files.writeString(module.resolve("AuthService.java"), source.toString());
    }

    @Test
    void buildsModuleDraftWithClaimsAndInitializesReview() throws Exception {
        when(extractor.extract("game", "src/auth", "5.1")).thenReturn(bundle());
        when(draftLifecycleService.initializeDraft(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> new KnowledgeDraftModels.DraftMetadata(
                        invocation.getArgument(2), invocation.getArgument(0), invocation.getArgument(1),
                        KnowledgeDraftModels.DraftStatus.DRAFT, 0, invocation.getArgument(4),
                        invocation.getArgument(4), invocation.getArgument(3),
                        List.of(), null));

        var draft = service().build(new ModuleBuildRequest("game", "5.1", "src/auth", "abc123", "system"));

        assertThat(draft.buildId()).isNotBlank();
        assertThat(draft.projectId()).isEqualTo("game");
        Path draftRoot = temp.resolve("drafts").resolve("game").resolve("5.1");
        try (var dirs = Files.list(draftRoot)) {
            Path written = dirs.findFirst().orElseThrow();
            assertThat(written.resolve("wiki-source.json")).isRegularFile();
            assertThat(written.resolve("module-bundle.json")).isRegularFile();
            String source = Files.readString(written.resolve("wiki-source.json"));
            assertThat(source).contains("pageType").contains("MODULE").contains("claims");
        }
    }

    @Test
    void rejectsBuildWhenQualityGateFails() {
        ModuleFactBundle empty = new ModuleFactBundle("game", "abc123", "auth", "认证模块", "src/auth",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of());
        when(extractor.extract("game", "src/auth", "5.1")).thenReturn(empty);

        assertThatThrownBy(() -> service().build(new ModuleBuildRequest("game", "5.1", "src/auth", "abc123", "system")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有真实 CODE 证据");
    }

    @Test
    void rejectsEmptyModulePath() {
        assertThatThrownBy(() -> service().build(new ModuleBuildRequest("game", "5.1", "", "abc123", "system")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modulePath");
    }
}
