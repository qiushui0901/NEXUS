package com.example.requirementrag.config;

import com.example.requirementrag.web.ProjectAuthInterceptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties({AuthProperties.class, WikiProperties.class, VersioningProperties.class})
public class WebMvcConfig implements WebMvcConfigurer {

    private final ProjectAuthInterceptor projectAuthInterceptor;

    public WebMvcConfig(ProjectAuthInterceptor projectAuthInterceptor) {
        this.projectAuthInterceptor = projectAuthInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(projectAuthInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/monitor/status", "/api/webhooks/**", "/actuator/**");
    }
}
