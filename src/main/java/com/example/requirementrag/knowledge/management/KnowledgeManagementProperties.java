package com.example.requirementrag.knowledge.management;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** 知识管理工作台配置。状态目录是旁路能力，关闭时不改变原有导入和检索行为。 */
@ConfigurationProperties("app.knowledge-management")
public record KnowledgeManagementProperties(boolean enabled, String databasePath) {
    @ConstructorBinding
    public KnowledgeManagementProperties {
        databasePath = databasePath == null || databasePath.isBlank()
                ? "data/knowledge-management.db" : databasePath.trim();
    }
}
