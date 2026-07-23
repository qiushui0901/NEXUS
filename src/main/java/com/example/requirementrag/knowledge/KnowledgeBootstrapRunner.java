package com.example.requirementrag.knowledge;

import com.example.requirementrag.config.RagProperties;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 应用启动时按配置触发知识库异步引导。
 */
@Component
public class KnowledgeBootstrapRunner implements ApplicationRunner {

    private final RagProperties properties;
    private final KnowledgeBootstrapService bootstrapService;

    /** 注入配置与引导服务。 */
    public KnowledgeBootstrapRunner(RagProperties properties, KnowledgeBootstrapService bootstrapService) {
        this.properties = properties;
        this.bootstrapService = bootstrapService;
    }

    /** 若启用引导配置，则在启动后异步导入知识库。 */
    @Override
    public void run(ApplicationArguments args) {
        if (properties.knowledge().bootstrapEnabled()) {
            bootstrapService.bootstrapAsync();
        }
    }
}
