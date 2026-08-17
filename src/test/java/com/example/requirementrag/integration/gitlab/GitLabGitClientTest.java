package com.example.requirementrag.integration.gitlab;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitLabGitClientTest {

    @Test
    void validatesHttpsCloneUrlWithoutEmbeddedSecrets() {
        GitLabGitClient.validateCloneUrl("https://gitlab.example.com/group/project.git");

        assertThatThrownBy(() -> GitLabGitClient.validateCloneUrl(
                "https://oauth2:secret@gitlab.example.com/group/project.git"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GitLabGitClient.validateCloneUrl(
                "http://gitlab.example.com/group/project.git"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GitLabGitClient.validateCloneUrl(
                "https://gitlab.example.com/group/project.git?token=secret"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatesProjectBranchAndFullCommitSha() {
        GitLabGitClient.validateProjectId("payments-api");
        GitLabGitClient.validateBranch("release/2026.08");
        GitLabGitClient.validateSha("a".repeat(40));

        assertThatThrownBy(() -> GitLabGitClient.validateProjectId("../escape"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GitLabGitClient.validateBranch("../main"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GitLabGitClient.validateSha("abc123"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolvesRepositoryOnlyInsideConfiguredRoot() throws Exception {
        String root = Files.createTempDirectory("nexus-git-root-").toString();
        GitLabGitClient client = new GitLabGitClient(
                new GitLabIntegrationProperties(true, root, null, "", 10, 1));

        assertThat(client.repositoryPath("project-a").normalize().toString()).startsWith(root);
        assertThatThrownBy(() -> client.repositoryPath("../../escape"))
                .isInstanceOf(IllegalArgumentException.class);
        client.shutdown();
    }

    @Test
    void drainsLargeGitOutputWhileRetainingOnlyBoundedDiagnostics() throws Exception {
        String root = Files.createTempDirectory("nexus-git-root-").toString();
        GitLabGitClient client = new GitLabGitClient(
                new GitLabIntegrationProperties(true, root, null, "", 10, 1));
        Method method = GitLabGitClient.class.getDeclaredMethod("readOutput", java.io.InputStream.class);
        method.setAccessible(true);

        byte[] retained = (byte[]) method.invoke(client,
                new ByteArrayInputStream(new byte[1_048_576 + 64 * 1024]));

        assertThat(retained).hasSize(1_048_576);
        client.shutdown();
    }

    @Test
    void existingRepositoryKeepsCleanOriginWhenAuthenticated() throws Exception {
        Path root = Files.createTempDirectory("nexus-git-origin-");
        Path repository = Files.createDirectories(root.resolve("project-a"));
        run(repository, "git", "init");
        String cloneUrl = "https://gitlab.example.com/group/project-a.git";
        run(repository, "git", "remote", "add", "origin", cloneUrl);
        GitLabGitClient client = new GitLabGitClient(
                new GitLabIntegrationProperties(true, root.toString(), null, "", 10, 1));
        GitLabManagedProject project = new GitLabManagedProject(
                "project-a", "Project A", "group", "server", cloneUrl, "main",
                "group/project-a", "project_a_requirements", "project_a_code",
                repository.toString(), "encrypted-pat", "encrypted-webhook",
                GitLabProjectStatus.PENDING, null, null, null, "now", "now");

        client.ensureRepository(project, "glpat-must-not-be-persisted");

        String config = Files.readString(repository.resolve(".git/config"));
        assertThat(config).contains(cloneUrl).doesNotContain("glpat-must-not-be-persisted");
        client.shutdown();
    }

    private void run(Path directory, String... command) throws Exception {
        Process process = new ProcessBuilder(command).directory(directory.toFile())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).as(output).isZero();
    }
}
