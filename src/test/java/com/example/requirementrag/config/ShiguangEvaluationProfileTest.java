package com.example.requirementrag.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShiguangEvaluationProfileTest {

    @Test
    void bindsAnIsolatedReadOnlySafeProjectScope() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource(
                "shiguang-test", Map.of("SHIGUANG_REPOSITORY_PATH", "/authorized/qiushui-shiguang")));
        new YamlPropertySourceLoader()
                .load("application-shiguang-eval",
                        new ClassPathResource("application-shiguang-eval.yml"))
                .forEach(environment.getPropertySources()::addLast);

        RagProperties properties = Binder.get(environment)
                .bind("app.rag", RagProperties.class)
                .get();
        RagProperties.ProjectConfig project = properties.projects().getFirst();

        assertEquals("shiguang-eval", project.id());
        assertEquals("/authorized/qiushui-shiguang", project.repositoryPath());
        assertEquals("requirements_shiguang_eval", project.requirementCollection());
        assertEquals("code_shiguang_eval", project.codeCollection());
        assertEquals("qiushui0901/qiushui-shiguang", project.gitPath());
        assertTrue(project.includes().contains("/shiguang-auth/"));
        assertTrue(project.includes().contains("/shiguang-agent/"));
        assertTrue(project.excludes().contains("/src/main/resources/"));
        assertTrue(project.excludes().contains("/src/test/resources/"));
        assertTrue(project.excludes().contains("/简历.md"));
        assertFalse(project.excludes().isEmpty());

        RagProperties.ProjectConfig documentV2 = properties.projects().get(1);
        assertEquals("document-v2-eval", documentV2.id());
        assertEquals("requirements_document_v2_eval", documentV2.requirementCollection());
        assertEquals("code_document_v2_eval", documentV2.codeCollection());
        assertEquals("document-v2-corpus", documentV2.knowledge().documentId());
        assertEquals("document-v2-v2", documentV2.knowledge().version());
    }
}
