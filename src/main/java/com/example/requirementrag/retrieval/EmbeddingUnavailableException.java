package com.example.requirementrag.retrieval;

/** 嵌入模型不可用或拒绝处理输入时抛出的可诊断异常。 */
public class EmbeddingUnavailableException extends RuntimeException {

    public EmbeddingUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
