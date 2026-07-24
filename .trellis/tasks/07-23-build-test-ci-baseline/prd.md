# 构建与测试基线

## Goal

让本地与 GitHub Actions 在一致的 Java 21 / Maven 环境下可靠执行完整测试，并消除 Mockito inline mock maker 的动态自附加问题。

## Requirements

- 使用 Maven Wrapper 固定 Maven 版本，README/CI 均以 `./mvnw` 为标准入口。
- Maven Enforcer 在构建早期拒绝非 Java 21 与过旧 Maven。
- Surefire 显式加载 Mockito agent，不依赖运行时 self-attach。
- GitHub Actions 在 JDK 21 下执行 `./mvnw -B verify`，并缓存 Maven 依赖。
- 不提交 `target/`、本地缓存、Qdrant 数据、快照或环境密钥。

## Acceptance Criteria

- [x] `./mvnw -version` 使用固定 Maven 版本。
- [x] Java 21 下现有测试全部通过，且不再出现 Mockito self-attach 初始化错误。
- [x] 非 Java 21 构建由 Enforcer 给出明确失败信息。
- [x] `.github/workflows/ci.yml` 对 push 与 pull_request 执行 verify。
