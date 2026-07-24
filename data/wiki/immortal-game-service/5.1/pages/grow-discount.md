---
featureId: "grow-discount"
projectId: "immortal-game-service"
version: "5.1"
status: CODE_VERIFIED
codeCommit: "f7e0e22bec3068a45636ec2985e21abc1975c3e5"
generatedAt: "2026-07-24T00:00:00+08:00"
---

# 成长特价礼包

成长特价礼包是早于 5.1 已存在的独立功能，由 GrowDiscountService 实现。它与 5.1 的成长基金不是同一个功能。

## 产品视角

- 该功能的已知版本边界早于成长基金。
- 不得把成长特价礼包代码解释为 5.1 成长基金的实现。
- 产品规则和具体礼包内容仍需对应版本需求证据核验。

## 开发视角

- GrowDiscountService.growDiscountBuy
- GrowDiscountService.checkBuy
- GrowDiscountService.doBuy

## 测试视角

- 购买入口执行 GrowDiscountService.checkBuy 校验。
- 合法购买进入 GrowDiscountService.doBuy。
- 检索成长特价礼包时不应把 GrowFundService 作为主要实现证据。
- 版本对比中应标记为既有功能，而不是 5.1 新增成长基金。

## 风险与存疑

- 中文名称与成长基金相似，必须使用 grow-discount 作为稳定标识。
- 当前页面只确认代码边界，礼包配置规则仍待需求审核。

## 关联功能

- **成长基金** (`grow-fund`)：5.1 中独立新增的 GrowFund 实现，不是本功能的别名。
- **成长类功能边界** (`grow-feature-boundary`)：查看两个功能的版本和代码隔离。

## 原始证据

### 成长特价礼包服务

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0
- 位置：GrowDiscountService
- 文件：immortal-game-service-impl/src/main/java
- 符号：GrowDiscountService.growDiscountBuy / checkBuy / doBuy
- Commit：836abbd7f80561cfe6e19ac6ebbfdb1a9ebe3af7
- 核验状态：VERIFIED

> 购买链路由 growDiscountBuy、checkBuy 和 doBuy 组成。

