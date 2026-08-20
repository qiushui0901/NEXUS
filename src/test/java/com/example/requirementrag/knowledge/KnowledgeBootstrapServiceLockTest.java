package com.example.requirementrag.knowledge;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.observability.RagObservability;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import com.example.requirementrag.service.RequirementIngestionService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeBootstrapServiceLockTest {

    @Test
    void releasesProjectLockWhenKnowledgeConfigurationFailsBeforeIngestion() {
        RagProperties properties = mock(RagProperties.class);
        ProjectRegistry registry = mock(ProjectRegistry.class);
        BootstrapState state = new BootstrapState();
        RagProperties.ProjectConfig project = new RagProperties.ProjectConfig(
                "project-a", "Project A", "group", "server",
                "project_a_requirements", "project_a_code", "/tmp/project-a", "group/project-a",
                new RagProperties.ProjectKnowledge(true, null, null, "document-a", "1.0",
                        null, null, 800),
                List.of(), List.of(), 1_000_000);
        when(registry.require("project-a")).thenReturn(project);
        KnowledgeBootstrapService service = new KnowledgeBootstrapService(
                properties, registry, mock(ZipHtmlKnowledgeLoader.class),
                mock(RequirementIngestionService.class), state,
                mock(RagObservability.class), mock(QdrantHybridStore.class));

        assertThatThrownBy(() -> service.bootstrap("project-a"))
                .isInstanceOf(NullPointerException.class);
        assertThat(state.running("project-a")).isFalse();
        assertThat(state.status().state()).isEqualTo("FAILED");

        assertThatThrownBy(() -> service.bootstrap("project-a"))
                .isInstanceOf(NullPointerException.class);
        assertThat(state.running("project-a")).isFalse();
    }
}
