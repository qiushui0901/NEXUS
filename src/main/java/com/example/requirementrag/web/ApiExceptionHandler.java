package com.example.requirementrag.web;

import com.example.requirementrag.retrieval.EmbeddingUnavailableException;
import com.example.requirementrag.service.DocumentNotFoundException;
import com.example.requirementrag.service.RagUnavailableException;
import com.example.requirementrag.model.RagOutcomeStatus;
import com.example.requirementrag.requirement.graph.RequirementGraphException;
import com.example.requirementrag.requirement.semantic.RequirementSemanticException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * 全局 API 异常处理器，将业务异常映射为 ProblemDetail 响应。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** 文档未找到时返回 404。 */
    @ExceptionHandler(DocumentNotFoundException.class)
    ProblemDetail handleNotFound(DocumentNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    /** 参数校验失败时返回 400。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "请求参数不完整");
    }

    /** 需求语义图错误返回稳定 code，不暴露 provider 异常原文。 */
    @ExceptionHandler(RequirementGraphException.class)
    ProblemDetail handleRequirementGraph(RequirementGraphException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        detail.setProperty("code", exception.code());
        return detail;
    }

    /** 需求语义构建/校验错误返回稳定 code（SEMANTIC_*），不暴露 provider 异常原文。 */
    @ExceptionHandler(RequirementSemanticException.class)
    ProblemDetail handleRequirementSemantic(RequirementSemanticException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        detail.setProperty("code", exception.code());
        return detail;
    }

    /** 非法业务参数返回 400。 */
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    /** 授权失败时返回 403。 */
    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleAccessDenied(AccessDeniedException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, exception.getMessage());
    }

    /** 保留 ResponseStatusException 的状态码与消息。 */
    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail handleResponseStatus(ResponseStatusException exception) {
        return ProblemDetail.forStatusAndDetail(exception.getStatusCode(), exception.getReason());
    }

    /** 本地嵌入模型不可用或拒绝输入时返回可诊断的 503。 */
    @ExceptionHandler(EmbeddingUnavailableException.class)
    ProblemDetail handleEmbeddingUnavailable(EmbeddingUnavailableException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage());
    }

    /** 核心 RAG 依赖不可用且没有可用证据时返回 503。 */
    @ExceptionHandler(RagUnavailableException.class)
    ProblemDetail handleRagUnavailable(RagUnavailableException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                exception.getMessage());
        detail.setProperty("outcome", RagOutcomeStatus.FAILED);
        detail.setProperty("warnings", exception.warnings());
        return detail;
    }
}
