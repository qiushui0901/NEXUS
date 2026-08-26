package com.example.requirementrag.config;

import com.example.requirementrag.integration.gitlab.GitLabIntegrationProperties;
import com.example.requirementrag.knowledge.management.KnowledgeManagementProperties;
import com.example.requirementrag.knowledge.multisource.MultiSourceKnowledgeProperties;
import com.example.requirementrag.project.BusinessProjectCatalogProperties;
import com.example.requirementrag.requirement.graph.RequirementGraphFusionProperties;
import com.example.requirementrag.requirement.graph.RequirementGraphProperties;
import com.example.requirementrag.requirement.semantic.RequirementSemanticProperties;
import com.example.requirementrag.web.ProjectAuthInterceptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置：注册认证/项目授权拦截器，
 * 拦截 /api/**，放行监控状态、webhook 与 actuator 端点。
 */
@Configuration
@EnableConfigurationProperties({
        WikiProperties.class,
        VersioningProperties.class,
        AuthProperties.class,
        GitLabIntegrationProperties.class,
        KnowledgeManagementProperties.class,
        BusinessProjectCatalogProperties.class,
        RequirementGraphProperties.class,
        RequirementGraphFusionProperties.class,
        MultiSourceKnowledgeProperties.class,
        RequirementSemanticProperties.class,
        com.example.requirementrag.knowledge.multisource.vector.KnowledgeClaimVectorProperties.class
})
public class WebMvcConfig implements WebMvcConfigurer {

    private final ProjectAuthInterceptor projectAuthInterceptor;

    public WebMvcConfig(ProjectAuthInterceptor projectAuthInterceptor) {
        this.projectAuthInterceptor = projectAuthInterceptor;
    }

    /** 注册项目认证拦截器并声明需要放行的路径。 */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(projectAuthInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/monitor/status", "/api/webhooks/**", "/actuator/**");
    }
}
