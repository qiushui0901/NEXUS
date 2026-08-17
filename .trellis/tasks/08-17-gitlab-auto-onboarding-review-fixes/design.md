# GitLab 自动接入 Review 修复设计

## 1. 边界与数据流

```text
管理 API Clone URL
  -> GitLabSyncService.validate
  -> GitLabGitClient URL/Host 策略
  -> 加密并保存 PAT
  -> 队列
  -> GIT_ASKPASS

同步请求
  -> 清空或设置 targetSha
  -> clone/fetch/解析目标
  -> 持久化目标
  -> checkout/index
  -> READY 或保留目标的 FAILED

应用启动
  -> 读取 SQLite 项目
  -> 恢复动态注册表
  -> 识别中断状态
  -> 使用持久化 targetSha 重新入队
```

## 2. 可信 Host 策略

- 在 `GitLabIntegrationProperties` 增加 `allowedHosts` 和 `allowPrivateHosts`。
- `allowedHosts` 规范化为小写、去重的不可变集合，默认 `gitlab.com`。
- `GitLabGitClient` 统一拥有 Clone URL 校验，`GitLabSyncService` 不复制 Host 规则。
- 校验顺序：URI 结构 -> 精确白名单 -> IP 字面量/解析地址策略。
- 默认拒绝 IP 字面量和非公网解析结果；`allowPrivateHosts=true` 只放宽地址类型限制，不绕过 Host 白名单。
- 地址解析抽象为包内可注入函数，单元测试不依赖外部 DNS。

## 3. `targetSha` 状态语义

- Store 的内部 SQL 使用显式模式区分：
  - `REPLACE`：`target_sha=?`，传入 `null` 即清空。
  - `KEEP`：`target_sha=target_sha`。
- 保留现有 `lastIndexedSha` 的 `COALESCE` 语义，因为其 `null` 一直代表“不更新”，且索引成功前不得清空。
- 最新 HEAD 同步开始时使用 `REPLACE(null)`；解析目标后使用 `REPLACE(target)`；失败和禁用使用 `KEEP`。
- retry 读取持久化值：非空时固定目标，空时沿最新 HEAD 流程重新解析。

## 4. 启动恢复

- `restoreRegistry` 在成功注册动态项目后判断状态。
- `PENDING/CLONING/SYNCING/INDEXING` 以 `project.targetSha()` 重新入队。
- `READY/FAILED/DISABLED` 不自动调度。
- 注册冲突先写入 `FAILED`，不进入恢复队列。
- 继续复用现有每项目稳定 FIFO 队列，不新增第二套执行器。

## 5. 兼容与回滚

- 默认 `allowedHosts=gitlab.com` 会拒绝未配置的自建 GitLab；接入指南明确要求升级时配置公司 Host。
- 私网 GitLab 需要显式开启 `allow-private-hosts`，作为有意的管理员信任决策。
- SQLite 不新增列，无数据库迁移。
- 回滚只需恢复 Java、配置和文档改动；已有数据库内容保持兼容。

## 6. 测试策略

- Git 客户端：精确白名单、子域绕过、IP、私网解析、显式私网放行。
- Store：`REPLACE(null)` 可清空；`KEEP` 可保留。
- Sync：旧目标早期失败后 retry 重新解析；已记录目标仍固定重试。
- Recovery：四种中断状态重新入队，三种稳定状态不入队。
- 回归：已有 FIFO、禁用保护、非快进拒绝和 Spring 上下文测试继续通过。
