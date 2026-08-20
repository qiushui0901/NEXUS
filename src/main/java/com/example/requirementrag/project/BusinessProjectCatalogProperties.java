package com.example.requirementrag.project;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** 业务项目与仓库目录的持久化配置。 */
@ConfigurationProperties("app.rag.project-catalog")
public record BusinessProjectCatalogProperties(String databasePath) {

    @ConstructorBinding
    public BusinessProjectCatalogProperties {
        databasePath = databasePath == null || databasePath.isBlank()
                ? "data/business-project-catalog.db" : databasePath.trim();
    }
}
