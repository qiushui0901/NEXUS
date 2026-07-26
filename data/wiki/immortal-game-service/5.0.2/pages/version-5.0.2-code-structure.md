---
featureId: "version-5.0.2-code-structure"
projectId: "immortal-game-service"
version: "5.0.2"
status: CODE_VERIFIED
codeCommit: "836abbd7f80561cfe6e19ac6ebbfdb1a9ebe3af7"
generatedAt: "2026-07-24T00:00:00+08:00"
---

# 5.0.2 代码结构与变更

这是由 Git 自动生成的版本代码证据页。比较基线 6b7a154851c6 与目标 836abbd7f805；共纳入 24 个受控文件。

## 产品视角

- 本页不生成未经需求原文核验的产品规则。

## 开发视角

- Git 代码边界：6b7a154851c6 → 836abbd7f805。
- 本版本受控识别 24 个代码/配置文件，其中 Java/Kotlin 22 个、测试文件 0 个、配置文件 2 个。
- 提交说明：5.0.2：在战斗结束后不执行重复释放技能。 提交时间：2026-06-30T15:53:36+08:00。
- ADDED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/constant/NumConstants.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/bean/money/MoneyType.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/constant/ResultEnum.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/constant/RoleLooksConstants.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/redis/activity/CrazyDigDao.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/rediskey/activity/CrazyDigCacheKeyUtils.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/crazyDig/CrazyDigExchangeModel.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/crazyDig/CrazyDigModel.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/crazyDig/CrazyDigPackModel.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/crazyDig/CrazyDigRankModel.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/exchange/ExchangeModel.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/fight/FightSkillModel.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/pack/base/AbstractPackHandler.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/role/RoleModel.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/AbstractShopHandler.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/IShopHandler.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/handlers/CrazyDigExchangeShopHandler.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/handlers/CrazyDigExchangeShopHandlerV2.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/handlers/HunyuanShopHandler.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/handlers/SevenDayTaskExchangeShopHandler.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/hero/HeroService.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/item/ItemService.java
- MODIFIED: immortal-game-service-api/pom.xml
- MODIFIED: immortal-game-service-impl/pom.xml
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/constant/NumConstants.java :: NumConstants
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/bean/money/MoneyType.java :: MoneyType, get, contains, canRemoveEmpty, isJade
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/constant/ResultEnum.java :: ResultEnum, get, getEc, getEm
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/constant/RoleLooksConstants.java :: RoleLooksConstants
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/redis/activity/CrazyDigDao.java :: CrazyDigDao, addDigNum, getDigNumMap, incExchangeLevel, getExchangeLevel, getPackRechargeInfo, addPackRechargeInfo, addAchStep
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/rediskey/activity/CrazyDigCacheKeyUtils.java :: CrazyDigCacheKeyUtils, getDigKey, getDigExchangeKey, getDigPackKey, getDigAchKey, getPickaxeKey, getCurrencyKey, concatSubKey
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/crazyDig/CrazyDigExchangeModel.java :: CrazyDigExchangeModel, getShopType, crazyDigExchangeIndex, getUnlockLevel, crazyDigExchange, getMaxUnlockLevel, buildExchangeShopPackList, buildShopIndexParam
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/crazyDig/CrazyDigModel.java :: CrazyDigModel, init, getTimeOffsetScene, crazyDigIndex, getDigRemainTime, crazyDigDig, getConfigActivityMain, getConfigCrazyDig
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/crazyDig/CrazyDigPackModel.java :: CrazyDigPackModel, init, crazyDigPackIndex, sortPackList, findUnDonePackListByType, findDonePackListByType, crazyDigRecharge, crazyDigBuyShop
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/crazyDig/CrazyDigRankModel.java :: CrazyDigRankModel, init, getPrizeLockType, getPrizeLockId, getPrizeAddSource, checkPrizeUser, getRankType, getRankConf
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/exchange/ExchangeModel.java :: ExchangeModel, exchange, exchangeIndex
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/fight/FightSkillModel.java :: FightSkillModel, runRoundBuff, runSkill, runHeroSkill, runHeroFatherSkill, runHeroSubSkill, runPassiveSkill, runHeroSubSkillByTarget
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/pack/base/AbstractPackHandler.java :: AbstractPackHandler, getPackTypeList, getActivityType, check, buildPackList, sortPackList, checkBuy, buildShopPackList
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/role/RoleModel.java :: RoleModel, init, onRoleExpAdd, getRolePropertyBatch, batchGetRoleProperty, getRoleProperty, buildIndex, getRoleBottomTabList
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/AbstractShopHandler.java :: AbstractShopHandler, check, buildGoodsInfoList, buildConsumeItemList, createSign, checkBuy, canReduce, moneyReduce
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/IShopHandler.java :: IShopHandler
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/handlers/CrazyDigExchangeShopHandler.java :: CrazyDigExchangeShopHandler, getType, canReduce, moneyReduce, getLevel, sendItem
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/handlers/CrazyDigExchangeShopHandlerV2.java :: CrazyDigExchangeShopHandlerV2, getType, canReduce, moneyReduce, getLevel, sendItem
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/handlers/HunyuanShopHandler.java :: HunyuanShopHandler, getType, getLevel, checkBuy
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/handlers/SevenDayTaskExchangeShopHandler.java :: SevenDayTaskExchangeShopHandler, getType, getLevel, canReduce, moneyReduce, getShopRedDot, buildGoodsInfoList, buildConsumeItemList
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/hero/HeroService.java :: HeroService, getPowerCount, getTeamHeroes, getTeamHeroesAndPet, getPetHeroDetail, getTeamHeroPropDescMap, getTeamHeroPropMap, getTeamHalosMap
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/item/ItemService.java :: ItemService, createItemNodeWithoutBaseInfo, getAll, canAdd, add, commonCheck, removeByConfigId, canRemoveByConfigId

