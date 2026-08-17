package com.example.requirementrag;

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
        "app.rag.auth.enabled=false"
})
class RequirementRagApplicationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoads() {
        assertThat(context.getBeansOfType(GitLabSyncService.class)).isEmpty();
    }
}
