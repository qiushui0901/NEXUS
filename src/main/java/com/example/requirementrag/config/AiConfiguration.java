package com.example.requirementrag.config;

import com.example.requirementrag.rerank.BgeReranker;
import com.example.requirementrag.rerank.HttpBgeReranker;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * AI 与外部服务 Bean 配置：ChatClient、Qdrant 客户端、BGE 重排器。
 */
@Configuration
@EnableConfigurationProperties(RagProperties.class)
public class AiConfiguration {
    private static final int QDRANT_CONNECT_TIMEOUT_MS = 2_000;
    private static final int QDRANT_READ_TIMEOUT_MS = 5_000;

    /** 构建 Spring AI ChatClient。 */
    @Bean
    ChatClient chatClient(@Qualifier("openAiChatModel") ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    /** 配置 Qdrant REST 客户端，设置连接与读取超时。 */
    @Bean
    RestClient qdrantRestClient(RestClient.Builder builder, RagProperties properties) {
        return builder
                .baseUrl(properties.qdrant().baseUrl())
                .requestFactory(qdrantRequestFactory())
                .build();
    }

    /** 注册有界连接/读取超时的 BGE 重排器；统一管线负责结构化降级。 */
    @Bean
    BgeReranker bgeReranker(RestClient.Builder builder, RagProperties properties, JsonMapper jsonMapper) {
        RestClient client = builder
                .baseUrl(properties.bge().baseUrl())
                .requestFactory(bgeRequestFactory(properties.bge()))
                .build();
        return new HttpBgeReranker(client, properties.bge(), jsonMapper,
                properties.retrieval().resolvedEnrichedBgePassageEnabled());
    }

    SimpleClientHttpRequestFactory qdrantRequestFactory() {
        return requestFactory(QDRANT_CONNECT_TIMEOUT_MS, QDRANT_READ_TIMEOUT_MS);
    }

    SimpleClientHttpRequestFactory bgeRequestFactory(RagProperties.Bge properties) {
        return requestFactory(properties.connectTimeoutMs(), properties.readTimeoutMs());
    }

    private SimpleClientHttpRequestFactory requestFactory(int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeoutMs);
        requestFactory.setReadTimeout(readTimeoutMs);
        return requestFactory;
    }
}
