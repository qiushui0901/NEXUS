package com.example.requirementrag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
    public record Bge(String baseUrl, String path, String apiKey) {
    }

    /** LLM 生成、重排与路由模型名称配置。 */
    public record Llm(String generationModel, String rerankerModel, String routingModel) {
        public String resolvedRoutingModel() {
            return (routingModel != null && !routingModel.isBlank()) ? routingModel : rerankerModel;
        }
    }

    /** 检索各阶段 topK 参数配置。 */
    public record Retrieval(int denseTopK, int sparseTopK, int hybridTopK, int bgeTopK, int llmTopK) {
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
