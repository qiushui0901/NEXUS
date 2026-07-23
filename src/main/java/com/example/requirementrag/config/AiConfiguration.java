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

    /** 构建 Spring AI ChatClient。 */
    @Bean
    ChatClient chatClient(@Qualifier("openAiChatModel") ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    /** 配置 Qdrant REST 客户端，设置连接与读取超时。 */
    @Bean
    RestClient qdrantRestClient(RestClient.Builder builder, RagProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(2_000);
        requestFactory.setReadTimeout(5_000);
        return builder
                .baseUrl(properties.qdrant().baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    /** 注册带降级能力的 BGE 重排器 Bean。 */
    @Bean
    BgeReranker bgeReranker(RestClient.Builder builder, RagProperties properties) {
        return new ResilientBgeReranker(new HttpBgeReranker(builder.baseUrl(properties.bge().baseUrl()).build(), properties.bge()));
    }
}
