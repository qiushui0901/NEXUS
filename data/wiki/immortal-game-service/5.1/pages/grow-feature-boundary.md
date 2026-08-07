---
featureId: "grow-feature-boundary"
projectId: "immortal-game-service"
version: "5.1"
pageType: FEATURE
status: CODE_VERIFIED
codeCommit: "f7e0e22bec3068a45636ec2985e21abc1975c3e5"
generatedAt: "2026-07-24T00:00:00+08:00"
---

# 成长基金与成长特价礼包边界

这是一条强制知识边界：成长基金和成长特价礼包使用不同的 featureId、版本来源和代码符号，检索、生成和测试均不得自动合并。

## 产品视角

- 成长基金按 5.1 功能管理。
- 成长特价礼包按既有功能管理。
- 名称相似只建立容易混淆关系，不建立同义词关系。

## 开发视角

- 成长基金：GrowFundService、IGrowFundMoaService、GrowFundMoaServiceImpl
- 成长特价礼包：GrowDiscountService
- 禁止将 GrowDiscountService 用作成长基金代码证据。

## 测试视角

- 查询成长基金时主要代码命中必须包含 GrowFundService。
- 查询成长特价礼包时主要代码命中必须包含 GrowDiscountService。
- 两张功能页面必须互相链接但保持独立证据列表。
- 切换 5.0.2 和 5.1 时不得把功能首次出现版本改写。

## 风险与存疑

- 只用中文关键词而不使用版本和 featureId 过滤时仍可能发生语义误召回。

## 关联功能

- **成长基金** (`grow-fund`)：5.1 GrowFund 实现。
- **成长特价礼包** (`grow-discount`)：既有 GrowDiscount 实现。

## 原始证据

### 功能边界声明

- 类型：MANIFEST
- 来源：data/knowledge-manifests/immortal-game-service-v5.1.json
- 版本：5.1
- 位置：featureBoundaries
- Commit：f7e0e22bec3068a45636ec2985e21abc1975c3e5
- 核验状态：VERIFIED

> 清单分别记录 growthFund 和 growthDiscount，并声明 doNotConfuseWith / doNotUseAsGrowthFundEvidence。