## 测试视角

- 没有真实执行快照。
- 静态识别 0 个测试文件，需在发布流程关联执行报告。

## 风险与存疑

- 代码路径和结构证据不能证明运行时行为。
- 文件列表按安全上限截断，完整源码仍以 Git commit 为准。

## 原始证据

### 5.0.2 代码版本边界

- 类型：GIT
- 来源：immortal-game-service
- 版本：5.0.2
- 位置：6b7a154851c6 到 836abbd7f805
- Commit：836abbd7f80561cfe6e19ac6ebbfdb1a9ebe3af7
- 核验状态：VERIFIED

> 6b7a154851c6 到 836abbd7f805；提交说明：5.0.2：在战斗结束后不执行重复释放技能；纳入 24 个受控代码/配置文件。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/constant/NumConstants.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0.2
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/constant/NumConstants.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/constant/NumConstants.java
- 符号：NumConstants
- Commit：836abbd7f80561cfe6e19ac6ebbfdb1a9ebe3af7
- 核验状态：VERIFIED

> Git 836abbd7f805 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/constant/NumConstants.java；识别到结构符号：NumConstants。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/bean/money/MoneyType.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0.2
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/bean/money/MoneyType.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/bean/money/MoneyType.java
- 符号：MoneyType, get, contains, canRemoveEmpty, isJade
- Commit：836abbd7f80561cfe6e19ac6ebbfdb1a9ebe3af7
- 核验状态：VERIFIED

> Git 836abbd7f805 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/bean/money/MoneyType.java；识别到结构符号：MoneyType, get, contains, canRemoveEmpty, isJade。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/constant/ResultEnum.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0.2
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/constant/ResultEnum.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/constant/ResultEnum.java
- 符号：ResultEnum, get, getEc, getEm
- Commit：836abbd7f80561cfe6e19ac6ebbfdb1a9ebe3af7
- 核验状态：VERIFIED

> Git 836abbd7f805 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/constant/ResultEnum.java；识别到结构符号：ResultEnum, get, getEc, getEm。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/constant/RoleLooksConstants.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0.2
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/constant/RoleLooksConstants.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/constant/RoleLooksConstants.java
- 符号：RoleLooksConstants
- Commit：836abbd7f80561cfe6e19ac6ebbfdb1a9ebe3af7
- 核验状态：VERIFIED

> Git 836abbd7f805 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/constant/RoleLooksConstants.java；识别到结构符号：RoleLooksConstants。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/redis/activity/CrazyDigDao.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0.2
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/redis/activity/CrazyDigDao.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/redis/activity/CrazyDigDao.java
- 符号：CrazyDigDao, addDigNum, getDigNumMap, incExchangeLevel, getExchangeLevel, getPackRechargeInfo, addPackRechargeInfo, addAchStep
- Commit：836abbd7f80561cfe6e19ac6ebbfdb1a9ebe3af7
- 核验状态：VERIFIED

