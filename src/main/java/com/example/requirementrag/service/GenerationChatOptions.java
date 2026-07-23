package com.example.requirementrag.service;

import org.springframework.ai.openai.OpenAiChatOptions;

/** 为生成模型构建兼容选项，避免向不支持 temperature 的 Claude 模型发送该参数。 */
final class GenerationChatOptions {

    private GenerationChatOptions() {
    }

    static OpenAiChatOptions.Builder forModel(String model) {
        return OpenAiChatOptions.builder()
                .model(model);
    }
}
