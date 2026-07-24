---
featureId: "grow-fund"
projectId: "immortal-game-service"
version: "5.1"
status: CODE_VERIFIED
codeCommit: "f7e0e22bec3068a45636ec2985e21abc1975c3e5"
generatedAt: "2026-07-24T00:00:00+08:00"
---

# 成长基金

成长基金是 5.1 中独立实现的功能。已确认服务入口、购买校验和购买执行代码；具体产品数值与领取规则仍需依据 5.1 需求原文审核。

## 产品视角

- 该功能属于 5.1 版本知识范围。
- 成长基金不是成长特价礼包，不能使用 GrowDiscountService 作为实现证据。
- 具体购买条件、奖励档位和领取规则标记为待需求核验，当前页面不做无证据推断。

## 开发视角

- IGrowFundMoaService.growFundIndex
- IGrowFundMoaService.growFundBuy
- GrowFundMoaServiceImpl.growFundIndex
- GrowFundMoaServiceImpl.growFundBuy
- GrowFundService.index
- GrowFundService.canBuy
- GrowFundService.buy

## 测试视角

- 成长基金首页接口能够返回当前玩家的可购买与领取状态。
- 购买前必须经过 GrowFundService.canBuy 校验。
- 重复购买、条件不足和非法请求不能进入成功购买路径。
- 成长基金查询和购买不得路由到 GrowDiscountService。
- 需求规则补齐后，为奖励档位、领取条件和边界等级增加验收用例。

## 风险与存疑

- 需求原文中的价格、等级和奖励内容尚未在本页核验。
- 名称相似可能导致检索误召回成长特价礼包，需要 featureId 和版本过滤。

## 关联功能

- **成长特价礼包** (`grow-discount`)：名称相似但代码入口和版本边界不同，不得互相作为证据。
- **成长类功能边界** (`grow-feature-boundary`)：查看两项功能的证据隔离规则。

## 原始证据

### 成长基金 MOA 接口

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.1
- 位置：IGrowFundMoaService
- 文件：immortal-game-service-api/src/main/java
- 符号：IGrowFundMoaService.growFundIndex / growFundBuy
- Commit：f7e0e22bec3068a45636ec2985e21abc1975c3e5
- 核验状态：VERIFIED

> 提供成长基金首页与购买入口。

### 成长基金服务实现

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.1
- 位置：GrowFundService
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/growFund/
- 符号：GrowFundService.index / canBuy / buy
- Commit：f7e0e22bec3068a45636ec2985e21abc1975c3e5
- 核验状态：VERIFIED

> 包含首页、购买资格检查和购买执行方法。

### 5.1 成长基金需求

- 类型：REQUIREMENT
- 来源：产品文档.zip
- 版本：5.1
- 位置：成长基金相关章节
- 核验状态：PENDING_REVIEW

> 需求内容等待逐条提取和人工核验，本页不提前生成具体数值规则。

