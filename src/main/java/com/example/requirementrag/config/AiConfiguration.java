package com.example.requirementrag.config;

import com.example.requirementrag.rerank.BgeReranker;
import com.example.requirementrag.rerank.HttpBgeReranker;
import com.example.requirementrag.rerank.ResilientBgeReranker;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * AI 与外部服务 Bean 配置：ChatClient、Qdrant 客户端、BGE 重排器。
 */
@Configuration
@EnableConfigurationProperties(RagProperties.class)
public class AiConfiguration {
    private static final int CONNECT_TIMEOUT_MS = 2_000;
    private static final int READ_TIMEOUT_MS = 5_000;

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
                .requestFactory(externalRequestFactory())
                .build();
    }

    /** 注册带降级与有界连接/读取超时的 BGE 重排器 Bean。 */
    @Bean
    BgeReranker bgeReranker(RestClient.Builder builder, RagProperties properties) {
        RestClient client = builder
                .baseUrl(properties.bge().baseUrl())
                .requestFactory(externalRequestFactory())
                .build();
        return new ResilientBgeReranker(new HttpBgeReranker(client, properties.bge()));
    }

    private SimpleClientHttpRequestFactory externalRequestFactory() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        requestFactory.setReadTimeout(READ_TIMEOUT_MS);
        return requestFactory;
    }
}