> Git 836abbd7f805 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/redis/activity/CrazyDigDao.java；识别到结构符号：CrazyDigDao, addDigNum, getDigNumMap, incExchangeLevel, getExchangeLevel, getPackRechargeInfo, addPackRechargeInfo, addAchStep。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/rediskey/activity/CrazyDigCacheKeyUtils.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0.2
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/rediskey/activity/CrazyDigCacheKeyUtils.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/rediskey/activity/CrazyDigCacheKeyUtils.java
- 符号：CrazyDigCacheKeyUtils, getDigKey, getDigExchangeKey, getDigPackKey, getDigAchKey, getPickaxeKey, getCurrencyKey, concatSubKey
- Commit：836abbd7f80561cfe6e19ac6ebbfdb1a9ebe3af7
- 核验状态：VERIFIED

> Git 836abbd7f805 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/rediskey/activity/CrazyDigCacheKeyUtils.java；识别到结构符号：CrazyDigCacheKeyUtils, getDigKey, getDigExchangeKey, getDigPackKey, getDigAchKey, getPickaxeKey, getCurrencyKey, concatSubKey。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/crazyDig/CrazyDigExchangeModel.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0.2
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/crazyDig/CrazyDigExchangeModel.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/crazyDig/CrazyDigExchangeModel.java
- 符号：CrazyDigExchangeModel, getShopType, crazyDigExchangeIndex, getUnlockLevel, crazyDigExchange, getMaxUnlockLevel, buildExchangeShopPackList, buildShopIndexParam
- Commit：836abbd7f80561cfe6e19ac6ebbfdb1a9ebe3af7
- 核验状态：VERIFIED

> Git 836abbd7f805 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/crazyDig/CrazyDigExchangeModel.java；识别到结构符号：CrazyDigExchangeModel, getShopType, crazyDigExchangeIndex, getUnlockLevel, crazyDigExchange, getMaxUnlockLevel, buildExchangeShopPackList, buildShopIndexParam。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/crazyDig/CrazyDigModel.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0.2
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/crazyDig/CrazyDigModel.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/crazyDig/CrazyDigModel.java
- 符号：CrazyDigModel, init, getTimeOffsetScene, crazyDigIndex, getDigRemainTime, crazyDigDig, getConfigActivityMain, getConfigCrazyDig
- Commit：836abbd7f80561cfe6e19ac6ebbfdb1a9ebe3af7
- 核验状态：VERIFIED

> Git 836abbd7f805 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/crazyDig/CrazyDigModel.java；识别到结构符号：CrazyDigModel, init, getTimeOffsetScene, crazyDigIndex, getDigRemainTime, crazyDigDig, getConfigActivityMain, getConfigCrazyDig。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/crazyDig/CrazyDigPackModel.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0.2
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/crazyDig/CrazyDigPackModel.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/crazyDig/CrazyDigPackModel.java
- 符号：CrazyDigPackModel, init, crazyDigPackIndex, sortPackList, findUnDonePackListByType, findDonePackListByType, crazyDigRecharge, crazyDigBuyShop
- Commit：836abbd7f80561cfe6e19ac6ebbfdb1a9ebe3af7
- 核验状态：VERIFIED

> Git 836abbd7f805 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/crazyDig/CrazyDigPackModel.java；识别到结构符号：CrazyDigPackModel, init, crazyDigPackIndex, sortPackList, findUnDonePackListByType, findDonePackListByType, crazyDigRecharge, crazyDigBuyShop。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/crazyDig/CrazyDigRankModel.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0.2
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/crazyDig/CrazyDigRankModel.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/crazyDig/CrazyDigRankModel.java
- 符号：CrazyDigRankModel, init, getPrizeLockType, getPrizeLockId, getPrizeAddSource, checkPrizeUser, getRankType, getRankConf
- Commit：836abbd7f80561cfe6e19ac6ebbfdb1a9ebe3af7
- 核验状态：VERIFIED

