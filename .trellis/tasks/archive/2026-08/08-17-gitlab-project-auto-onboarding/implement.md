# GitLab 项目自动接入执行计划

## Implementation

- [x] 增加配置属性、状态/请求/响应模型和 AES-GCM 凭据组件。
- [x] 增加 SQLite 项目与 webhook 事件存储，覆盖初始化、CRUD、状态和去重。
- [x] 将 `ProjectRegistry` 改为线程安全动态注册，同时保留静态项目优先级。
- [x] 实现受控 Git 客户端：clone、fetch、分支/commit 验证、detached checkout。
- [x] 实现项目级后台同步服务和首次全量/后续增量索引策略。
- [x] 实现 ADMIN 管理 API和项目级 GitLab Push Hook。
- [x] 增加配置示例、简体中文使用文档和 `CHANGELOG.md`。

## Verification

- [x] 单元测试：加密、URL/路径/SHA 校验、Git 命令敏感信息边界。
- [x] 存储测试：CRUD、重启加载、事件去重、禁用和失败状态。
- [x] 服务测试：注册成功、重复注册、快进增量、非快进、失败重试。
- [x] Web 测试：ADMIN 权限、错误 token、错误分支、重复 webhook。
- [x] `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -B test`
- [x] `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -B verify`
- [x] `git diff --check`

## Risk And Rollback Points

- `ProjectRegistry` 是高扇出共享组件，必须先补动态注册并运行现有多项目测试。
- Git 子进程不得将 PAT 拼入 URL 或参数；测试检查持久化 remote URL。
- 增量索引要求 old/new commit 均已 fetch；无法证明快进时失败而不是覆盖 live 索引。
- 关闭开关必须完全回退到静态项目路径。
