package com.example.requirementrag.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 启动期配置校验：关键配置错误（URL 非法、topK/超时/缓存非正、集合名空、仓库路径缺失）在启动时失败，
 * 而不是推迟到首次请求。校验失败抛出 IllegalStateException 中断 Spring 启动。
 */
@Component
public class RagConfigValidator {

    private static final Logger log = LoggerFactory.getLogger(RagConfigValidator.class);

    private final RagProperties properties;
    private final Environment environment;

    public RagConfigValidator(RagProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @PostConstruct
    public void validate() {
        List<String> errors = new ArrayList<>();
        validateQdrant(errors);
        validateRetrieval(errors);
        validateUrls(errors);
        validateCode(errors);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("配置校验失败:\n- " + String.join("\n- ", errors));
        }
        log.info("配置校验通过: qdrant={} retrieval 已就绪", properties.qdrant().baseUrl());
    }

    private void validateQdrant(List<String> errors) {
        if (blank(properties.qdrant().baseUrl())) {
            errors.add("app.rag.qdrant.base-url 不能为空");
        } else if (!isHttpUrl(properties.qdrant().baseUrl())) {
            errors.add("app.rag.qdrant.base-url 不是合法的 http(s) URL: " + properties.qdrant().baseUrl());
        }
        if (blank(properties.qdrant().collection())) {
            errors.add("app.rag.qdrant.collection 不能为空");
        }
    }

    private void validateRetrieval(List<String> errors) {
        RagProperties.Retrieval retrieval = properties.retrieval();
        if (retrieval == null) {
            return;
        }
        requirePositive(errors, "app.rag.retrieval.dense-top-k", retrieval.denseTopK());
        requirePositive(errors, "app.rag.retrieval.sparse-top-k", retrieval.sparseTopK());
        requirePositive(errors, "app.rag.retrieval.hybrid-top-k", retrieval.hybridTopK());
        if (retrieval.denseTopK() > 0 && retrieval.hybridTopK() > 0 && retrieval.denseTopK() < retrieval.hybridTopK()) {
            errors.add("app.rag.retrieval.dense-top-k 不应小于 hybrid-top-k（融合结果不会超过单路召回）");
        }
        if (retrieval.branchTimeoutMs() <= 0) {
            errors.add("app.rag.retrieval.branch-timeout-ms 必须大于 0");
        }
        if (retrieval.parallelism() <= 0) {
            errors.add("app.rag.retrieval.parallelism 必须大于 0");
        }
        if (retrieval.resultCacheTtlSeconds() < 0 || retrieval.resultCacheMaxEntries() < 0
                || retrieval.embeddingCacheTtlSeconds() < 0 || retrieval.embeddingCacheMaxEntries() < 0) {
            errors.add("检索/嵌入缓存 TTL 与最大条目数不能为负");
        }
        if (retrieval.resolvedLlmRerankSkipGap() < 0) {
            errors.add("app.rag.retrieval.llm-rerank-skip-gap 不能为负");
        }
    }

    private void validateUrls(List<String> errors) {
        if (properties.bge() != null && !blank(properties.bge().baseUrl()) && !isHttpUrl(properties.bge().baseUrl())) {
            errors.add("app.rag.bge.base-url 不是合法的 http(s) URL: " + properties.bge().baseUrl());
        }
        String openAiBaseUrl = environment.getProperty("spring.ai.openai.base-url", "");
        if (!blank(openAiBaseUrl) && !isHttpUrl(openAiBaseUrl)) {
            errors.add("spring.ai.openai.base-url 不是合法的 http(s) URL: " + openAiBaseUrl);
        }
        String embeddingModel = environment.getProperty("spring.ai.openai.embedding.options.model", "");
        if (blank(embeddingModel)) {
            errors.add("spring.ai.openai.embedding.options.model 不能为空（嵌入模型未配置）");
        }
    }

    private void validateCode(List<String> errors) {
        RagProperties.Code code = properties.code();
        if (code == null) {
            return;
        }
        String repositoryPath = environment.getProperty("app.rag.code.repository-path",
                code.repositoryPath() == null ? "" : code.repositoryPath());
        if (!blank(repositoryPath) && !Files.isDirectory(Path.of(repositoryPath))) {
            errors.add("app.rag.code.repository-path 不是有效目录: " + repositoryPath);
        }
    }

    private void requirePositive(List<String> errors, String name, int value) {
        if (value <= 0) {
            errors.add(name + " 必须大于 0（当前 " + value + "）");
        }
    }

    private boolean isHttpUrl(String value) {
        try {
            URI uri = new URI(value);
            return "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
