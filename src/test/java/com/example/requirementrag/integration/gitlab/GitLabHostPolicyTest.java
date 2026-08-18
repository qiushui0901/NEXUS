package com.example.requirementrag.integration.gitlab;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitLabHostPolicyTest {

    @Test
    void appliesTheSameHostPolicyToBaseAndCloneUrls() throws Exception {
        GitLabIntegrationProperties properties = new GitLabIntegrationProperties(
                true, null, null, "", 10, 1,
                List.of("gitlab.example.com"), false);
        GitLabHostPolicy policy = new GitLabHostPolicy(properties,
                ignored -> new InetAddress[]{InetAddress.getByAddress(new byte[]{8, 8, 8, 8})});

        assertThat(policy.validateBaseUrl("https://gitlab.example.com/").toString())
                .isEqualTo("https://gitlab.example.com");
        assertThat(policy.validateCloneUrl(
                "https://gitlab.example.com/group/project.git").getHost())
                .isEqualTo("gitlab.example.com");
        assertThat(policy.validateCloneUrlForBaseUrl(
                "https://gitlab.example.com:443/group/project.git",
                "https://gitlab.example.com").getHost())
                .isEqualTo("gitlab.example.com");
        assertThatThrownBy(() -> policy.validateBaseUrl("https://other.example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowedHosts");
        assertThatThrownBy(() -> policy.validateBaseUrl(
                "https://oauth2:secret@gitlab.example.com"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bindsCloneCredentialsToTheConnectionHostAndPort() throws Exception {
        GitLabIntegrationProperties properties = new GitLabIntegrationProperties(
                true, null, null, "", 10, 1,
                List.of("gitlab-a.example.com", "gitlab-b.example.com"), false);
        GitLabHostPolicy policy = new GitLabHostPolicy(properties,
                ignored -> new InetAddress[]{InetAddress.getByAddress(new byte[]{8, 8, 8, 8})});

        assertThatThrownBy(() -> policy.validateCloneUrlForBaseUrl(
                "https://gitlab-b.example.com/group/project.git",
                "https://gitlab-a.example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("主机或端口不一致");
        assertThatThrownBy(() -> policy.validateCloneUrlForBaseUrl(
                "https://gitlab-a.example.com:8443/group/project.git",
                "https://gitlab-a.example.com"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("主机或端口不一致");
    }

    @Test
    void rejectsPrivateGitLabApiResolutionByDefault() throws Exception {
        GitLabIntegrationProperties properties = new GitLabIntegrationProperties(
                true, null, null, "", 10, 1,
                List.of("gitlab.internal.example"), false);
        GitLabHostPolicy policy = new GitLabHostPolicy(properties,
                ignored -> new InetAddress[]{InetAddress.getByAddress(new byte[]{10, 0, 0, 8})});

        assertThatThrownBy(() -> policy.validateBaseUrl("https://gitlab.internal.example"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内网");
    }
}
