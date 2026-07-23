package com.example.requirementrag.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class GenerationChatOptionsTest {

    @Test
    void omitsTemperatureForClaudeGenerationModels() {
        var options = GenerationChatOptions.forModel("claude-sonnet-5").build();

        assertThat(options.getModel()).isEqualTo("claude-sonnet-5");
        assertThat(options.getTemperature()).isNull();
    }

    @Test
    void doesNotRestoreTemperatureThroughGlobalOpenAiDefaults() throws IOException {
        String yaml = new ClassPathResource("application.yml")
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(yaml).doesNotContain("temperature:");
    }
}
