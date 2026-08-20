package com.example.requirementrag.integration.gitlab;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.test.web.client.MockRestServiceServer;

import java.net.InetAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class GitLabApiClientTest {

    @Test
    void verifiesAccountAndReadsEveryMembershipProjectPage() throws Exception {
        Fixture fixture = fixture();
        fixture.server.expect(requestTo("https://gitlab.example.com/api/v4/user"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("PRIVATE-TOKEN", "glpat-secret"))
                .andRespond(withSuccess("""
                        {"id":7,"username":"qiushui","name":"秋水"}
                        """, MediaType.APPLICATION_JSON));
        fixture.server.expect(requestTo(
                        "https://gitlab.example.com/api/v4/projects?membership=true&simple=true&per_page=100&page=1&order_by=last_activity_at&sort=desc"))
                .andExpect(header("PRIVATE-TOKEN", "glpat-secret"))
                .andRespond(withSuccess("""
                        [{"id":11,"name":"A","path_with_namespace":"group/a",
                          "http_url_to_repo":"https://gitlab.example.com/group/a.git",
                          "default_branch":"main","visibility":"private","archived":false,
                          "last_activity_at":"2026-08-18T00:00:00Z"}]
                        """, MediaType.APPLICATION_JSON).header("X-Next-Page", "2"));
        fixture.server.expect(requestTo(
                        "https://gitlab.example.com/api/v4/projects?membership=true&simple=true&per_page=100&page=2&order_by=last_activity_at&sort=desc"))
                .andRespond(withSuccess("""
                        [{"id":12,"name":"B","path_with_namespace":"group/b",
                          "http_url_to_repo":"https://gitlab.example.com/group/b.git",
                          "default_branch":"develop","visibility":"internal","archived":false,
                          "last_activity_at":"2026-08-17T00:00:00Z"}]
                        """, MediaType.APPLICATION_JSON).header("X-Next-Page", ""));

        assertThat(fixture.client.account("https://gitlab.example.com", "glpat-secret"))
                .extracting(GitLabApiClient.Account::username, GitLabApiClient.Account::name)
                .containsExactly("qiushui", "秋水");
        assertThat(fixture.client.membershipProjects(
                "https://gitlab.example.com", "glpat-secret"))
                .extracting(GitLabApiClient.RemoteProject::pathWithNamespace)
                .containsExactly("group/a", "group/b");
        fixture.server.verify();
    }

    @Test
    void mapsAuthenticationAndRateLimitFailuresToStableErrors() throws Exception {
        Fixture auth = fixture();
        auth.server.expect(requestTo("https://gitlab.example.com/api/v4/user"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> auth.client.account(
                "https://gitlab.example.com", "bad-token"))
                .isInstanceOf(GitLabApiException.class)
                .extracting(error -> ((GitLabApiException) error).code())
                .isEqualTo("GITLAB_TOKEN_INVALID");

        Fixture accountForbidden = fixture();
        accountForbidden.server.expect(requestTo("https://gitlab.example.com/api/v4/user"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> accountForbidden.client.account(
                "https://gitlab.example.com", "limited-token"))
                .isInstanceOf(GitLabApiException.class)
                .extracting(error -> ((GitLabApiException) error).code())
                .isEqualTo("GITLAB_TOKEN_INVALID");

        Fixture rateLimit = fixture();
        rateLimit.server.expect(requestTo(
                        "https://gitlab.example.com/api/v4/projects?membership=true&simple=true&per_page=100&page=1&order_by=last_activity_at&sort=desc"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> rateLimit.client.membershipProjects(
                "https://gitlab.example.com", "glpat-secret"))
                .isInstanceOf(GitLabApiException.class)
                .extracting(error -> ((GitLabApiException) error).code())
                .isEqualTo("GITLAB_RATE_LIMITED");

        Fixture projectForbidden = fixture();
        projectForbidden.server.expect(requestTo(
                        "https://gitlab.example.com/api/v4/projects/11/repository/branches?per_page=100&page=1"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> projectForbidden.client.branches(
                "https://gitlab.example.com", "glpat-secret", 11))
                .isInstanceOf(GitLabApiException.class)
                .extracting(error -> ((GitLabApiException) error).code())
                .isEqualTo("GITLAB_PERMISSION_DENIED");
    }

    @Test
    void sendsSearchToGitLabBeforeApplyingTheDiscoveryLimit() throws Exception {
        Fixture fixture = fixture();
        fixture.server.expect(requestTo(
                        "https://gitlab.example.com/api/v4/projects?membership=true&simple=true&per_page=100&page=1&order_by=last_activity_at&sort=desc&search=orders"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON)
                        .header("X-Next-Page", ""));

        assertThat(fixture.client.membershipProjects(
                "https://gitlab.example.com", "glpat-secret", "orders")).isEmpty();
        fixture.server.verify();
    }

    @Test
    void acceptsSelfHostedProjectEnvelopeAndToleratesNonDtoFields() throws Exception {
        Fixture fixture = fixture();
        fixture.server.expect(requestTo(
                        "https://gitlab.example.com/api/v4/projects?membership=true&simple=true&per_page=100&page=1&order_by=last_activity_at&sort=desc"))
                .andRespond(withSuccess("""
                        {"data":[
                          {"id":11,"name":"A","path_with_namespace":"group/a",
                           "http_url_to_repo":"https://gitlab.example.com/group/a.git",
                           "default_branch":"main","visibility":"private","archived":0,
                           "last_activity_at":"2026-08-18T00:00:00Z",
                           "permissions":[]}
                        ],"meta":{"page":1}}
                        """, MediaType.APPLICATION_JSON).header("X-Next-Page", ""));

        assertThat(fixture.client.membershipProjects(
                "https://gitlab.example.com", "glpat-secret"))
                .singleElement()
                .satisfies(project -> {
                    assertThat(project.id()).isEqualTo(11);
                    assertThat(project.pathWithNamespace()).isEqualTo("group/a");
                    assertThat(project.archived()).isFalse();
                });
        fixture.server.verify();
    }

    @Test
    void rejectsProjectCloneUrlFromAnotherAllowedGitLabHost() throws Exception {
        GitLabIntegrationProperties properties = new GitLabIntegrationProperties(
                true, null, null, "", 10, 1,
                List.of("gitlab-a.example.com", "gitlab-b.example.com"), false);
        GitLabHostPolicy policy = new GitLabHostPolicy(properties,
                ignored -> new InetAddress[]{InetAddress.getByAddress(new byte[]{8, 8, 8, 8})});
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitLabApiClient client = new GitLabApiClient(policy,
                baseUrl -> builder.baseUrl(baseUrl).build());
        server.expect(requestTo("https://gitlab-a.example.com/api/v4/projects/11"))
                .andRespond(withSuccess("""
                        {"id":11,"name":"Orders","path_with_namespace":"group/orders",
                         "http_url_to_repo":"https://gitlab-b.example.com/group/orders.git",
                         "default_branch":"main","visibility":"private","archived":false}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.project(
                "https://gitlab-a.example.com", "glpat-secret", 11))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("主机或端口不一致");
    }

    @Test
    void acceptsSelfHostedProjectDetailWrapperAndReadsMembership() throws Exception {
        Fixture fixture = fixture();
        fixture.server.expect(requestTo("https://gitlab.example.com/api/v4/projects/11"))
                .andRespond(withSuccess("""
                        {"data":{
                          "id":11,"name":"Orders","path_with_namespace":"group/orders",
                          "http_url_to_repo":"https://gitlab.example.com/group/orders.git",
                          "default_branch":"main","archived":false,
                          "permissions":{"group_access":{"access_level":"30"}}
                        }}
                        """, MediaType.APPLICATION_JSON));

        assertThat(fixture.client.project(
                "https://gitlab.example.com", "glpat-secret", 11))
                .satisfies(project -> {
                    assertThat(project.pathWithNamespace()).isEqualTo("group/orders");
                    assertThat(project.member()).isTrue();
                });
        fixture.server.verify();
    }

    @Test
    void readsAllRemoteBranchesAndSortsDefaultBranchFirst() throws Exception {
        Fixture fixture = fixture();
        fixture.server.expect(requestTo(
                        "https://gitlab.example.com/api/v4/projects/11/repository/branches?per_page=100&page=1"))
                .andRespond(withSuccess("""
                        [{"name":"develop","default":false,"protected":false,"merged":false,
                          "commit":{"id":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}}]
                        """, MediaType.APPLICATION_JSON).header("X-Next-Page", "2"));
        fixture.server.expect(requestTo(
                        "https://gitlab.example.com/api/v4/projects/11/repository/branches?per_page=100&page=2"))
                .andRespond(withSuccess("""
                        {"data":[{"name":"main","default":true,"protected":true,"merged":false,
                          "commit":{"id":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}]}
                        """, MediaType.APPLICATION_JSON).header("X-Next-Page", ""));

        assertThat(fixture.client.branches(
                "https://gitlab.example.com", "glpat-secret", 11))
                .extracting(
                        GitLabApiClient.RemoteBranch::name,
                        GitLabApiClient.RemoteBranch::defaultBranch,
                        GitLabApiClient.RemoteBranch::protectedBranch)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("main", true, true),
                        org.assertj.core.groups.Tuple.tuple("develop", false, false));
        fixture.server.verify();
    }

    private Fixture fixture() throws Exception {
        GitLabIntegrationProperties properties = new GitLabIntegrationProperties(
                true, null, null, "", 10, 1,
                List.of("gitlab.example.com"), false);
        GitLabHostPolicy policy = new GitLabHostPolicy(properties,
                ignored -> new InetAddress[]{InetAddress.getByAddress(new byte[]{8, 8, 8, 8})});
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GitLabApiClient client = new GitLabApiClient(policy,
                baseUrl -> builder.baseUrl(baseUrl).build());
        return new Fixture(client, server);
    }

    private record Fixture(GitLabApiClient client, MockRestServiceServer server) {
    }
}
