# 构建与测试基线实施计划

- [x] 修改 `pom.xml`：Enforcer、dependency properties、Surefire Mockito agent。
- [x] 生成并校验 Maven Wrapper。
- [x] 新增 `.github/workflows/ci.yml`。
- [x] 在 Java 21 下运行 `./mvnw -B verify`。
- [x] 确认 Git 状态未包含向量库、快照、密钥或构建产物。
