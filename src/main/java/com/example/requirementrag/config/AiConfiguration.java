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
import com.fasterxml.jackson.databind.json.JsonMapper;

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

    /** Boot 3.x 自动配置只提供 ObjectMapper，补充 JsonMapper bean 供重排器序列化使用。 */
    @Bean
    JsonMapper jsonMapper() {
        return new JsonMapper();
    }

    /** 显式注册 NEXUS MCP 工具（Spring AI 1.x 不自动扫描 @Tool，且避免与自动发现重复）。 */
    @Bean
    org.springframework.ai.tool.ToolCallbackProvider nexusMcpToolCallbacks(
            @org.springframework.context.annotation.Lazy com.example.requirementrag.mcp.NexusMcpTools tools) {
        return org.springframework.ai.tool.method.MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
    }

    /** 配置 Qdrant REST 客户端，设置连接与读取超时。 */
    @Bean
    RestClient qdrantRestClient(RagProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.qdrant().baseUrl())
                .requestFactory(qdrantRequestFactory())
                .build();
    }

    /** 注册有界连接/读取超时的 BGE 重排器；统一管线负责结构化降级。 */
    @Bean
    BgeReranker bgeReranker(RestClient.Builder builder, RagProperties properties, JsonMapper jsonMapper) {        RestClient client = builder
                .baseUrl(properties.bge().baseUrl())
                .requestFactory(bgeRequestFactory(properties.bge()))
                .build();
        return new HttpBgeReranker(client, properties.bge(), jsonMapper,
                properties.retrieval().resolvedEnrichedBgePassageEnabled());
    }

    /** 构造 Qdrant 固定超时的请求工厂。 */
    SimpleClientHttpRequestFactory qdrantRequestFactory() {
        return requestFactory(QDRANT_CONNECT_TIMEOUT_MS, QDRANT_READ_TIMEOUT_MS);
    }

    /** 构造 BGE 请求工厂，超时值取自配置。 */
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
