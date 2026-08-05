package com.example.requirementrag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Wiki 配置，绑定 app.rag.wiki 前缀：版本化 Wiki 源文件、草稿与生成产物的
 * 路径，以及渲染结果缓存参数。
 */
@ConfigurationProperties("app.rag.wiki")
public record WikiProperties(String rootPath, String sourcePath, String draftPath,
                             long cacheTtlSeconds, int cacheMaxEntries) {

    /** 路径缺失时回退默认目录；缓存参数 0 取默认值（300 秒 / 2000 条）。 */
    @ConstructorBinding
    public WikiProperties {
        rootPath = hasText(rootPath) ? rootPath.trim() : "data/wiki";
        sourcePath = hasText(sourcePath) ? sourcePath.trim() : "data/wiki-sources";
        draftPath = hasText(draftPath) ? draftPath.trim() : "data/wiki-drafts";
        cacheTtlSeconds = cacheTtlSeconds < 0 ? 0 : cacheTtlSeconds == 0 ? 300 : cacheTtlSeconds;
        cacheMaxEntries = cacheMaxEntries < 0 ? 0 : cacheMaxEntries == 0 ? 2_000 : cacheMaxEntries;
    }

    /** 兼容构造器：保留既有内嵌/测试场景的构造方式，缓存参数取默认值。 */
    public WikiProperties(String rootPath, String sourcePath) {
        this(rootPath, sourcePath, null, 300, 2_000);
    }

    /** 兼容构造器：保留 0.8 之前草稿路径的构造方式，缓存参数取默认值。 */
    public WikiProperties(String rootPath, String sourcePath, String draftPath) {
        this(rootPath, sourcePath, draftPath, 300, 2_000);
    }

    /** 判断字符串是否为 null 或空白。 */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
