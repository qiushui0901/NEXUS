package com.example.requirementrag.service;

import org.springframework.ai.openai.OpenAiChatOptions;

/** 为生成模型构建兼容选项，避免向不支持 temperature 的 Claude 模型发送该参数。 */
public final class GenerationChatOptions {

    private GenerationChatOptions() {
    }

    /** 构建仅携带模型名的基础选项，避免向不支持 temperature 的模型发送该参数。 */
    public static OpenAiChatOptions.Builder forModel(String model) {
        return OpenAiChatOptions.builder()
                .model(model);
    }
}
