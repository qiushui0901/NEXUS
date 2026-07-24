---
featureId: "version-5.0.2-overview"
projectId: "immortal-game-service"
version: "5.0.2"
status: CODE_VERIFIED
codeCommit: "836abbd7f80561cfe6e19ac6ebbfdb1a9ebe3af7"
generatedAt: "2026-07-24T00:00:00+08:00"
---

# 5.0.2 版本概览

5.0.2 是 5.1 增量知识的代码基线。该版本与 5.1 分别保存，不能把 5.1 新增的成长基金写入本版本。

## 产品视角

- 5.0.2 与 5.1 使用不同的代码 commit 和知识目录。
- 成长基金尚未纳入本版本；历史功能必须按本版本证据单独核验。

## 开发视角

- 基线 commit：6b7a154851c6f7979d58c89485eb28899687a234
- Git 分支：origin/V5.0.2
- 版本 commit：836abbd7f80561cfe6e19ac6ebbfdb1a9ebe3af7

## 测试视角

- 5.0.2 页面不得展示 GrowFundService 作为本版本实现。
- 5.0.2 中成长特价礼包应保持 GrowDiscount 证据边界。
- 从 5.0.2 切换到 5.1 后，功能首次出现版本不得被改写。

## 风险与存疑

- 产品需求与详细测试证据仍需按 5.0.2 原始材料补充。

## 原始证据

### V5.0.2 分支头提交

- 类型：GIT
- 来源：immortal-game-service
- 版本：5.0.2
- 位置：origin/V5.0.2
- Commit：836abbd7f80561cfe6e19ac6ebbfdb1a9ebe3af7
- 核验状态：VERIFIED

> 5.0.2：在战斗结束后不执行重复释放技能。

