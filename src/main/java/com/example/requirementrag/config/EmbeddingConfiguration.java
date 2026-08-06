package com.example.requirementrag.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 嵌入模型 Bean 配置。
 */
@Configuration
public class EmbeddingConfiguration {

    /** 将 OpenAI 兼容 API 嵌入模型设为主 EmbeddingModel。 */
    @Bean
    @Primary
    EmbeddingModel primaryEmbeddingModel(@Qualifier("openAiEmbeddingModel") EmbeddingModel openAiEmbeddingModel) {
        return openAiEmbeddingModel;
    }
}
