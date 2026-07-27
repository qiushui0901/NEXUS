package com.example.requirementrag.code;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.model.CodeIndexJobState;
import com.example.requirementrag.model.CodeIndexJobStatus;
import com.example.requirementrag.model.CodeIndexResponse;
import com.example.requirementrag.retrieval.EmbeddingUnavailableException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeIndexJobServiceTest {

    @Test
    void completesIndexAndPublishesResult() throws Exception {
        CodeKnowledgeService knowledgeService = mock(CodeKnowledgeService.class);
        ProjectRegistry registry = registryFor("project-a");
        when(knowledgeService.index("project-a"))
                .thenReturn(new CodeIndexResponse("project-a", "abc123", 12, 48));
        CodeIndexJobService service = new CodeIndexJobService(knowledgeService, registry, Runnable::run);

        CodeIndexJobStatus returned = service.start("project-a");
        CodeIndexJobStatus status = service.status("project-a");

        assertThat(returned.state()).isEqualTo(CodeIndexJobState.RUNNING);
        assertThat(status.state()).isEqualTo(CodeIndexJobState.COMPLETED);
        assertThat(status.commitSha()).isEqualTo("abc123");
        assertThat(status.files()).isEqualTo(12);
        assertThat(status.chunks()).isEqualTo(48);
    }

    @Test
    void keepsSingleRunningJobPerProject() throws Exception {
        CodeKnowledgeService knowledgeService = mock(CodeKnowledgeService.class);
        ProjectRegistry registry = registryFor("project-a");
        AtomicReference<Runnable> queued = new AtomicReference<>();
        Executor executor = queued::set;
        CodeIndexJobService service = new CodeIndexJobService(knowledgeService, registry, executor);

        CodeIndexJobStatus first = service.start("project-a");
        CodeIndexJobStatus second = service.start("project-a");

        assertThat(first.state()).isEqualTo(CodeIndexJobState.RUNNING);
        assertThat(second).isEqualTo(first);
        assertThat(queued.get()).isNotNull();
        when(knowledgeService.index("project-a"))
                .thenReturn(new CodeIndexResponse("project-a", "abc123", 1, 2));
        queued.get().run();
        verify(knowledgeService, times(1)).index("project-a");
    }

    @Test
    void reportsSafeFailureWithoutDroppingTheJobState() throws Exception {
        CodeKnowledgeService knowledgeService = mock(CodeKnowledgeService.class);
        ProjectRegistry registry = registryFor("project-a");
        when(knowledgeService.index("project-a"))
                .thenThrow(new EmbeddingUnavailableException("请确认 Ollama 与嵌入模型可用",
                        new IllegalStateException("provider internals")));
        CodeIndexJobService service = new CodeIndexJobService(knowledgeService, registry, Runnable::run);

        service.start("project-a");
        CodeIndexJobStatus status = service.status("project-a");

        assertThat(status.state()).isEqualTo(CodeIndexJobState.FAILED);
        assertThat(status.message()).contains("Ollama").doesNotContain("provider internals");
        assertThat(status.completedAt()).isNotBlank();
    }

    private ProjectRegistry registryFor(String projectId) {
        ProjectRegistry registry = mock(ProjectRegistry.class);
        RagProperties.ProjectConfig project = mock(RagProperties.ProjectConfig.class);
        when(project.id()).thenReturn(projectId);
        when(registry.require(projectId)).thenReturn(project);
        return registry;
    }
}
