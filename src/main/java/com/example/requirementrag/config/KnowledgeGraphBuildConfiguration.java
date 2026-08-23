package com.example.requirementrag.config;

import com.example.requirementrag.knowledge.multisource.KnowledgeGraphBuildService;
import com.example.requirementrag.knowledge.multisource.LlmKnowledgeGraphExtractor;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeStore;
import com.example.requirementrag.knowledge.multisource.SymbolGraphCodeEntitySource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 装配跨源总实体关系图构建服务：代码实体源 + 可选 LLM 语义边。 */
@Configuration
public class KnowledgeGraphBuildConfiguration {

    @Bean
    public KnowledgeGraphBuildService knowledgeGraphBuildService(
            MultiSourceKnowledgeStore store,
            SymbolGraphCodeEntitySource codeEntitySource,
            LlmKnowledgeGraphExtractor llmGraphExtractor,
            @Value("${app.rag.multi-source.graph-llm-enabled:false}") boolean graphLlmEnabled) {
        return new KnowledgeGraphBuildService(store)
                .withCodeEntitySource(codeEntitySource)
                .withLlmGraphExtractor(llmGraphExtractor)
                .withLlmEnabled(graphLlmEnabled);
    }
}