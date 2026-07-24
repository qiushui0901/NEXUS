# 构建与测试基线设计

## Boundary

只修改构建入口、测试 JVM 启动参数与 CI；不改变业务运行时依赖或生产 JVM 参数。

## Design

1. Maven Wrapper 固定 Maven 3.9.x，wrapper JAR/配置随代码提交。
2. `maven-enforcer-plugin` 在 `validate` 阶段要求 Java `[21,22)` 与 Maven `[3.9,)`。
3. `maven-dependency-plugin:properties` 暴露测试依赖 JAR 路径；Surefire 通过 `-javaagent:${org.mockito:mockito-core:jar}` 提前加载 Mockito agent。
4. GitHub Actions 使用 Temurin 21、Maven 缓存和 `./mvnw -B verify`。

## Compatibility / Rollback

- 生产启动命令不增加 agent。
- Maven Wrapper 不替换现有系统 Maven，只提供项目内稳定入口。
- 回滚时移除新增插件、wrapper 与 workflow 即可。