> Git 836abbd7f805 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/crazyDig/CrazyDigRankModel.java；识别到结构符号：CrazyDigRankModel, init, getPrizeLockType, getPrizeLockId, getPrizeAddSource, checkPrizeUser, getRankType, getRankConf。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/exchange/ExchangeModel.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0.2
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/exchange/ExchangeModel.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/exchange/ExchangeModel.java
- 符号：ExchangeModel, exchange, exchangeIndex
- Commit：836abbd7f80561cfe6e19ac6ebbfdb1a9ebe3af7
- 核验状态：VERIFIED

> Git 836abbd7f805 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/exchange/ExchangeModel.java；识别到结构符号：ExchangeModel, exchange, exchangeIndex。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/fight/FightSkillModel.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0.2
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/fight/FightSkillModel.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/fight/FightSkillModel.java
- 符号：FightSkillModel, runRoundBuff, runSkill, runHeroSkill, runHeroFatherSkill, runHeroSubSkill, runPassiveSkill, runHeroSubSkillByTarget
- Commit：836abbd7f80561cfe6e19ac6ebbfdb1a9ebe3af7
- 核验状态：VERIFIED

> Git 836abbd7f805 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/fight/FightSkillModel.java；识别到结构符号：FightSkillModel, runRoundBuff, runSkill, runHeroSkill, runHeroFatherSkill, runHeroSubSkill, runPassiveSkill, runHeroSubSkillByTarget。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/pack/base/AbstractPackHandler.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0.2
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/pack/base/AbstractPackHandler.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/pack/base/AbstractPackHandler.java
- 符号：AbstractPackHandler, getPackTypeList, getActivityType, check, buildPackList, sortPackList, checkBuy, buildShopPackList
- Commit：836abbd7f80561cfe6e19ac6ebbfdb1a9ebe3af7
- 核验状态：VERIFIED

> Git 836abbd7f805 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/pack/base/AbstractPackHandler.java；识别到结构符号：AbstractPackHandler, getPackTypeList, getActivityType, check, buildPackList, sortPackList, checkBuy, buildShopPackList。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/role/RoleModel.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0.2
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/role/RoleModel.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/role/RoleModel.java
- 符号：RoleModel, init, onRoleExpAdd, getRolePropertyBatch, batchGetRoleProperty, getRoleProperty, buildIndex, getRoleBottomTabList
- Commit：836abbd7f80561cfe6e19ac6ebbfdb1a9ebe3af7
- 核验状态：VERIFIED

> Git 836abbd7f805 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/role/RoleModel.java；识别到结构符号：RoleModel, init, onRoleExpAdd, getRolePropertyBatch, batchGetRoleProperty, getRoleProperty, buildIndex, getRoleBottomTabList。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/AbstractShopHandler.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0.2
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/AbstractShopHandler.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/AbstractShopHandler.java
- 符号：AbstractShopHandler, check, buildGoodsInfoList, buildConsumeItemList, createSign, checkBuy, canReduce, moneyReduce
- Commit：836abbd7f80561cfe6e19ac6ebbfdb1a9ebe3af7
- 核验状态：VERIFIED

> Git 836abbd7f805 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/AbstractShopHandler.java；识别到结构符号：AbstractShopHandler, check, buildGoodsInfoList, buildConsumeItemList, createSign, checkBuy, canReduce, moneyReduce。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/IShopHandler.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0.2
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/IShopHandler.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/IShopHandler.java
- 符号：IShopHandler
- Commit：836abbd7f80561cfe6e19ac6ebbfdb1a9ebe3af7
- 核验状态：VERIFIED

> Git 836abbd7f805 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/IShopHandler.java；识别到结构符号：IShopHandler。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/handlers/CrazyDigExchangeShopHandler.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0.2
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/handlers/CrazyDigExchangeShopHandler.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/handlers/CrazyDigExchangeShopHandler.java
- 符号：CrazyDigExchangeShopHandler, getType, canReduce, moneyReduce, getLevel, sendItem
- Commit：836abbd7f80561cfe6e19ac6ebbfdb1a9ebe3af7
- 核验状态：VERIFIED

