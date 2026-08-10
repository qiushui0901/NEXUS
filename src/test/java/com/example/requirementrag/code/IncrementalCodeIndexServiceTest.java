package com.example.requirementrag.code;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.CodeChunk;
import com.example.requirementrag.model.IncrementalCodeIndexResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IncrementalCodeIndexServiceTest {

    @TempDir
    Path repository;

    @Test
    void renameDeletesOldPathAndRefreshesMultiLanguageGraphSnapshot() throws Exception {
        git("init");
        git("config", "user.email", "test@example.com");
        git("config", "user.name", "Test User");

        Path oldFile = repository.resolve("src/service_old.py");
        Files.createDirectories(oldFile.getParent());
        Files.writeString(oldFile, "def run():\n    return 1\n", StandardCharsets.UTF_8);
        git("add", ".");
        git("commit", "-m", "initial");
        String baseSha = git("rev-parse", "HEAD");

        Path newFile = repository.resolve("src/service_new.py");
        git("mv", oldFile.toString(), newFile.toString());
        git("commit", "-m", "rename service");
        String newSha = git("rev-parse", "HEAD");

        ProjectRegistry registry = mock(ProjectRegistry.class);
        CodeQdrantStore store = mock(CodeQdrantStore.class);
        RagProperties properties = mock(RagProperties.class);
        RagProperties.ProjectConfig project = new RagProperties.ProjectConfig(
                "project", "Project", "group", "server", "requirements-v51", "code-v51",
                repository.toString(), null, null, List.of(), List.of(), 1_000_000);
        whenProject(registry, project);

        SQLiteSymbolGraphStore graphStore = new SQLiteSymbolGraphStore(
                Files.createTempDirectory("nexus-incremental-graph-").toString());
        IncrementalCodeIndexResponse response = new IncrementalCodeIndexService(
                registry, new MultiLanguageCodeScanner(new CodeLanguageRegistry()), store,
                new GitDiffService(properties, registry), graphStore, new CodeIndexLockService())
                .indexWithResult("project", baseSha, newSha);

        assertEquals(2, response.javaFiles());
        assertTrue(response.indexedChunks() > 0);
        verify(store).upsertChunks(eq("code-v51-live"), anyList());
        verify(store, org.mockito.Mockito.times(2)).deleteChunks(eq("code-v51-live"), anyList());
        verify(store, org.mockito.Mockito.never()).deleteFileChunks(anyString(), anyString(), anyString());
        org.mockito.ArgumentCaptor<java.util.List> captor =
                org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(store, org.mockito.Mockito.atLeastOnce()).deleteChunks(eq("code-v51-live"), captor.capture());
        assertTrue(captor.getAllValues().stream().flatMap(java.util.Collection::stream)
                        .allMatch(id -> id.equals("old-id-1")),
                "删除必须只按旧 chunk ID，不得按 filePath 删除新写入的 chunk");
        assertEquals(newSha, graphStore.latestCommit("project"));
        assertTrue(graphStore.symbolsByFiles("project", newSha, List.of("src/service_old.py"), 10).isEmpty());
        assertTrue(graphStore.symbolsByFiles("project", newSha, List.of("src/service_new.py"), 10)
                .stream().anyMatch(symbol -> symbol.language().equals("python")));
    }

    @Test
    void zeroShaSkipsRepositoryAndStore() throws Exception {
        ProjectRegistry registry = mock(ProjectRegistry.class);
        CodeQdrantStore store = mock(CodeQdrantStore.class);
        GitDiffService gitDiffService = mock(GitDiffService.class);

        IncrementalCodeIndexResponse response = new IncrementalCodeIndexService(
                registry, new JavaCodeScanner(), store, gitDiffService)
                .indexWithResult("project", "0000000000000000000000000000000000000000", "new");

        assertEquals(0, response.changedFiles());
        assertEquals(0, response.javaFiles());
        assertEquals(0, response.indexedChunks());
    }

    private void whenProject(ProjectRegistry registry, RagProperties.ProjectConfig project) {
        org.mockito.Mockito.when(registry.require("project")).thenReturn(project);
        org.mockito.Mockito.when(registry.resolveCodeCollection("project")).thenReturn("code-v51");
    }

    private String git(String... arguments) throws IOException, InterruptedException {
        String[] command = new String[arguments.length + 1];
        command[0] = "git";
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        Process process = new ProcessBuilder(command)
                .directory(repository.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        int exitCode = process.waitFor();
        assertEquals(0, exitCode, output);
        return output;
    }
}
