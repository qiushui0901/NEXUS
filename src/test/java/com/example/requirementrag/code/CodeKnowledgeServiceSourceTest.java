package com.example.requirementrag.code;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CodeKnowledgeServiceSourceTest {

    @TempDir
    Path temp;

    @Test
    void readsARegularFileBelowTheRepositoryRoot() throws Exception {
        Path repository = Files.createDirectory(temp.resolve("repository"));
        Files.writeString(repository.resolve("Example.java"), "line one\nline two\n");
        CodeKnowledgeService service = service(repository);

        var snippet = service.source(null, "Example.java", 2, 2);

        assertThat(snippet.filePath()).isEqualTo("Example.java");
        assertThat(snippet.startLine()).isEqualTo(2);
        assertThat(snippet.endLine()).isEqualTo(2);
        assertThat(snippet.text()).contains("line two");
    }

    @Test
    void rejectsASymbolicLinkThatEscapesTheRepositoryRoot() throws Exception {
        Path repository = Files.createDirectory(temp.resolve("repository"));
        Path outside = Files.writeString(temp.resolve("secret.txt"), "must not be exposed");
        Files.createSymbolicLink(repository.resolve("linked.txt"), outside);
        CodeKnowledgeService service = service(repository);

        assertThatThrownBy(() -> service.source(null, "linked.txt", 1, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes repository root");
    }

    private CodeKnowledgeService service(Path repository) {
        RagProperties properties = mock(RagProperties.class);
        when(properties.code()).thenReturn(new RagProperties.Code(
                "test", repository.toString(), "test-code", List.of(), List.of(), 1_000_000));
        return new CodeKnowledgeService(properties, mock(ProjectRegistry.class),
                mock(JavaCodeScanner.class), mock(CodeQdrantStore.class));
    }

    @Test
    void rejectsInvalidLineRanges() throws Exception {
        Path root = Files.createTempDirectory("nexus-source-lines-");
        Path source = root.resolve("Hero.java");
        Files.writeString(source, "line1\nline2\nline3\n");
        RagProperties properties = mock(RagProperties.class);
        RagProperties.Code code = new RagProperties.Code("demo", root.toString(), "code",
                List.of(), List.of(), 1_000_000);
        when(properties.code()).thenReturn(code);
        com.example.requirementrag.config.ProjectRegistry registry = mock(ProjectRegistry.class);
        when(registry.require("demo")).thenReturn(new com.example.requirementrag.config.RagProperties.ProjectConfig(
                "demo", "Demo", "demo", "server", "req", "code", root.toString(), null, null,
                List.of(), List.of(), 1_000_000));
        CodeKnowledgeService service = new CodeKnowledgeService(properties,
                registry, mock(JavaCodeScanner.class), mock(CodeQdrantStore.class));

        assertThatThrownBy(() -> service.source("demo", "Hero.java", 10, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法行范围");
        assertThatThrownBy(() -> service.source("demo", "Hero.java", 0, 5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法行范围");
        assertThatThrownBy(() -> service.source("demo", "Hero.java", 5, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非法行范围");
        assertThatThrownBy(() -> service.source("demo", "Hero.java", 1, 99))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超出文件长度");
    }
}
