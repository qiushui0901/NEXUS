package com.example.requirementrag.web;

import com.example.requirementrag.code.CodeQdrantStore;
import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.UserContext;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import com.example.requirementrag.wiki.WikiRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RuntimeStatusControllerTest {

    private final RagProperties properties = mock(RagProperties.class);
    private final ProjectRegistry projectRegistry = mock(ProjectRegistry.class);
    private final QdrantHybridStore requirementStore = mock(QdrantHybridStore.class);
    private final CodeQdrantStore codeStore = mock(CodeQdrantStore.class);
    private final WikiRepository wikiRepository = mock(WikiRepository.class);
    private final ProjectAccessGuard accessGuard = mock(ProjectAccessGuard.class);
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    @Test
    void reportsQdrantConfiguredApiModelsAndGitlab() {
        ProbeClients probes = probes();
        probes.qdrantServer.expect(requestTo("http://qdrant.test/collections"))
                .andRespond(withSuccess());
        probes.modelServer.expect(requestTo("http://models.test/models"))
                .andRespond(withSuccess("""
                        {"object":"list","data":[
                          {"id":"text-embedding-v4"},
                          {"id":"gpt-5.6-sol"}
                        ]}
                        """, MediaType.APPLICATION_JSON));
        probes.gitlabServer.expect(requestTo("http://gitlab.test/explore"))
                .andRespond(withSuccess());

        RuntimeStatusController.RuntimeSnapshot snapshot = controller(
                probes, List.of("text-embedding-v4", "gpt-5.6-sol")).status(request);

        assertThat(snapshot.state()).isEqualTo("READY");
        assertThat(snapshot.coreReady()).isTrue();
        assertThat(snapshot.services()).extracting(RuntimeStatusController.ServiceCheck::name)
                .containsExactly("Qdrant", "API 模型", "GitLab");
        assertThat(snapshot.services().get(1).message()).isEqualTo("已验证 2 个配置模型");
        probes.verify();
    }

    @Test
    void marksMissingModelAsCoreFailureAndGitlabAsDegradable() {
        ProbeClients probes = probes();
        probes.qdrantServer.expect(requestTo("http://qdrant.test/collections"))
                .andRespond(withSuccess());
        probes.modelServer.expect(requestTo("http://models.test/models"))
                .andRespond(withSuccess("""
                        {"object":"list","data":[{"id":"text-embedding-v4"}]}
                        """, MediaType.APPLICATION_JSON));
        probes.gitlabServer.expect(requestTo("http://gitlab.test/explore"))
                .andRespond(withServerError());

        RuntimeStatusController.RuntimeSnapshot snapshot = controller(
                probes, List.of("text-embedding-v4", "gpt-5.6-sol")).status(request);

        assertThat(snapshot.state()).isEqualTo("NOT_READY");
        assertThat(snapshot.coreReady()).isFalse();
        assertThat(snapshot.services()).satisfiesExactly(
                qdrant -> assertThat(qdrant.available()).isTrue(),
                models -> {
                    assertThat(models.available()).isFalse();
                    assertThat(models.required()).isTrue();
                    assertThat(models.message()).isEqualTo("有 1 个配置模型当前不可用");
                },
                gitlab -> {
                    assertThat(gitlab.available()).isFalse();
                    assertThat(gitlab.required()).isFalse();
                });
        probes.verify();
    }

    private RuntimeStatusController controller(ProbeClients probes, List<String> models) {
        when(accessGuard.currentUser(request)).thenReturn(UserContext.defaultAdmin());
        when(projectRegistry.all()).thenReturn(List.of());
        return new RuntimeStatusController(properties, projectRegistry, requirementStore, codeStore,
                wikiRepository, accessGuard, probes.qdrantClient, probes.modelClient, probes.gitlabClient,
                "/explore", models);
    }

    private ProbeClients probes() {
        RestClient.Builder qdrantBuilder = RestClient.builder().baseUrl("http://qdrant.test");
        RestClient.Builder modelBuilder = RestClient.builder().baseUrl("http://models.test");
        RestClient.Builder gitlabBuilder = RestClient.builder().baseUrl("http://gitlab.test");
        MockRestServiceServer qdrantServer = MockRestServiceServer.bindTo(qdrantBuilder).build();
        MockRestServiceServer modelServer = MockRestServiceServer.bindTo(modelBuilder).build();
        MockRestServiceServer gitlabServer = MockRestServiceServer.bindTo(gitlabBuilder).build();
        return new ProbeClients(qdrantBuilder.build(), qdrantServer,
                modelBuilder.build(), modelServer, gitlabBuilder.build(), gitlabServer);
    }

    private record ProbeClients(
            RestClient qdrantClient,
            MockRestServiceServer qdrantServer,
            RestClient modelClient,
            MockRestServiceServer modelServer,
            RestClient gitlabClient,
            MockRestServiceServer gitlabServer) {
        void verify() {
            qdrantServer.verify();
            modelServer.verify();
            gitlabServer.verify();
        }
    }
}
