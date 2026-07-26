---
featureId: "version-5.1-overview"
projectId: "immortal-game-service"
version: "5.1"
status: DRAFT
codeCommit: "f7e0e22bec3068a45636ec2985e21abc1975c3e5"
generatedAt: "2026-07-24T00:00:00+08:00"
---

# 5.1 版本概览

5.1 的需求、代码和测试知识入口。 当前代码基于 5.0.2 到 5.1 的 Git 增量建立版本边界，页面只收录有来源的事实。 自动代码证据：Git 代码边界：836abbd7f805 → f7e0e22bec30。 本版本受控识别 78 个代码/配置文件，其中 Java/Kotlin 75 个、测试文件 0 个、配置文件 3 个。 提交说明：Merge branch 'V5.1.0' into 'master'。 提交时间：2026-07-16T14:18:40+00:00。

## 产品视角

- 需求知识按 5.1 独立管理，不与 5.0.2 的需求结论自动合并。
- 尚未通过需求原文核验的业务规则保持待审核状态。

## 开发视角

- 基线 commit: 836abbd7f80561cfe6e19ac6ebbfdb1a9ebe3af7
- 5.1 commit: f7e0e22bec3068a45636ec2985e21abc1975c3e5
- 5.0.2 到 5.1 共识别 76 个变化 Java 文件。
- Git 代码边界：836abbd7f805 → f7e0e22bec30。
- 本版本受控识别 78 个代码/配置文件，其中 Java/Kotlin 75 个、测试文件 0 个、配置文件 3 个。
- 提交说明：Merge branch 'V5.1.0' into 'master'。 提交时间：2026-07-16T14:18:40+00:00。

## 测试视角

- 验证 5.0.2 与 5.1 的检索结果不会跨版本串线。
- 验证新增、修改、删除和重命名文件的索引更新。
- 验证相似名称功能使用不同 featureId。
- 没有真实执行快照；本页只记录 Git 版本边界和静态测试文件证据。
- 静态识别测试文件 0 个；发布前需要关联真实测试报告。

## 风险与存疑

- 需求源尚需逐功能完成原文证据核验。
- 本页是版本导航，不替代具体功能页面的原始证据。
- 自动代码证据只证明文件和结构存在，不等同于业务规则或运行时行为。
- 测试执行结果尚未关联到本版本 Wiki。

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

### 5.1 代码版本边界

- 类型：GIT
- 来源：immortal-game-service
- 版本：5.1
- 位置：836abbd7f805 到 f7e0e22bec30
- Commit：f7e0e22bec3068a45636ec2985e21abc1975c3e5
- 核验状态：VERIFIED

> 836abbd7f805 到 f7e0e22bec30；提交说明：Merge branch 'V5.1.0' into 'master'；纳入 78 个受控代码/配置文件。

