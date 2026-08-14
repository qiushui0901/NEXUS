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
        /** 包含路径子串列表，未配置时为默认空列表。 */
        public List<String> includes() {
            return includePathSubstrings == null ? List.of() : includePathSubstrings;
        }

        /** 排除路径子串列表，未配置时为默认空列表。 */
        public List<String> excludes() {
            return excludePathSubstrings == null ? List.of() : excludePathSubstrings;
        }

        /** 解析单文件大小上限（字节），未配置或 ≤0 时默认 1MB。 */
        public int resolvedMaxFileBytes() {
            return maxFileBytes <= 0 ? 1_000_000 : maxFileBytes;
        }
    }

    /** BGE 重排服务配置。 */
    public record Bge(String baseUrl, String path, String apiKey, int connectTimeoutMs, int readTimeoutMs) {
        public static final int DEFAULT_CONNECT_TIMEOUT_MS = 2_000;
        public static final int DEFAULT_READ_TIMEOUT_MS = 10_000;
        private static final int MAX_TIMEOUT_MS = 120_000;

        /** 归一化超时配置：0 取默认值，小于 0 或超过上限抛 IllegalArgumentException。 */
        @ConstructorBinding
        public Bge {
            connectTimeoutMs = resolveTimeout("connectTimeoutMs", connectTimeoutMs, DEFAULT_CONNECT_TIMEOUT_MS);
            readTimeoutMs = resolveTimeout("readTimeoutMs", readTimeoutMs, DEFAULT_READ_TIMEOUT_MS);
        }

        /** 兼容构造器，供引入 BGE 专属超时之前创建的调用方使用，超时取默认值。 */
        public Bge(String baseUrl, String path, String apiKey) {
            this(baseUrl, path, apiKey, DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS);
        }

        /** 校验并归一化超时毫秒数：0 表示使用默认值，越界抛异常。 */
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
    public record Llm(String generationModel, String rerankerModel, String routingModel,
                      String developmentPlanModel, String doubtReviewModel, String annotationModel) {
        @ConstructorBinding
        public Llm {
        }

        /** 兼容构造器：未配置代码语义标注模型。 */
        public Llm(String generationModel, String rerankerModel, String routingModel,
                   String developmentPlanModel, String doubtReviewModel) {
            this(generationModel, rerankerModel, routingModel, developmentPlanModel, doubtReviewModel, null);
        }

        /** 开发方案生成模型名，未配置时回退到生成模型名。 */
        public String resolvedDevelopmentPlanModel() {
            return (developmentPlanModel != null && !developmentPlanModel.isBlank())
                    ? developmentPlanModel : generationModel;
        }

        /** 存疑评审生成模型名，未配置时回退到生成模型名。 */
        public String resolvedDoubtReviewModel() {
            return (doubtReviewModel != null && !doubtReviewModel.isBlank())
                    ? doubtReviewModel : generationModel;
        }

        /** 路由模型名，未配置时回退到重排模型名。 */
        public String resolvedRoutingModel() {
            return (routingModel != null && !routingModel.isBlank()) ? routingModel : rerankerModel;
        }

        /** 代码语义标注模型名，未配置时回退到生成模型名。 */
        public String resolvedAnnotationModel() {
            return (annotationModel != null && !annotationModel.isBlank())
                    ? annotationModel : generationModel;
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
            Boolean codeQueryExpansionEnabled,
            Boolean codeBgeRerankEnabled,
            Integer codeCandidateMultiplier,
            Double llmRerankSkipGap,
            Boolean codeExactSymbolEnabled,
            Boolean codeClassScopedEnabled,
            Boolean codeStructuralRerankEnabled
    ) {
        @ConstructorBinding
        public Retrieval {
        }

        /** 兼容构造器：未配置 LLM 重排跳过阈值与代码检索增强开关。 */
        public Retrieval(int denseTopK, int sparseTopK, int hybridTopK, int bgeTopK, int llmTopK,
                         boolean llmRerankEnabled, long branchTimeoutMs, int parallelism,
                         int circuitBreakerFailureThreshold, long circuitBreakerOpenMs,
                         long resultCacheTtlSeconds, int resultCacheMaxEntries,
                         long embeddingCacheTtlSeconds, int embeddingCacheMaxEntries,
                         Boolean childFirstRerankEnabled, Boolean enrichedBgePassageEnabled,
                         Boolean codeQueryExpansionEnabled, Boolean codeBgeRerankEnabled,
                         Integer codeCandidateMultiplier) {
            this(denseTopK, sparseTopK, hybridTopK, bgeTopK, llmTopK, llmRerankEnabled, branchTimeoutMs,
                    parallelism, circuitBreakerFailureThreshold, circuitBreakerOpenMs,
                    resultCacheTtlSeconds, resultCacheMaxEntries, embeddingCacheTtlSeconds,
                    embeddingCacheMaxEntries, childFirstRerankEnabled, enrichedBgePassageEnabled,
                    codeQueryExpansionEnabled, codeBgeRerankEnabled, codeCandidateMultiplier, null,
                    null, null, null);
        }

        /** 兼容构造器：未配置代码检索增强开关（保留旧版含 LLM 重排跳过阈值的签名）。 */
        public Retrieval(int denseTopK, int sparseTopK, int hybridTopK, int bgeTopK, int llmTopK,
                         boolean llmRerankEnabled, long branchTimeoutMs, int parallelism,
                         int circuitBreakerFailureThreshold, long circuitBreakerOpenMs,
                         long resultCacheTtlSeconds, int resultCacheMaxEntries,
                         long embeddingCacheTtlSeconds, int embeddingCacheMaxEntries,
                         Boolean childFirstRerankEnabled, Boolean enrichedBgePassageEnabled,
                         Boolean codeQueryExpansionEnabled, Boolean codeBgeRerankEnabled,
                         Integer codeCandidateMultiplier, Double llmRerankSkipGap) {
            this(denseTopK, sparseTopK, hybridTopK, bgeTopK, llmTopK, llmRerankEnabled, branchTimeoutMs,
                    parallelism, circuitBreakerFailureThreshold, circuitBreakerOpenMs,
                    resultCacheTtlSeconds, resultCacheMaxEntries, embeddingCacheTtlSeconds,
                    embeddingCacheMaxEntries, childFirstRerankEnabled, enrichedBgePassageEnabled,
                    codeQueryExpansionEnabled, codeBgeRerankEnabled, codeCandidateMultiplier,
                    llmRerankSkipGap, null, null, null);
        }

        /** BGE 重排候选数，未配置或 ≤0 时默认 20。 */
        public int resolvedBgeTopK() {
            return bgeTopK <= 0 ? 20 : bgeTopK;
        }

        /** LLM 重排跳过阈值：BGE top1 与后续候选分差达到该值时跳过 LLM 重排；≤0 表示不启用。 */
        public double resolvedLlmRerankSkipGap() {
            return llmRerankSkipGap == null ? 0 : llmRerankSkipGap;
        }

        /** LLM 重排候选数，未配置或 ≤0 时默认 10。 */
        public int resolvedLlmTopK() {
            return llmTopK <= 0 ? 10 : llmTopK;
        }

        /** 检索分支超时，未配置或 ≤0 时默认 5 秒。 */
        public long resolvedBranchTimeoutMs() {
            return branchTimeoutMs <= 0 ? 5_000 : branchTimeoutMs;
        }

        /** 并行分支数，未配置或 ≤0 时默认 6，上限 32。 */
        public int resolvedParallelism() {
            return parallelism <= 0 ? 6 : Math.min(parallelism, 32);
        }

        /** 熔断失败阈值：负数禁用熔断，0 取默认 3。 */
        public int resolvedCircuitBreakerFailureThreshold() {
            return circuitBreakerFailureThreshold < 0 ? 0
                    : circuitBreakerFailureThreshold == 0 ? 3 : circuitBreakerFailureThreshold;
        }

        /** 熔断打开时长：负数禁用，0 取默认 30 秒。 */
        public long resolvedCircuitBreakerOpenMs() {
            return circuitBreakerOpenMs < 0 ? 0 : circuitBreakerOpenMs == 0 ? 30_000 : circuitBreakerOpenMs;
        }

        /** 结果缓存 TTL：负数禁用缓存，0 取默认 120 秒。 */
        public long resolvedResultCacheTtlSeconds() {
            return resultCacheTtlSeconds < 0 ? 0 : resultCacheTtlSeconds == 0 ? 120 : resultCacheTtlSeconds;
        }

        /** 结果缓存容量上限：负数禁用，0 取默认 1000 条。 */
        public int resolvedResultCacheMaxEntries() {
            return resultCacheMaxEntries < 0 ? 0 : resultCacheMaxEntries == 0 ? 1_000 : resultCacheMaxEntries;
        }

        /** 嵌入缓存 TTL：负数禁用，0 取默认 900 秒。 */
        public long resolvedEmbeddingCacheTtlSeconds() {
            return embeddingCacheTtlSeconds < 0 ? 0
                    : embeddingCacheTtlSeconds == 0 ? 900 : embeddingCacheTtlSeconds;
        }

        /** 嵌入缓存容量上限：负数禁用，0 取默认 10000 条。 */
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

        /** 代码候选池是否经过 BGE 语义重排。CPU 部署默认关闭（单次多候选重排可达数分钟，超出分支超时）；GPU 部署显式开启。 */
        public boolean resolvedCodeBgeRerankEnabled() {
            return codeBgeRerankEnabled != null && codeBgeRerankEnabled;
        }

        /** 代码 RRF 候选池 = limit * 倍率（下限 50）；调大提升召回但增加重排成本。 */
        public int resolvedCodeCandidateMultiplier() {
            return codeCandidateMultiplier == null || codeCandidateMultiplier <= 0 ? 3 : codeCandidateMultiplier;
        }

        /** 0.8.5 默认启用精确符号快速通道（SQLite 类名+方法名精确查找置顶）；显式 false 回退纯混合检索。 */
        public boolean resolvedCodeExactSymbolEnabled() {
            return codeExactSymbolEnabled == null || codeExactSymbolEnabled;
        }

        /** 0.8.5 默认启用类名限定召回（类文件范围过滤检索）；显式 false 关闭。 */
        public boolean resolvedCodeClassScopedEnabled() {
            return codeClassScopedEnabled == null || codeClassScopedEnabled;
        }

        /** 0.8.5 默认启用结构化重排增强信号（类名/限定名/文件路径匹配）；显式 false 保持旧重排规则。 */
        public boolean resolvedCodeStructuralRerankEnabled() {
            return codeStructuralRerankEnabled == null || codeStructuralRerankEnabled;
        }

        /** 检索关键参数的指纹字符串，配置变化时指纹随之改变，用于相关缓存失效判断。 */
        public String fingerprint() {
            return denseTopK + ":" + sparseTopK + ":" + hybridTopK + ":" + resolvedBgeTopK()
                    + ":" + resolvedLlmTopK() + ":" + llmRerankEnabled
                    + ":" + resolvedChildFirstRerankEnabled()
                    + ":" + resolvedEnrichedBgePassageEnabled()
                    + ":" + resolvedCodeQueryExpansionEnabled()
                    + ":" + resolvedCodeBgeRerankEnabled()
                    + ":" + resolvedCodeCandidateMultiplier()
                    + ":" + resolvedLlmRerankSkipGap()
                    + ":" + resolvedCodeExactSymbolEnabled()
                    + ":" + resolvedCodeClassScopedEnabled()
                    + ":" + resolvedCodeStructuralRerankEnabled();
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
        /** 包含路径子串列表，未配置时为默认空列表。 */
        public List<String> includes() {
            return includePathSubstrings == null ? List.of() : includePathSubstrings;
        }

        /** 排除路径子串列表，未配置时为默认空列表。 */
        public List<String> excludes() {
            return excludePathSubstrings == null ? List.of() : excludePathSubstrings;
        }

        /** 解析单文件大小上限（字节），未配置或 ≤0 时默认 1MB。 */
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
        /** 解析 ZIP 内目标文件夹前缀，未配置时回退为版本号。 */
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
