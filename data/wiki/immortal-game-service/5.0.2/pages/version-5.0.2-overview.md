---
featureId: "version-5.0.2-overview"
projectId: "immortal-game-service"
version: "5.0.2"
pageType: FEATURE
status: CODE_VERIFIED
codeCommit: "836abbd7f80561cfe6e19ac6ebbfdb1a9ebe3af7"
generatedAt: "2026-07-24T00:00:00+08:00"
---

# 5.0.2 版本概览

5.0.2 是 5.1 增量知识的代码基线。 该版本与 5.1 分别保存，不能把 5.1 新增的成长基金写入本版本。 自动代码证据：Git 代码边界：6b7a154851c6 → 836abbd7f805。 本版本受控识别 24 个代码/配置文件，其中 Java/Kotlin 22 个、测试文件 0 个、配置文件 2 个。 提交说明：5.0.2：在战斗结束后不执行重复释放技能。 提交时间：2026-06-30T15:53:36+08:00。

## 产品视角

- 5.0.2 与 5.1 使用不同的代码 commit 和知识目录。
- 成长基金尚未纳入本版本；历史功能必须按本版本证据单独核验。

## 开发视角

- 基线 commit：6b7a154851c6f7979d58c89485eb28899687a234
- Git 分支：origin/V5.0.2
- 版本 commit：836abbd7f80561cfe6e19ac6ebbfdb1a9ebe3af7
- Git 代码边界：6b7a154851c6 → 836abbd7f805。
- 本版本受控识别 24 个代码/配置文件，其中 Java/Kotlin 22 个、测试文件 0 个、配置文件 2 个。
- 提交说明：5.0.2：在战斗结束后不执行重复释放技能。 提交时间：2026-06-30T15:53:36+08:00。

## 测试视角

- 5.0.2 页面不得展示 GrowFundService 作为本版本实现。
- 5.0.2 中成长特价礼包应保持 GrowDiscount 证据边界。
- 从 5.0.2 切换到 5.1 后，功能首次出现版本不得被改写。
- 没有真实执行快照；本页只记录 Git 版本边界和静态测试文件证据。
- 静态识别测试文件 0 个；发布前需要关联真实测试报告。

## 风险与存疑

- 产品需求与详细测试证据仍需按 5.0.2 原始材料补充。
- 自动代码证据只证明文件和结构存在，不等同于业务规则或运行时行为。
- 测试执行结果尚未关联到本版本 Wiki。

## 原始证据

### V5.0.2 分支头提交

- 类型：GIT
- 来源：immortal-game-service
- 版本：5.0.2
- 位置：origin/V5.0.2
- Commit：836abbd7f80561cfe6e19ac6ebbfdb1a9ebe3af7
- 核验状态：VERIFIED

> 5.0.2：在战斗结束后不执行重复释放技能。

### 5.0.2 代码版本边界

- 类型：GIT
- 来源：immortal-game-service
- 版本：5.0.2
- 位置：6b7a154851c6 到 836abbd7f805
- Commit：836abbd7f80561cfe6e19ac6ebbfdb1a9ebe3af7
- 核验状态：VERIFIED

> 6b7a154851c6 到 836abbd7f805；提交说明：5.0.2：在战斗结束后不执行重复释放技能；纳入 24 个受控代码/配置文件。

