package com.example.requirementrag.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectRegistryDynamicTest {

    @Test
    void registersAndRemovesDynamicProjectsWithoutChangingDefaultProject() {
        RagProperties properties = mock(RagProperties.class);
        RagProperties.ProjectConfig configured = project("static-project", "/tmp/static");
        when(properties.projects()).thenReturn(List.of(configured));
        ProjectRegistry registry = new ProjectRegistry(properties);
        RagProperties.ProjectConfig dynamic = project("dynamic-project", "/tmp/dynamic");

        assertThat(registry.registerDynamic(dynamic)).isTrue();
        assertThat(registry.registerDynamic(dynamic)).isFalse();
        assertThat(registry.require("dynamic-project")).isEqualTo(dynamic);
        assertThat(registry.defaultProject()).isEqualTo(configured);
        assertThat(registry.all()).containsExactly(configured, dynamic);

        assertThat(registry.unregisterDynamic("dynamic-project")).isTrue();
        assertThat(registry.find("dynamic-project")).isEmpty();
    }

    @Test
    void refusesToOverrideStaticOrExistingDynamicProjects() {
        RagProperties properties = mock(RagProperties.class);
        when(properties.projects()).thenReturn(List.of(project("static-project", "/tmp/static")));
        ProjectRegistry registry = new ProjectRegistry(properties);
        registry.registerDynamic(project("dynamic-project", "/tmp/dynamic"));

        assertThatThrownBy(() -> registry.registerDynamic(project("static-project", "/tmp/other")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("静态项目");
        assertThatThrownBy(() -> registry.registerDynamic(project("dynamic-project", "/tmp/other")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已存在");
    }

    private RagProperties.ProjectConfig project(String id, String repositoryPath) {
        return new RagProperties.ProjectConfig(id, id, "group", "server",
                id + "_requirements", id + "_code", repositoryPath, "group/" + id,
                new RagProperties.ProjectKnowledge(false, null, null, null, null, null, null, 800),
                List.of(), List.of("/.git/"), 1_000_000);
    }
}
