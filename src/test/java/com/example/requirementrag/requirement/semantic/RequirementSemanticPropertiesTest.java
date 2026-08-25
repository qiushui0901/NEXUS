package com.example.requirementrag.requirement.semantic;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/** 语义属性治理：INFERRED 候选默认关闭，符合“推断不能自动作为确认事实”原则。 */
class RequirementSemanticPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @Test
    void allowInferredCandidateDefaultsToFalseWhenNotConfigured() {
        contextRunner.run(context -> {
            RequirementSemanticProperties properties = context.getBean(RequirementSemanticProperties.class);
            assertThat(properties.allowInferredCandidate())
                    .as("allow-inferred-candidate 未配置时必须默认 false")
                    .isFalse();
        });
    }

    @Configuration
    @EnableConfigurationProperties(RequirementSemanticProperties.class)
    static class Config {
    }
}
