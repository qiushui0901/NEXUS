package com.example.requirementrag.wiki.module;

import com.example.requirementrag.code.CodeRelation;
import com.example.requirementrag.code.CodeSymbol;
import com.example.requirementrag.code.SQLiteSymbolGraphStore;
import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.wiki.module.ModuleFactModels.ModuleFactBundle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ModuleFactExtractorTest {
    @TempDir
    Path temp;

    private final SQLiteSymbolGraphStore graphStore = mock(SQLiteSymbolGraphStore.class);
    private final ProjectRegistry projectRegistry = mock(ProjectRegistry.class);
    private ModuleFactExtractor extractor;

    @BeforeEach
    void setUp() {
        RagProperties.ProjectConfig project = new RagProperties.ProjectConfig(
                "game", "Game", "game", "server", "requirements_game", "code_game",
                temp.toString(), "group/game", null, List.of(), List.of(), 1_000_000);
        when(projectRegistry.require("game")).thenReturn(project);
        extractor = new ModuleFactExtractor(projectRegistry, graphStore);
    }

    @Test
    void extractsModuleFactsFromFilesAndSymbolGraph() throws Exception {
        Path module = temp.resolve("src/main/java/com/game/auth");
        Files.createDirectories(module);
        Files.writeString(module.resolve("AuthService.java"), """
                package com.game.auth;
                @RestController
                public class AuthService {
                    public String revoke(String token) { return revokeInternal(token); }
                    private String revokeInternal(String token) { return "ok"; }
                }
                """);
        Files.writeString(module.resolve("application.yml"), "server:\n  port: 8080\n");
        when(graphStore.latestCommit("game")).thenReturn("abc123");
        CodeSymbol entry = new CodeSymbol("s1", "game", "abc123", "java", "METHOD",
                "com.game.auth.AuthService.revoke", "revoke", "src/main/java/com/game/auth/AuthService.java",
                4, 4, true, false);
        CodeSymbol internal = new CodeSymbol("s2", "game", "abc123", "java", "METHOD",
                "com.game.auth.AuthService.revokeInternal", "revokeInternal",
                "src/main/java/com/game/auth/AuthService.java", 5, 5, false, false);
        when(graphStore.symbolsByFiles(eq("game"), eq("abc123"), any(), anyInt()))
                .thenReturn(List.of(entry, internal));
        when(graphStore.relations(eq("game"), eq("abc123"), eq("s1"), eq(false), anyInt()))
                .thenReturn(List.of(new CodeRelation("r1", "game", "abc123", "s1", "s2",
                        "com.game.auth.AuthService.revokeInternal", "src/main/java/com/game/auth/AuthService.java",
                        5, CodeRelation.Resolution.EXACT, "")));
        when(graphStore.relations(any(), any(), any(), eq(true), anyInt())).thenReturn(List.of());
        when(graphStore.unresolved(any(), any(), anyInt())).thenReturn(List.of());

        ModuleFactBundle bundle = extractor.extract("game", "src/main/java/com/game/auth", "5.1");

        assertThat(bundle.moduleId()).isEqualTo("auth");
        assertThat(bundle.commitSha()).isEqualTo("abc123");
        assertThat(bundle.entryPoints()).extracting(CodeSymbol::qualifiedName)
                .containsExactly("com.game.auth.AuthService.revoke");
        assertThat(bundle.coreFlows()).hasSize(1);
        assertThat(bundle.routes()).anyMatch(route -> route.contains("@RestController"));
        assertThat(bundle.configuration()).contains("application.yml");
        assertThat(bundle.evidence()).isNotEmpty();
        assertThat(bundle.evidence().get(0).evidenceId()).startsWith("code:auth:");
        assertThat(bundle.sourceRoots()).contains(temp.resolve("src/main/java/com/game/auth/AuthService.java")
                .toAbsolutePath().normalize().toString());
    }

    @Test
    void reportsDiagnosticsWhenGraphSnapshotIsMissing() throws Exception {
        Path module = temp.resolve("src/main/java/com/game/auth");
        Files.createDirectories(module);
        Files.writeString(module.resolve("AuthService.java"), "package com.game.auth;\nclass AuthService {}\n");
        when(graphStore.latestCommit("game")).thenReturn(null);

        ModuleFactBundle bundle = extractor.extract("game", "src/main/java/com/game/auth", "5.1");

        assertThat(bundle.diagnostics()).anyMatch(diagnostic ->
                diagnostic.code().equals("NO_GRAPH_SNAPSHOT"));
        assertThat(bundle.entryPoints()).isEmpty();
    }

    @Test
    void reportsDiagnosticsWhenModulePathIsMissing() {
        when(graphStore.latestCommit("game")).thenReturn("abc123");

        ModuleFactBundle bundle = extractor.extract("game", "src/missing/module", "5.1");

        assertThat(bundle.diagnostics()).anyMatch(diagnostic ->
                diagnostic.code().equals("MODULE_PATH_NOT_FOUND"));
    }
}
