# GitLab 自动接入 Review 修复

## Goal

修复 GitLab 自动接入首轮 Review 发现的三个状态与安全缺陷，确保 PAT 只会发送给管理员明确授权的 GitLab 主机，同步重试不会误用旧目标提交，应用重启后中断任务能够自动恢复。

## Background

- P1：`GitLabGitClient.validateCloneUrl` 仅校验 HTTPS，未将 Clone URL 绑定到可信 GitLab 实例，存在 PAT 泄露和 SSRF 风险（原位置 `GitLabGitClient.java:145-153`）。
- P1：`GitLabProjectStore.updateState` 使用 `target_sha=COALESCE(?, target_sha)`，无法显式清除旧目标；`READY(A) -> 最新 HEAD 同步 B 提前失败 -> retry` 可能错误重试 A（原位置 `GitLabProjectStore.java:110-115`）。
- P2：`GitLabSyncService.restoreRegistry` 只恢复动态注册表，没有恢复 `PENDING/CLONING/SYNCING/INDEXING` 项目（原位置 `GitLabSyncService.java:292-305`）。
- 当前项目用于公司内部部署，需要支持自建 GitLab；私网 GitLab 必须由管理员同时显式配置 Host 白名单和私网访问开关。

## Requirements

### R1 可信 GitLab 主机

- Clone URL 的 Host 必须与 `app.rag.gitlab.allowed-hosts` 中的精确主机名匹配，比较时忽略大小写。
- 默认白名单仅包含 `gitlab.com`，不得接受子域后缀匹配、URL 凭据、查询参数或片段。
- 默认拒绝 IP 字面量，以及解析到任意本机、回环、链路本地、私网、IPv6 ULA 或组播地址的 Host。
- 公司自建 GitLab 只有在 Host 已加入白名单且 `app.rag.gitlab.allow-private-hosts=true` 时才允许访问私网地址。
- URL 校验必须发生在保存凭据和执行 `GIT_ASKPASS` 之前。

### R2 明确的 `targetSha` 更新语义

- 状态存储必须区分“保留当前 targetSha”和“用传入值替换 targetSha”；替换值允许为 `null`，表示显式清空。
- 发起“同步远端最新 HEAD”时，必须在 Git 操作前清空旧 `targetSha`。
- 已解析出目标提交后必须持久化该目标；后续失败只能保留该目标，不得清空或回退到更旧提交。
- `retry` 对有目标的失败任务重试持久化目标；对目标为空的早期失败重新获取远端 HEAD。
- `lastIndexedSha` 仍只在索引成功后更新。

### R3 中断任务恢复

- 应用启动恢复动态注册表后，自动重新入队 `PENDING/CLONING/SYNCING/INDEXING` 项目。
- 有持久化 `targetSha` 时恢复原目标；没有目标时同步当前远端 HEAD。
- `READY`、`FAILED`、`DISABLED` 不得自动入队。
- 动态项目与静态配置冲突时保持现有失败行为，不得调度同步。

### R4 配置与文档

- `.env.example`、`application.yml` 和简体中文接入指南必须说明 Host 白名单及私网 GitLab 开关。
- `CHANGELOG.md` 在当前版本的 `Fixed` 下记录三项修复。
- 更新 GitLab 自动接入 Trellis 规范，固化安全和恢复契约。

## Acceptance Criteria

- [ ] 非白名单 Host、IP 字面量和默认私网解析地址均在凭据保存或 Git 命令执行前被拒绝。
- [ ] 显式允许私网且精确白名单匹配时，公司内部 GitLab URL 可通过校验。
- [ ] `READY(A) -> 同步最新 HEAD 早期失败 -> retry` 不会直接用 A 标记 READY，而会重新获取远端目标。
- [ ] 已记录失败目标 B 时，远端移动到 C 后 retry 仍重试 B。
- [ ] 重启时四种中断状态均重新入队，且 `READY/FAILED/DISABLED` 不入队。
- [ ] 禁用状态仍不能被后台任务覆盖，单项目 FIFO 语义不回退。
- [ ] GitLab 模块定向测试、完整 Maven 测试、JaCoCo 和 `git diff --check` 通过。
- [ ] 个人文件 `面试问答全解-NEXUS.md` 不修改、不暂存、不提交。

## Out Of Scope

- 自动调用 GitLab API 创建 Webhook。
- 多实例共享同步队列或分布式锁。
- 改变现有管理 API 路径和响应结构。
- 自动推送提交到远端仓库。
