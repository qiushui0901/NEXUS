package com.example.requirementrag.knowledge.management;

import com.example.requirementrag.knowledge.management.KnowledgeManagementModels.SafeError;
import org.springframework.stereotype.Component;

import java.util.Locale;

/** 把内部异常转换为稳定、可公开且不包含凭据或下游地址的错误。 */
@Component
public class KnowledgeErrorSanitizer {
    public SafeError sanitize(Throwable error) {
        if (error == null) return null;
        String name = error.getClass().getSimpleName().toUpperCase(Locale.ROOT);
        String code = name.contains("EMBED") ? "EMBEDDING_UNAVAILABLE"
                : name.contains("TIMEOUT") ? "DEPENDENCY_TIMEOUT"
                : error instanceof java.io.IOException ? "SOURCE_READ_FAILED"
                : error instanceof IllegalArgumentException ? "INVALID_SOURCE"
                : "KNOWLEDGE_PROCESSING_FAILED";
        String message = switch (code) {
            case "EMBEDDING_UNAVAILABLE" -> "向量服务暂时不可用，请稍后重试";
            case "DEPENDENCY_TIMEOUT" -> "依赖服务响应超时，请稍后重试";
            case "SOURCE_READ_FAILED" -> "无法读取知识来源";
            case "INVALID_SOURCE" -> "知识来源无有效内容或格式不受支持";
            default -> "知识处理失败，请使用关联 ID 查询服务端日志";
        };
        return new SafeError(code, message);
    }
}
