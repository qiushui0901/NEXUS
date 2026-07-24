package com.example.requirementrag.code;

import com.example.requirementrag.code.GitDiffService.ChangeType;
import com.example.requirementrag.code.GitDiffService.GitDiffResult;
import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GitDiffServiceTest {

    @TempDir
    Path repository;

    @Test
    void reportsAddedModifiedDeletedAndRenamedFilesWithCategoryCounts() throws Exception {
        git("init");
        git("config", "user.email", "test@example.com");
        git("config", "user.name", "Test User");

        write("src/main/java/example/ModifiedService.java", "class ModifiedService {}\n");
        write("src/main/java/example/DeletedService.java", "class DeletedService {}\n");
        write("src/test/java/example/RenamedServiceTest.java", "class RenamedServiceTest {}\n");
        git("add", ".");
        git("commit", "-m", "base");
        String baseSha = git("rev-parse", "HEAD");

        write("src/main/java/example/ModifiedService.java", "class ModifiedService { void run() {} }\n");
        Files.delete(repository.resolve("src/main/java/example/DeletedService.java"));
        Path movedTest = repository.resolve("src/test/java/example/MovedServiceTest.java");
        Files.createDirectories(movedTest.getParent());
        git("mv", "src/test/java/example/RenamedServiceTest.java", "src/test/java/example/MovedServiceTest.java");
        write("config/new.json", "{}\n");
        git("add", ".");
        git("commit", "-m", "target");
        String targetSha = git("rev-parse", "HEAD");

        RagProperties properties = mock(RagProperties.class);
        ProjectRegistry registry = mock(ProjectRegistry.class);
        RagProperties.ProjectConfig project = new RagProperties.ProjectConfig(
                "project", "Project", "group", "server", "requirements", "code",
                repository.toString(), null, null, List.of(), List.of(), 1_000_000);
        when(registry.require("project")).thenReturn(project);

        GitDiffResult result = new GitDiffService(properties, registry).diff("project", baseSha, targetSha);

        assertThat(result.changedFiles()).isEqualTo(4);
        assertThat(result.added()).isEqualTo(1);
        assertThat(result.modified()).isEqualTo(1);
        assertThat(result.deleted()).isEqualTo(1);
        assertThat(result.renamed()).isEqualTo(1);
        assertThat(result.javaFiles()).isEqualTo(3);
        assertThat(result.testFiles()).isEqualTo(1);
        assertThat(result.configFiles()).isEqualTo(1);
        assertThat(result.changes()).extracting(GitDiffService.GitFileChange::type)
                .containsExactlyInAnyOrder(ChangeType.ADDED, ChangeType.MODIFIED, ChangeType.DELETED, ChangeType.RENAMED);
    }

    @Test
    void rejectsNonCommitArgumentsBeforeStartingGit() {
        GitDiffService service = new GitDiffService(mock(RagProperties.class), mock(ProjectRegistry.class));

        assertThatThrownBy(() -> service.diff("project", "HEAD;rm", "abcdef1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Git commit SHA");
    }

    private void write(String relativePath, String content) throws IOException {
        Path file = repository.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
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
