---
featureId: "version-5.1-overview"
projectId: "immortal-game-service"
version: "5.1"
status: DRAFT
codeCommit: "f7e0e22bec3068a45636ec2985e21abc1975c3e5"
generatedAt: "2026-07-24T00:00:00+08:00"
---

# 5.1 版本概览

5.1 的需求、代码和测试知识入口。当前代码基于 5.0.2 到 5.1 的 Git 增量建立版本边界，页面只收录有来源的事实。

## 产品视角

- 需求知识按 5.1 独立管理，不与 5.0.2 的需求结论自动合并。
- 尚未通过需求原文核验的业务规则保持待审核状态。

## 开发视角

- 基线 commit: 836abbd7f80561cfe6e19ac6ebbfdb1a9ebe3af7
- 5.1 commit: f7e0e22bec3068a45636ec2985e21abc1975c3e5
- 5.0.2 到 5.1 共识别 76 个变化 Java 文件。

## 测试视角

- 验证 5.0.2 与 5.1 的检索结果不会跨版本串线。
- 验证新增、修改、删除和重命名文件的索引更新。
- 验证相似名称功能使用不同 featureId。

## 风险与存疑

- 需求源尚需逐功能完成原文证据核验。
- 本页是版本导航，不替代具体功能页面的原始证据。

## 关联功能

- **成长基金** (`grow-fund`)：5.1 中确认存在代码实现的成长基金功能。
- **成长类功能边界** (`grow-feature-boundary`)：说明成长基金与成长特价礼包的隔离规则。

## 原始证据

### 5.1 知识清单

- 类型：MANIFEST
- 来源：data/knowledge-manifests/immortal-game-service-v5.1.json
- 版本：5.1
- 位置：code
- Commit：f7e0e22bec3068a45636ec2985e21abc1975c3e5
- 核验状态：VERIFIED

> 记录 5.0.2 基线、5.1 commit 和 76 个变化 Java 文件。

