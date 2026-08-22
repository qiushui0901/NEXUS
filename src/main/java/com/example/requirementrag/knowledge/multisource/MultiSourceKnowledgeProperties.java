package com.example.requirementrag.knowledge.multisource;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.util.Map;

/**
 * 多源知识检索配置：全局总开关、按项目灰度开关、LLM 意图回退。
 *
 * <p>灰度原则：默认全局关闭，只有显式配置 <code>app.rag.multi-source.enabled=true</code>
 * 或对应项目在 <code>project-enabled</code> 中开启后才进入多源检索；
 * 关闭时保留已导入数据，仅返回 {@code MULTI_SOURCE_DISABLED} 降级响应。
 */
@ConfigurationProperties("app.rag.multi-source")
public record MultiSourceKnowledgeProperties(
        boolean enabled,
        boolean llmFallbackEnabled,
        String intentModel,
        Map<String, Boolean> projectEnabled
) {
    @ConstructorBinding
    public MultiSourceKnowledgeProperties {
        projectEnabled = projectEnabled == null ? Map.of() : Map.copyOf(projectEnabled);
        intentModel = intentModel == null || intentModel.isBlank() ? null : intentModel.trim();
    }

    /** 默认配置：全局关闭，关闭 LLM 回退，无项目白名单。 */
    public static MultiSourceKnowledgeProperties disabledDefault() {
        return new MultiSourceKnowledgeProperties(false, false, null, Map.of());
    }

    /** 默认配置：全局开启，关闭 LLM 回退（测试/离线评估使用）。 */
    public static MultiSourceKnowledgeProperties enabledDefault() {
        return new MultiSourceKnowledgeProperties(true, false, null, Map.of());
    }

    /** 该项目是否启用多源检索：全局关闭则一律关闭；未在 project-enabled 配置时按全局开关。 */
    public boolean enabledFor(String projectId) {
        if (!enabled) {
            return false;
        }
        if (projectId == null || projectId.isBlank()) {
            return true;
        }
        return projectEnabled.getOrDefault(projectId, true);
    }

    /** 是否启用 LLM 意图回退。 */
    public boolean llmFallbackEnabled() {
        return llmFallbackEnabled;
    }

    /** 解析 LLM 意图回退模型名：未配置时使用调用方提供的回退模型。 */
    public String intentModel(String fallback) {
        return intentModel != null ? intentModel : fallback;
    }
}