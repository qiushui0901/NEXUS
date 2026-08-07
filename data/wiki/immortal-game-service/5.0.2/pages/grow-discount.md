---
featureId: "grow-discount"
projectId: "immortal-game-service"
version: "5.0.2"
pageType: FEATURE
status: CODE_VERIFIED
codeCommit: "836abbd7f80561cfe6e19ac6ebbfdb1a9ebe3af7"
generatedAt: "2026-07-24T00:00:00+08:00"
---

# 成长特价礼包

成长特价礼包在 5.0.2 中已存在，由 GrowDiscount 的接口、MOA 实现和服务购买链路组成。它不是 5.1 的成长基金。

## 产品视角

- 成长特价礼包是 5.0.2 的独立功能。
- 不能将 GrowDiscountService 作为成长基金的代码证据。
- 具体礼包价格、条件和奖励内容仍需基于 5.0.2 需求原文核验。

## 开发视角

- IGrowDiscountMoaService.growDiscountBuy
- GrowDiscountMoaServiceImpl.growDiscountBuy
- GrowDiscountService.growDiscountBuy
- GrowDiscountService.checkBuy
- GrowDiscountService.doBuy

## 测试视角

- 购买接口经由 IGrowDiscountMoaService.growDiscountBuy 暴露。
- 购买前必须调用 GrowDiscountService.checkBuy。
- 合法购买进入 GrowDiscountService.doBuy。
- 检索本功能时不得将 GrowFundService 作为本版本代码证据。

## 风险与存疑

- 名称与成长基金相似；跨版本浏览时必须同时限定 version 和 featureId。
- 具体礼包配置规则尚待需求原文核验。

## 原始证据

### 成长特价礼包 API 接口

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0.2
- 位置：IGrowDiscountMoaService.growDiscountBuy
- 文件：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IGrowDiscountMoaService.java
- 符号：IGrowDiscountMoaService.growDiscountBuy
- Commit：836abbd7f80561cfe6e19ac6ebbfdb1a9ebe3af7
- 核验状态：VERIFIED

> 提供成长特价礼包购买入口。

### 成长特价礼包购买服务

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0.2
- 位置：GrowDiscountService
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/growDiscount/GrowDiscountService.java
- 符号：GrowDiscountService.growDiscountBuy / checkBuy / doBuy
- Commit：836abbd7f80561cfe6e19ac6ebbfdb1a9ebe3af7
- 核验状态：VERIFIED

> 购买链路使用 growDiscountBuy、checkBuy 和 doBuy。

