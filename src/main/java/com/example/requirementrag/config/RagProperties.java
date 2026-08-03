package com.example.requirementrag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.util.List;

/**
 * RAG 应用配置属性，绑定 app.rag 前缀。
 * 支持多项目注册表（projects 列表）与单项目回退（knowledge/code 默认值）。
 */
@ConfigurationProperties("app.rag")
public record RagProperties(Qdrant qdrant, Bge bge, Llm llm, Retrieval retrieval,
                             Knowledge knowledge, Review review, Code code,
                             List<ProjectConfig> projects) {

    public RagProperties {
        projects = projects == null ? List.of() : projects;
    }

    /** 评审问题数量相关配置。 */
    public record Review(int minQuestions, int maxQuestions, int currentVersionQuestions, int priorVersionQuestions) {
    }

    /** 知识库引导与数据源路径配置。 */
    public record Knowledge(
            boolean bootstrapEnabled,
            String zipPath,
            String xlsxPath,
            String documentId,
            String version,
            String zipFolderPrefix,
            String xlsxSheetPrefix,
            int minHtmlBytes
    ) {
        /** 解析 ZIP 内目标文件夹前缀，未配置时回退为版本号。 */
        public String resolvedZipFolderPrefix() {
            if (zipFolderPrefix != null && !zipFolderPrefix.isBlank()) {
                return zipFolderPrefix.trim().replace('\\', '/');
            }
            return version == null ? "" : version.trim();
        }
    }

    /** Qdrant 向量库连接配置。 */
    public record Qdrant(String baseUrl, String collection) {
    }

    /** 代码库索引与向量化配置。 */
    public record Code(
            String projectId,
            String repositoryPath,
            String collection,
            List<String> includePathSubstrings,
            List<String> excludePathSubstrings,
            int maxFileBytes
    ) {
        public List<String> includes() {
            return includePathSubstrings == null ? List.of() : includePathSubstrings;
        }

        public List<String> excludes() {
            return excludePathSubstrings == null ? List.of() : excludePathSubstrings;
        }

        public int resolvedMaxFileBytes() {
            return maxFileBytes <= 0 ? 1_000_000 : maxFileBytes;
        }
    }

    /** BGE 重排服务配置。 */
    public record Bge(String baseUrl, String path, String apiKey, int connectTimeoutMs, int readTimeoutMs) {
        public static final int DEFAULT_CONNECT_TIMEOUT_MS = 2_000;
        public static final int DEFAULT_READ_TIMEOUT_MS = 10_000;
        private static final int MAX_TIMEOUT_MS = 120_000;

        @ConstructorBinding
        public Bge {
            connectTimeoutMs = resolveTimeout("connectTimeoutMs", connectTimeoutMs, DEFAULT_CONNECT_TIMEOUT_MS);
            readTimeoutMs = resolveTimeout("readTimeoutMs", readTimeoutMs, DEFAULT_READ_TIMEOUT_MS);
        }

        /** Compatibility constructor for callers created before BGE-specific timeouts were introduced. */
        public Bge(String baseUrl, String path, String apiKey) {
            this(baseUrl, path, apiKey, DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS);
        }

        private static int resolveTimeout(String name, int value, int defaultValue) {
            if (value == 0) {
                return defaultValue;
            }
            if (value < 0 || value > MAX_TIMEOUT_MS) {
                throw new IllegalArgumentException(name + " must be between 1 and " + MAX_TIMEOUT_MS + " ms");
            }
            return value;
        }
    }

    /** LLM 生成、重排与路由模型名称配置。 */
    public record Llm(String generationModel, String rerankerModel, String routingModel) {
        public String resolvedRoutingModel() {
            return (routingModel != null && !routingModel.isBlank()) ? routingModel : rerankerModel;
        }
    }

    /** 检索各阶段 topK、并行、重排与缓存参数配置。 */
    public record Retrieval(
            int denseTopK,
            int sparseTopK,
            int hybridTopK,
            int bgeTopK,
            int llmTopK,
            boolean llmRerankEnabled,
            long branchTimeoutMs,
            int parallelism,
            int circuitBreakerFailureThreshold,
            long circuitBreakerOpenMs,
            long resultCacheTtlSeconds,
            int resultCacheMaxEntries,
            long embeddingCacheTtlSeconds,
            int embeddingCacheMaxEntries,
            Boolean childFirstRerankEnabled,
            Boolean enrichedBgePassageEnabled,
            Boolean codeQueryExpansionEnabled
    ) {
        public int resolvedBgeTopK() {
            return bgeTopK <= 0 ? 20 : bgeTopK;
        }

        public int resolvedLlmTopK() {
            return llmTopK <= 0 ? 10 : llmTopK;
        }

        public long resolvedBranchTimeoutMs() {
            return branchTimeoutMs <= 0 ? 5_000 : branchTimeoutMs;
        }

        public int resolvedParallelism() {
            return parallelism <= 0 ? 6 : Math.min(parallelism, 32);
        }

        public int resolvedCircuitBreakerFailureThreshold() {
            return circuitBreakerFailureThreshold < 0 ? 0
                    : circuitBreakerFailureThreshold == 0 ? 3 : circuitBreakerFailureThreshold;
        }

        public long resolvedCircuitBreakerOpenMs() {
            return circuitBreakerOpenMs < 0 ? 0 : circuitBreakerOpenMs == 0 ? 30_000 : circuitBreakerOpenMs;
        }

        public long resolvedResultCacheTtlSeconds() {
            return resultCacheTtlSeconds < 0 ? 0 : resultCacheTtlSeconds == 0 ? 120 : resultCacheTtlSeconds;
        }

        public int resolvedResultCacheMaxEntries() {
            return resultCacheMaxEntries < 0 ? 0 : resultCacheMaxEntries == 0 ? 1_000 : resultCacheMaxEntries;
        }

        public long resolvedEmbeddingCacheTtlSeconds() {
            return embeddingCacheTtlSeconds < 0 ? 0
                    : embeddingCacheTtlSeconds == 0 ? 900 : embeddingCacheTtlSeconds;
        }

        public int resolvedEmbeddingCacheMaxEntries() {
            return embeddingCacheMaxEntries < 0 ? 0
                    : embeddingCacheMaxEntries == 0 ? 10_000 : embeddingCacheMaxEntries;
        }

        /** 0.8.1 默认启用 child-first 重排；显式 false 可回退到 0.8 parent-first 行为。 */
        public boolean resolvedChildFirstRerankEnabled() {
            return childFirstRerankEnabled == null || childFirstRerankEnabled;
        }

        /** 0.8.1 默认向 BGE 发送有界 child + parent 上下文；显式 false 仅发送 child。 */
        public boolean resolvedEnrichedBgePassageEnabled() {
            return enrichedBgePassageEnabled == null || enrichedBgePassageEnabled;
        }

        /** 0.8.1 默认启用中英文代码意图归一化；显式 false 保持 0.8 排序。 */
        public boolean resolvedCodeQueryExpansionEnabled() {
            return codeQueryExpansionEnabled == null || codeQueryExpansionEnabled;
        }

        public String fingerprint() {
            return denseTopK + ":" + sparseTopK + ":" + hybridTopK + ":" + resolvedBgeTopK()
                    + ":" + resolvedLlmTopK() + ":" + llmRerankEnabled
                    + ":" + resolvedChildFirstRerankEnabled()
                    + ":" + resolvedEnrichedBgePassageEnabled()
                    + ":" + resolvedCodeQueryExpansionEnabled();
        }
    }

    /** 多项目注册表中的单项目配置。 */
    public record ProjectConfig(
            String id,
            String name,
            String group,
            String side,
            String requirementCollection,
            String codeCollection,
            String repositoryPath,
            String gitPath,
            ProjectKnowledge knowledge,
            List<String> includePathSubstrings,
            List<String> excludePathSubstrings,
            int maxFileBytes
    ) {
        public List<String> includes() {
            return includePathSubstrings == null ? List.of() : includePathSubstrings;
        }

        public List<String> excludes() {
            return excludePathSubstrings == null ? List.of() : excludePathSubstrings;
        }

        public int resolvedMaxFileBytes() {
            return maxFileBytes <= 0 ? 1_000_000 : maxFileBytes;
        }

        /** 转换为兼容旧接口的 Code 配置。 */
        public Code toCodeConfig() {
            return new Code(id, repositoryPath, codeCollection,
                    includePathSubstrings, excludePathSubstrings, maxFileBytes);
        }
    }

    /** 项目级知识库配置。 */
    public record ProjectKnowledge(
            boolean bootstrapEnabled,
            String zipPath,
            String xlsxPath,
            String documentId,
            String version,
            String zipFolderPrefix,
            String xlsxSheetPrefix,
            int minHtmlBytes
    ) {
        public String resolvedZipFolderPrefix() {
            if (zipFolderPrefix != null && !zipFolderPrefix.isBlank()) {
                return zipFolderPrefix.trim().replace('\\', '/');
            }
            return version == null ? "" : version.trim();
        }

        /** 转换为兼容旧接口的 Knowledge 配置。 */
        public Knowledge toKnowledge() {
            return new Knowledge(bootstrapEnabled, zipPath, xlsxPath,
                    documentId, version, zipFolderPrefix, xlsxSheetPrefix, minHtmlBytes);
        }
    }
}
