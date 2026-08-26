package com.example.requirementrag.requirement.semantic;

import com.example.requirementrag.config.ProjectRegistry;
import com.example.requirementrag.config.RagProperties;
import com.example.requirementrag.knowledge.multisource.RequirementSemanticCandidateAdapter;
import com.example.requirementrag.retrieval.QdrantHybridStore;
import com.example.requirementrag.web.ProjectAccessGuard;
import com.example.requirementrag.web.RequirementSemanticBuildController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Spring 装配验证：app.rag.requirement-semantic.enabled 开关决定整条语义链路
 * （存储 / 校验 / Prompt / 标注 / 构建 / 候选适配器 / Controller）是否装配。
 * 关闭时全部 Bean 缺失，现有检索链路完全不受影响。
 */
class RequirementSemanticAssemblyTest {
    @TempDir Path tempDir;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class)
            .withBean(RequirementSemanticProperties.class, this::semanticProperties)
            .withBean(ChatClient.class, () -> mock(ChatClient.class))
            .withBean(RagProperties.class, () -> mock(RagProperties.class))
            .withBean(QdrantHybridStore.class, () -> mock(QdrantHybridStore.class))
            .withBean(ProjectRegistry.class, () -> mock(ProjectRegistry.class))
            .withBean(ProjectAccessGuard.class, () -> mock(ProjectAccessGuard.class))
            .withUserConfiguration(SemanticBeans.class);

    private RequirementSemanticProperties semanticProperties() {
        return new RequirementSemanticProperties(true, true, false, false,
                tempDir.resolve("semantic.db").toString(), "test-model",
                "requirement-semantic-v1", "v1", 12_000, 30, 30, 30, 30, 20, 30, 0,
                1_000, 1_800, 1_000_000, 400, true, 5_000);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void enabledFlagControlsWholeSemanticBeanGraph(boolean enabled) {
        runner.withPropertyValues("app.rag.requirement-semantic.enabled=" + enabled).run(context -> {
            if (enabled) {
                assertThat(context).hasSingleBean(SQLiteRequirementSemanticStore.class);
                assertThat(context).hasSingleBean(RequirementSemanticAnnotationValidator.class);
                assertThat(context).hasSingleBean(RequirementSemanticPromptService.class);
                assertThat(context).hasSingleBean(RequirementSemanticAnnotationService.class);
                assertThat(context).hasSingleBean(RequirementSemanticBuildService.class);
                assertThat(context).hasSingleBean(RequirementSemanticCandidateAdapter.class);
                assertThat(context).hasSingleBean(RequirementSemanticBuildController.class);
                assertThat(context).hasNotFailed();
            } else {
                assertThat(context).doesNotHaveBean(SQLiteRequirementSemanticStore.class);
                assertThat(context).doesNotHaveBean(RequirementSemanticAnnotationValidator.class);
                assertThat(context).doesNotHaveBean(RequirementSemanticPromptService.class);
                assertThat(context).doesNotHaveBean(RequirementSemanticAnnotationService.class);
                assertThat(context).doesNotHaveBean(RequirementSemanticBuildService.class);
                assertThat(context).doesNotHaveBean(RequirementSemanticCandidateAdapter.class);
                assertThat(context).doesNotHaveBean(RequirementSemanticBuildController.class);
                assertThat(context).hasNotFailed();
            }
        });
    }

    /** 直接导入注解组件类：@ConditionalOnProperty 与构造注入按生产路径求值。 */
    @Configuration(proxyBeanMethods = false)
    @Import({SQLiteRequirementSemanticStore.class,
            RequirementSemanticAnnotationValidator.class,
            RequirementSemanticPromptService.class,
            RequirementSemanticTextComposer.class,
            RequirementSemanticAnnotationService.class,
            RequirementSemanticBuildService.class,
            RequirementSemanticCandidateAdapter.class,
            RequirementSemanticBuildController.class})
    static class SemanticBeans {
    }
}