> Git 836abbd7f805 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/handlers/CrazyDigExchangeShopHandler.java；识别到结构符号：CrazyDigExchangeShopHandler, getType, canReduce, moneyReduce, getLevel, sendItem。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/handlers/CrazyDigExchangeShopHandlerV2.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0.2
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/handlers/CrazyDigExchangeShopHandlerV2.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/handlers/CrazyDigExchangeShopHandlerV2.java
- 符号：CrazyDigExchangeShopHandlerV2, getType, canReduce, moneyReduce, getLevel, sendItem
- Commit：836abbd7f80561cfe6e19ac6ebbfdb1a9ebe3af7
- 核验状态：VERIFIED

> Git 836abbd7f805 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/handlers/CrazyDigExchangeShopHandlerV2.java；识别到结构符号：CrazyDigExchangeShopHandlerV2, getType, canReduce, moneyReduce, getLevel, sendItem。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/handlers/HunyuanShopHandler.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0.2
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/handlers/HunyuanShopHandler.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/handlers/HunyuanShopHandler.java
- 符号：HunyuanShopHandler, getType, getLevel, checkBuy
- Commit：836abbd7f80561cfe6e19ac6ebbfdb1a9ebe3af7
- 核验状态：VERIFIED

> Git 836abbd7f805 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/handlers/HunyuanShopHandler.java；识别到结构符号：HunyuanShopHandler, getType, getLevel, checkBuy。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/handlers/SevenDayTaskExchangeShopHandler.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0.2
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/handlers/SevenDayTaskExchangeShopHandler.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/handlers/SevenDayTaskExchangeShopHandler.java
- 符号：SevenDayTaskExchangeShopHandler, getType, getLevel, canReduce, moneyReduce, getShopRedDot, buildGoodsInfoList, buildConsumeItemList
- Commit：836abbd7f80561cfe6e19ac6ebbfdb1a9ebe3af7
- 核验状态：VERIFIED

> Git 836abbd7f805 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/handlers/SevenDayTaskExchangeShopHandler.java；识别到结构符号：SevenDayTaskExchangeShopHandler, getType, getLevel, canReduce, moneyReduce, getShopRedDot, buildGoodsInfoList, buildConsumeItemList。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/hero/HeroService.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0.2
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/hero/HeroService.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/hero/HeroService.java
- 符号：HeroService, getPowerCount, getTeamHeroes, getTeamHeroesAndPet, getPetHeroDetail, getTeamHeroPropDescMap, getTeamHeroPropMap, getTeamHalosMap
- Commit：836abbd7f80561cfe6e19ac6ebbfdb1a9ebe3af7
- 核验状态：VERIFIED

> Git 836abbd7f805 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/hero/HeroService.java；识别到结构符号：HeroService, getPowerCount, getTeamHeroes, getTeamHeroesAndPet, getPetHeroDetail, getTeamHeroPropDescMap, getTeamHeroPropMap, getTeamHalosMap。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/item/ItemService.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0.2
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/item/ItemService.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/item/ItemService.java
- 符号：ItemService, createItemNodeWithoutBaseInfo, getAll, canAdd, add, commonCheck, removeByConfigId, canRemoveByConfigId
- Commit：836abbd7f80561cfe6e19ac6ebbfdb1a9ebe3af7
- 核验状态：VERIFIED

> Git 836abbd7f805 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/item/ItemService.java；识别到结构符号：ItemService, createItemNodeWithoutBaseInfo, getAll, canAdd, add, commonCheck, removeByConfigId, canRemoveByConfigId。

### 代码文件：immortal-game-service-api/pom.xml

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0.2
- 位置：immortal-game-service-api/pom.xml
- 文件：immortal-game-service-api/pom.xml
- Commit：836abbd7f80561cfe6e19ac6ebbfdb1a9ebe3af7
- 核验状态：VERIFIED

> Git 836abbd7f805 的版本证据：immortal-game-service-api/pom.xml；仅记录文件路径和类型，未复制源码正文。

### 代码文件：immortal-game-service-impl/pom.xml

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0.2
- 位置：immortal-game-service-impl/pom.xml
- 文件：immortal-game-service-impl/pom.xml
- Commit：836abbd7f80561cfe6e19ac6ebbfdb1a9ebe3af7
- 核验状态：VERIFIED

> Git 836abbd7f805 的版本证据：immortal-game-service-impl/pom.xml；仅记录文件路径和类型，未复制源码正文。

