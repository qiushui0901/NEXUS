package com.example.requirementrag;

import com.example.requirementrag.integration.gitlab.GitLabIntegrationController;
import com.example.requirementrag.integration.gitlab.GitLabManagedWebhookController;
import com.example.requirementrag.integration.gitlab.GitLabSyncService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "logging.structured.format.console=",
        "management.tracing.sampling.probability=0",
        "app.rag.knowledge.bootstrap-enabled=false",
        "app.rag.auth.enabled=false",
        "app.rag.gitlab.enabled=true",
        "app.rag.gitlab.repository-root-path=target/test-gitlab-enabled/repositories",
        "app.rag.gitlab.database-path=target/test-gitlab-enabled/projects.db",
        "app.rag.gitlab.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class GitLabIntegrationEnabledApplicationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void createsGitLabIntegrationBeansWhenEnabled() {
        assertThat(context.getBeansOfType(GitLabSyncService.class)).hasSize(1);
        assertThat(context.getBeansOfType(GitLabIntegrationController.class)).hasSize(1);
        assertThat(context.getBeansOfType(GitLabManagedWebhookController.class)).hasSize(1);
    }
}
