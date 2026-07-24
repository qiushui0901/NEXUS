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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class IncrementalCodeIndexServiceTest {

    @TempDir
    Path repository;

    @Test
    void renameDeletesOldPathAndIndexesNewPath() throws Exception {
        git("init");
        git("config", "user.email", "test@example.com");
        git("config", "user.name", "Test User");

        Path oldFile = repository.resolve("src/main/java/example/OldService.java");
        Files.createDirectories(oldFile.getParent());
        Files.writeString(oldFile, """
                package example;

                public class OldService {
                    public void run() {}
                }
                """, StandardCharsets.UTF_8);
        git("add", ".");
        git("commit", "-m", "initial");
        String baseSha = git("rev-parse", "HEAD");

        Path newFile = repository.resolve("src/main/java/example/NewService.java");
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

        IncrementalCodeIndexResponse response = new IncrementalCodeIndexService(
                properties, registry, new JavaCodeScanner(), store)
                .indexWithResult("project", baseSha, newSha);

        assertEquals(2, response.javaFiles());
        assertTrue(response.indexedChunks() > 0);
        verify(store).deleteFileChunks("code-v51", "project", "src/main/java/example/OldService.java");
        verify(store).deleteFileChunks("code-v51", "project", "src/main/java/example/NewService.java");
        verify(store).upsertChunks(eq("code-v51"), anyList());
    }

    @Test
    void zeroShaSkipsRepositoryAndStore() throws Exception {
        ProjectRegistry registry = mock(ProjectRegistry.class);
        CodeQdrantStore store = mock(CodeQdrantStore.class);
        RagProperties properties = mock(RagProperties.class);

        IncrementalCodeIndexResponse response = new IncrementalCodeIndexService(
                properties, registry, new JavaCodeScanner(), store)
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
