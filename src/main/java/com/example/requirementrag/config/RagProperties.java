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
                             List<ProjectConfig> projects, Evolution evolution,
                             Vision vision) {

    @ConstructorBinding
    public RagProperties {
        projects = projects == null ? List.of() : projects;
        evolution = evolution == null ? Evolution.disabled() : evolution;
        vision = vision == null ? Vision.disabled() : vision;
    }

    /** 兼容构造器：未配置 Vision。 */
    public RagProperties(Qdrant qdrant, Bge bge, Llm llm, Retrieval retrieval,
                         Knowledge knowledge, Review review, Code code,
                         List<ProjectConfig> projects, Evolution evolution) {
        this(qdrant, bge, llm, retrieval, knowledge, review, code, projects, evolution, Vision.disabled());
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

    /** 文档检索配置（自然语言/HTML 图文）。 */
    public record Document(
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
            Double llmRerankSkipGap,
            Boolean pageIndexEnabled
    ) {
        @ConstructorBinding
        public Document {
        }

        public int resolvedBgeTopK() {
            return bgeTopK <= 0 ? 20 : bgeTopK;
        }

        public double resolvedLlmRerankSkipGap() {
            return llmRerankSkipGap == null ? 0 : llmRerankSkipGap;
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

        public boolean resolvedChildFirstRerankEnabled() {
            return childFirstRerankEnabled == null || childFirstRerankEnabled;
        }

        public boolean resolvedEnrichedBgePassageEnabled() {
            return enrichedBgePassageEnabled == null || enrichedBgePassageEnabled;
        }

        public boolean resolvedPageIndexEnabled() {
            return pageIndexEnabled != null && pageIndexEnabled;
        }

        public String fingerprint() {
            return denseTopK + ":" + sparseTopK + ":" + hybridTopK + ":" + resolvedBgeTopK()
                    + ":" + resolvedLlmTopK() + ":" + llmRerankEnabled
                    + ":" + resolvedChildFirstRerankEnabled()
                    + ":" + resolvedEnrichedBgePassageEnabled()
                    + ":" + resolvedLlmRerankSkipGap()
                    + ":" + resolvedPageIndexEnabled()
                    + ":" + resolvedBranchTimeoutMs() + ":" + resolvedParallelism()
                    + ":" + resolvedCircuitBreakerFailureThreshold() + ":" + resolvedCircuitBreakerOpenMs()
                    + ":" + resolvedResultCacheTtlSeconds() + ":" + resolvedResultCacheMaxEntries();
        }
    }

    /** 代码检索配置（结构化符号/多仓库）。 */
    public record CodeRetrieval(
            long branchTimeoutMs,
            int parallelism,
            int circuitBreakerFailureThreshold,
            long circuitBreakerOpenMs,
            Boolean queryExpansionEnabled,
            Boolean bgeRerankEnabled,
            Integer candidateMultiplier,
            Boolean exactSymbolEnabled,
            Boolean classScopedEnabled,
            Boolean structuralRerankEnabled
    ) {
        @ConstructorBinding
        public CodeRetrieval {
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

        public boolean resolvedQueryExpansionEnabled() {
            return queryExpansionEnabled == null || queryExpansionEnabled;
        }

        public boolean resolvedBgeRerankEnabled() {
            return bgeRerankEnabled != null && bgeRerankEnabled;
        }

        public int resolvedCandidateMultiplier() {
            return candidateMultiplier == null || candidateMultiplier <= 0 ? 3 : candidateMultiplier;
        }

        public boolean resolvedExactSymbolEnabled() {
            return exactSymbolEnabled == null || exactSymbolEnabled;
        }

        public boolean resolvedClassScopedEnabled() {
            return classScopedEnabled == null || classScopedEnabled;
        }

        public boolean resolvedStructuralRerankEnabled() {
            return structuralRerankEnabled == null || structuralRerankEnabled;
        }

        public String fingerprint() {
            return resolvedBranchTimeoutMs() + ":" + resolvedParallelism()
                    + ":" + resolvedCircuitBreakerFailureThreshold() + ":" + resolvedCircuitBreakerOpenMs()
                    + ":" + resolvedQueryExpansionEnabled()
                    + ":" + resolvedBgeRerankEnabled() + ":" + resolvedCandidateMultiplier()
                    + ":" + resolvedExactSymbolEnabled() + ":" + resolvedClassScopedEnabled()
                    + ":" + resolvedStructuralRerankEnabled();
        }
    }

    /** 检索各阶段 topK、并行、重排与缓存参数配置。 */
    public record Retrieval(
            Document document,
            CodeRetrieval code,
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
            if (document == null && code == null
                    && denseTopK == 0 && sparseTopK == 0 && hybridTopK == 0 && bgeTopK == 0 && llmTopK == 0
                    && branchTimeoutMs == 0 && parallelism == 0) {
                // allow Spring to bind nested document/code only
            } else if (document == null) {
                document = new Document(denseTopK, sparseTopK, hybridTopK, bgeTopK, llmTopK, llmRerankEnabled,
                        branchTimeoutMs, parallelism, circuitBreakerFailureThreshold, circuitBreakerOpenMs,
                        resultCacheTtlSeconds, resultCacheMaxEntries, embeddingCacheTtlSeconds, embeddingCacheMaxEntries,
                        childFirstRerankEnabled, enrichedBgePassageEnabled, llmRerankSkipGap, false);
            }
            if (code == null) {
                code = new CodeRetrieval(branchTimeoutMs, parallelism, circuitBreakerFailureThreshold, circuitBreakerOpenMs,
                        codeQueryExpansionEnabled, codeBgeRerankEnabled, codeCandidateMultiplier,
                        codeExactSymbolEnabled, codeClassScopedEnabled, codeStructuralRerankEnabled);
            }
            // merge legacy flat values into nested when nested is default and legacy is explicit
            if (document != null) {
                boolean legacyDocSet = denseTopK != 0 || sparseTopK != 0 || hybridTopK != 0 || bgeTopK != 0 || llmTopK != 0
                        || branchTimeoutMs != 0 || parallelism != 0 || circuitBreakerFailureThreshold != 0
                        || circuitBreakerOpenMs != 0 || resultCacheTtlSeconds != 0 || resultCacheMaxEntries != 0;
                if (legacyDocSet && document.denseTopK() == 0 && document.sparseTopK() == 0 && document.hybridTopK() == 0) {
                    document = new Document(
                            denseTopK != 0 ? denseTopK : document.denseTopK(),
                            sparseTopK != 0 ? sparseTopK : document.sparseTopK(),
                            hybridTopK != 0 ? hybridTopK : document.hybridTopK(),
                            bgeTopK != 0 ? bgeTopK : document.bgeTopK(),
                            llmTopK != 0 ? llmTopK : document.llmTopK(),
                            llmRerankEnabled || document.llmRerankEnabled(),
                            branchTimeoutMs != 0 ? branchTimeoutMs : document.branchTimeoutMs(),
                            parallelism != 0 ? parallelism : document.parallelism(),
                            circuitBreakerFailureThreshold != 0 ? circuitBreakerFailureThreshold : document.circuitBreakerFailureThreshold(),
                            circuitBreakerOpenMs != 0 ? circuitBreakerOpenMs : document.circuitBreakerOpenMs(),
                            resultCacheTtlSeconds != 0 ? resultCacheTtlSeconds : document.resultCacheTtlSeconds(),
                            resultCacheMaxEntries != 0 ? resultCacheMaxEntries : document.resultCacheMaxEntries(),
                            embeddingCacheTtlSeconds != 0 ? embeddingCacheTtlSeconds : document.embeddingCacheTtlSeconds(),
                            embeddingCacheMaxEntries != 0 ? embeddingCacheMaxEntries : document.embeddingCacheMaxEntries(),
                            childFirstRerankEnabled != null ? childFirstRerankEnabled : document.childFirstRerankEnabled(),
                            enrichedBgePassageEnabled != null ? enrichedBgePassageEnabled : document.enrichedBgePassageEnabled(),
                            llmRerankSkipGap != null ? llmRerankSkipGap : document.llmRerankSkipGap(),
                            document.resolvedPageIndexEnabled());
                }
            }
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
            this(null, null, denseTopK, sparseTopK, hybridTopK, bgeTopK, llmTopK, llmRerankEnabled, branchTimeoutMs,
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
            this(null, null, denseTopK, sparseTopK, hybridTopK, bgeTopK, llmTopK, llmRerankEnabled, branchTimeoutMs,
                    parallelism, circuitBreakerFailureThreshold, circuitBreakerOpenMs,
                    resultCacheTtlSeconds, resultCacheMaxEntries, embeddingCacheTtlSeconds,
                    embeddingCacheMaxEntries, childFirstRerankEnabled, enrichedBgePassageEnabled,
                    codeQueryExpansionEnabled, codeBgeRerankEnabled, codeCandidateMultiplier,
                    llmRerankSkipGap, null, null, null);
        }

        /** BGE 重排候选数，未配置或 ≤0 时默认 20。@deprecated 代理到 document */
        public int resolvedBgeTopK() {
            return document != null ? document.resolvedBgeTopK() : (bgeTopK <= 0 ? 20 : bgeTopK);
        }

        /** LLM 重排跳过阈值：BGE top1 与后续候选分差达到该值时跳过 LLM 重排；≤0 表示不启用。@deprecated 代理到 document */
        public double resolvedLlmRerankSkipGap() {
            return document != null ? document.resolvedLlmRerankSkipGap() : (llmRerankSkipGap == null ? 0 : llmRerankSkipGap);
        }

        /** LLM 重排候选数，未配置或 ≤0 时默认 10。@deprecated 代理到 document */
        public int resolvedLlmTopK() {
            return document != null ? document.resolvedLlmTopK() : (llmTopK <= 0 ? 10 : llmTopK);
        }

        /** 检索分支超时，未配置或 ≤0 时默认 5 秒。@deprecated 代理到 document/code 合并 */
        public long resolvedBranchTimeoutMs() {
            if (document != null) return document.resolvedBranchTimeoutMs();
            return branchTimeoutMs <= 0 ? 5_000 : branchTimeoutMs;
        }

        /** 并行分支数，未配置或 ≤0 时默认 6，上限 32。@deprecated 代理到 document */
        public int resolvedParallelism() {
            if (document != null) return document.resolvedParallelism();
            return parallelism <= 0 ? 6 : Math.min(parallelism, 32);
        }

        /** 熔断失败阈值：负数禁用熔断，0 取默认 3。@deprecated 代理到 document */
        public int resolvedCircuitBreakerFailureThreshold() {
            if (document != null) return document.resolvedCircuitBreakerFailureThreshold();
            return circuitBreakerFailureThreshold < 0 ? 0
                    : circuitBreakerFailureThreshold == 0 ? 3 : circuitBreakerFailureThreshold;
        }

        /** 熔断打开时长：负数禁用，0 取默认 30 秒。@deprecated 代理到 document */
        public long resolvedCircuitBreakerOpenMs() {
            if (document != null) return document.resolvedCircuitBreakerOpenMs();
            return circuitBreakerOpenMs < 0 ? 0 : circuitBreakerOpenMs == 0 ? 30_000 : circuitBreakerOpenMs;
        }

        /** 结果缓存 TTL：负数禁用缓存，0 取默认 120 秒。@deprecated 代理到 document */
        public long resolvedResultCacheTtlSeconds() {
            if (document != null) return document.resolvedResultCacheTtlSeconds();
            return resultCacheTtlSeconds < 0 ? 0 : resultCacheTtlSeconds == 0 ? 120 : resultCacheTtlSeconds;
        }

        /** 结果缓存容量上限：负数禁用，0 取默认 1000 条。@deprecated 代理到 document */
        public int resolvedResultCacheMaxEntries() {
            if (document != null) return document.resolvedResultCacheMaxEntries();
            return resultCacheMaxEntries < 0 ? 0 : resultCacheMaxEntries == 0 ? 1_000 : resultCacheMaxEntries;
        }

        /** 嵌入缓存 TTL：负数禁用，0 取默认 900 秒。@deprecated 代理到 document */
        public long resolvedEmbeddingCacheTtlSeconds() {
            if (document != null) return document.resolvedEmbeddingCacheTtlSeconds();
            return embeddingCacheTtlSeconds < 0 ? 0
                    : embeddingCacheTtlSeconds == 0 ? 900 : embeddingCacheTtlSeconds;
        }

        /** 嵌入缓存容量上限：负数禁用，0 取默认 10000 条。@deprecated 代理到 document */
        public int resolvedEmbeddingCacheMaxEntries() {
            if (document != null) return document.resolvedEmbeddingCacheMaxEntries();
            return embeddingCacheMaxEntries < 0 ? 0
                    : embeddingCacheMaxEntries == 0 ? 10_000 : embeddingCacheMaxEntries;
        }

        /** 0.8.1 默认启用 child-first 重排；显式 false 可回退到 0.8 parent-first 行为。@deprecated 代理到 document */
        public boolean resolvedChildFirstRerankEnabled() {
            if (document != null) return document.resolvedChildFirstRerankEnabled();
            return childFirstRerankEnabled == null || childFirstRerankEnabled;
        }

        /** 0.8.1 默认向 BGE 发送有界 child + parent 上下文；显式 false 仅发送 child。@deprecated 代理到 document */
        public boolean resolvedEnrichedBgePassageEnabled() {
            if (document != null) return document.resolvedEnrichedBgePassageEnabled();
            return enrichedBgePassageEnabled == null || enrichedBgePassageEnabled;
        }

        /** 0.8.1 默认启用中英文代码意图归一化；显式 false 保持 0.8 排序。@deprecated 代理到 code */
        public boolean resolvedCodeQueryExpansionEnabled() {
            if (code != null) return code.resolvedQueryExpansionEnabled();
            return codeQueryExpansionEnabled == null || codeQueryExpansionEnabled;
        }

        /** 代码候选池是否经过 BGE 语义重排。@deprecated 代理到 code */
        public boolean resolvedCodeBgeRerankEnabled() {
            if (code != null) return code.resolvedBgeRerankEnabled();
            return codeBgeRerankEnabled != null && codeBgeRerankEnabled;
        }

        /** 代码 RRF 候选池 = limit * 倍率（下限 50）；调大提升召回但增加重排成本。@deprecated 代理到 code */
        public int resolvedCodeCandidateMultiplier() {
            if (code != null) return code.resolvedCandidateMultiplier();
            return codeCandidateMultiplier == null || codeCandidateMultiplier <= 0 ? 3 : codeCandidateMultiplier;
        }

        /** 0.8.5 默认启用精确符号快速通道；@deprecated 代理到 code */
        public boolean resolvedCodeExactSymbolEnabled() {
            if (code != null) return code.resolvedExactSymbolEnabled();
            return codeExactSymbolEnabled == null || codeExactSymbolEnabled;
        }

        /** 0.8.5 默认启用类名限定召回；@deprecated 代理到 code */
        public boolean resolvedCodeClassScopedEnabled() {
            if (code != null) return code.resolvedClassScopedEnabled();
            return codeClassScopedEnabled == null || codeClassScopedEnabled;
        }

        /** 0.8.5 默认启用结构化重排增强信号；@deprecated 代理到 code */
        public boolean resolvedCodeStructuralRerankEnabled() {
            if (code != null) return code.resolvedStructuralRerankEnabled();
            return codeStructuralRerankEnabled == null || codeStructuralRerankEnabled;
        }

        /** 检索关键参数的指纹字符串，配置变化时指纹随之改变，用于相关缓存失效判断。 */
        public String fingerprint() {
            if (document != null && code != null) {
                return document.fingerprint() + "|" + code.fingerprint();
            }
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

    /** 自进化 RAG 配置。默认全部关闭，关闭时保持现有检索行为。 */
    public record Evolution(
            boolean enabled,
            boolean experienceRecordingEnabled,
            double successSampleRate,
            double failureSampleRate,
            int queueCapacity,
            boolean queryPreviewEnabled,
            int retentionDays,
            String experienceRootPath,
            String candidateRootPath,
            String datasetRootPath,
            String policyRootPath
    ) {
        @ConstructorBinding
        public Evolution {
            if (successSampleRate < 0 || successSampleRate > 1) {
                throw new IllegalArgumentException("successSampleRate must be between 0 and 1");
            }
            if (failureSampleRate < 0 || failureSampleRate > 1) {
                throw new IllegalArgumentException("failureSampleRate must be between 0 and 1");
            }
            queueCapacity = queueCapacity <= 0 ? 1_000 : queueCapacity;
            retentionDays = retentionDays <= 0 ? 30 : retentionDays;
            experienceRootPath = text(experienceRootPath, "data/evolution/experiences");
            candidateRootPath = text(candidateRootPath, "data/evolution/candidates");
            datasetRootPath = text(datasetRootPath, "data/evolution/datasets");
            policyRootPath = text(policyRootPath, "data/evolution/policies");
        }

        /** 默认关闭配置。 */
        public static Evolution disabled() {
            return new Evolution(false, false, 0.1, 1.0, 1_000, false, 30,
                    "data/evolution/experiences", "data/evolution/candidates",
                    "data/evolution/datasets", "data/evolution/policies");
        }

        private static String text(String value, String fallback) {
            return value == null || value.isBlank() ? fallback : value.trim();
        }
    }

    /** 视觉理解配置（文档图片 caption）。默认关闭，打开后对 ZIP 内图片做批量 caption。 */
    public record Vision(
            Boolean enabled,
            String model,
            Integer maxImagesPerDoc,
            Long timeoutMs,
            Integer concurrency
    ) {
        @ConstructorBinding
        public Vision {
        }

        public static Vision disabled() {
            return new Vision(false, "glm-5.2", 20, 8000L, 3);
        }

        public boolean resolvedEnabled() {
            return enabled != null && enabled;
        }

        public String resolvedModel(String fallback) {
            if (model != null && !model.isBlank()) return model.trim();
            if (fallback != null && !fallback.isBlank()) return fallback.trim();
            return "glm-5.2";
        }

        public int resolvedMaxImagesPerDoc() {
            return maxImagesPerDoc == null || maxImagesPerDoc <= 0 ? 20 : Math.min(maxImagesPerDoc, 50);
        }

        public long resolvedTimeoutMs() {
            return timeoutMs == null || timeoutMs <= 0 ? 8000L : Math.min(timeoutMs, 30000L);
        }

        public int resolvedConcurrency() {
            return concurrency == null || concurrency <= 0 ? 3 : Math.min(concurrency, 8);
        }
    }
}
