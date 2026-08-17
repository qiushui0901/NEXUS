package com.example.requirementrag.integration.gitlab;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitLabGitClientTest {

    @Test
    void validatesHttpsCloneUrlWithoutEmbeddedSecrets() throws Exception {
        GitLabGitClient client = client("gitlab.example.com", false, publicAddress());
        client.validateCloneUrl("https://gitlab.example.com/group/project.git");

        assertThatThrownBy(() -> client.validateCloneUrl(
                "https://oauth2:secret@gitlab.example.com/group/project.git"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.validateCloneUrl(
                "http://gitlab.example.com/group/project.git"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.validateCloneUrl(
                "https://gitlab.example.com/group/project.git?token=secret"))
                .isInstanceOf(IllegalArgumentException.class);
        client.shutdown();
    }

    @Test
    void requiresExactAllowedHostMatch() throws Exception {
        GitLabGitClient client = client("gitlab.example.com", false, publicAddress());

        assertThatThrownBy(() -> client.validateCloneUrl(
                "https://gitlab.example.com.evil.test/group/project.git"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowedHosts");
        assertThatThrownBy(() -> client.validateCloneUrl(
                "https://other.example.com/group/project.git"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowedHosts");
        client.shutdown();
    }

    @Test
    void rejectsPrivateResolutionByDefaultAndAllowsExplicitPrivateGitLab() throws Exception {
        InetAddress privateAddress = InetAddress.getByAddress(new byte[]{10, 0, 0, 8});
        GitLabGitClient restricted = client("gitlab.internal.example", false, privateAddress);
        GitLabGitClient allowed = client("gitlab.internal.example", true, privateAddress);

        assertThatThrownBy(() -> restricted.validateCloneUrl(
                "https://gitlab.internal.example/group/project.git"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内网");
        allowed.validateCloneUrl("https://gitlab.internal.example/group/project.git");
        restricted.shutdown();
        allowed.shutdown();
    }

    @Test
    void rejectsHostWhenAnyResolvedAddressIsPrivate() throws Exception {
        InetAddress privateAddress = InetAddress.getByAddress(new byte[]{10, 0, 0, 8});
        GitLabGitClient client = client("gitlab.example.com", false, publicAddress(), privateAddress);

        assertThatThrownBy(() -> client.validateCloneUrl(
                "https://gitlab.example.com/group/project.git"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内网");
        client.shutdown();
    }

    @Test
    void rejectsIpLiteralByDefault() throws Exception {
        GitLabGitClient client = client("127.0.0.1", false,
                InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));

        assertThatThrownBy(() -> client.validateCloneUrl(
                "https://127.0.0.1/group/project.git"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IP");
        client.shutdown();
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
                new GitLabIntegrationProperties(true, root.toString(), null, "", 10, 1,
                        List.of("gitlab.example.com"), true),
                ignored -> new InetAddress[]{InetAddress.getLoopbackAddress()});
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

    private GitLabGitClient client(String allowedHost, boolean allowPrivate, InetAddress... addresses)
            throws Exception {
        String root = Files.createTempDirectory("nexus-git-host-policy-").toString();
        GitLabIntegrationProperties properties = new GitLabIntegrationProperties(
                true, root, null, "", 10, 1, List.of(allowedHost), allowPrivate);
        return new GitLabGitClient(properties, ignored -> addresses);
    }

    private InetAddress publicAddress() throws Exception {
        return InetAddress.getByAddress(new byte[]{8, 8, 8, 8});
    }
}
