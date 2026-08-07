---
featureId: "version-5.1-module-immortal-game-service-impl"
projectId: "immortal-game-service"
version: "5.1"
pageType: FEATURE
status: CODE_VERIFIED
codeCommit: "f7e0e22bec3068a45636ec2985e21abc1975c3e5"
generatedAt: "2026-07-24T00:00:00+08:00"
---

# 5.1 · immortal-game-service-impl 模块

Git 版本 5.1 中，模块 immortal-game-service-impl 受控识别 65 个文件；内容来自 commit f7e0e22bec30 的路径和结构扫描。

## 产品视角

- 没有关联需求原文，因此不把类名解释为产品规则。

## 开发视角

- ADDED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/bean/config/StageDowngradeConfig.java
- ADDED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/constant/HistoryConstants.java
- ADDED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/enums/WarGoodsOpType.java
- ADDED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/growFund/GrowFundModel.java
- ADDED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/growFund/GrowFundTaskModel.java
- ADDED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/union/WarGoodsRecordModel.java
- ADDED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/growFund/GrowFundService.java
- ADDED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/GrowFundMoaServiceImpl.java
- ADDED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/warGoodsRecord/WarGoodsRecordService.java
- ADDED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/cache/FirstFullSyncCache.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/GameMomoApplication.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/bean/funcUnlock/FunctionType.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/bean/money/MoneyType.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/constant/ResultEnum.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/redis/HistoryDao.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/redis/UnionDao.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/redis/activity/CrazyDigDao.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/rediskey/UnionCacheKeyUtils.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/rediskey/activity/CrazyDigCacheKeyUtils.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/enums/AddSource.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/enums/HistoryType.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/enums/RechargeType.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/enums/Reminder.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/enums/RemoveSource.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/enums/TaskSceneEnum.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/crazyDig/CrazyDigExchangeModel.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/crazyDig/CrazyDigModel.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/crazyDig/CrazyDigPackModel.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/crazyDig/CrazyDigRankModel.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/monitor/ThreadMonitorModel.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/pack/base/AbstractPackHandler.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/AbstractShopHandler.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/IShopHandler.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/handlers/CrazyDigExchangeShopHandler.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/handlers/CrazyDigExchangeShopHandlerV2.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/handlers/HunyuanShopHandler.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/handlers/SevenDayTaskExchangeShopHandler.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/sign/WelfareModel.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/IImmortalMoaServiceImpl.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/compact/WorldCompactService.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/BiMoaServiceImpl.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/UnionMoaServiceImpl.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/utils/AsyncExecPoolUtils.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/bean/cacheBean/team/WorldTeam.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/bean/stage/StageBase.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/bean/stage/StageWorld.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/config/ImmortalConfig.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/constant/WorldFightConstants.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/enums/hubble/Title.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/message/s2c/MessageListProcessor.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/message/s2c/SingleMsgConsumer.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/message/s2c/VersionRoomMessageProcessor.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/season/RoomSeasonSwitchModel.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/fight/FightPluginCommon.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/fight/FightPluginWar.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/march/IMarchPlugin.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/march/MarchPluginCommon.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/march/MarchPluginWar.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/IImmortalWorldMoaServiceImpl.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/impl/WorldHistoryMoaServiceImpl.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/impl/WorldMessageMoaServiceImpl.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/common/PongHandler.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/stage/StageGlobalService.java
- MODIFIED: immortal-game-service-impl/pom.xml
- DELETED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/FullSyncHandler.java
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/bean/config/StageDowngradeConfig.java :: StageDowngradeConfig, isDowngrade
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/constant/HistoryConstants.java :: HistoryConstants
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/enums/WarGoodsOpType.java :: WarGoodsOpType, fromId, isCost
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/growFund/GrowFundModel.java :: GrowFundModel, isActivated, getRoleLevel, getRechargeConfigId, getRechargeInfoMap, redPot, cancelRedPot, isAllReceived
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/growFund/GrowFundTaskModel.java :: GrowFundTaskModel, check
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/union/WarGoodsRecordModel.java :: WarGoodsRecordModel, init, addWarGoodsRecord, getRecordList, clearRedDot, onBattlefieldGoodsAutoUse
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/growFund/GrowFundService.java :: GrowFundService, index, canBuy, buy
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/GrowFundMoaServiceImpl.java :: GrowFundMoaServiceImpl, growFundIndex, growFundBuy
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/warGoodsRecord/WarGoodsRecordService.java :: WarGoodsRecordService, canBuildRecordList, buildRecordList, getHistoryType, getRecordList, fillUserNames, fillUnionNames
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/cache/FirstFullSyncCache.java :: FirstFullSyncCache, WaiterEntry, init, destroy, buildKey, createWaiter, complete, poll
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/GameMomoApplication.java :: GameMomoApplication, main
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/bean/funcUnlock/FunctionType.java :: FunctionType, getConfig, of, getByConfig, getBySubFunction
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/bean/money/MoneyType.java :: MoneyType, get, contains, canRemoveEmpty, isJade
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/constant/ResultEnum.java :: ResultEnum, get, getEc, getEm
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/redis/HistoryDao.java :: HistoryDao, addHistory, addHistories, getHistory, getLastHistory, addQueryHistoryTime, getQueryHistoryTime, delHistory
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/redis/UnionDao.java :: UnionDao, getUserUnionInfo, UserUnionDO, getUserUnionInfoFromMaster, batchQueryUserUnionIdMap, exception, applyReduce, query
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/redis/activity/CrazyDigDao.java :: CrazyDigDao, addDigNum, getDigNumMap, incExchangeLevel, getExchangeLevel, getPackRechargeInfo, addPackRechargeInfo, addAchStep
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/rediskey/UnionCacheKeyUtils.java :: UnionCacheKeyUtils, getUserUnionInfoKey, getUserUnionApplyKey, getUnionUserApplyKey, getUnionUserListKey, getUnionTotalContributionKey, getUnionDayContributionKey, getUnionMoneyKey
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/rediskey/activity/CrazyDigCacheKeyUtils.java :: CrazyDigCacheKeyUtils, getDigKey, getDigExchangeKey, getDigPackKey, getDigAchKey
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/enums/AddSource.java :: AddSource
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/enums/HistoryType.java :: HistoryType, getMaxNum, getType, getByType, getRedisKeyExtraInfo
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/enums/RechargeType.java :: RechargeType, getByType, getTimeUnitStr
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/enums/Reminder.java :: Reminder, of
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/enums/RemoveSource.java :: RemoveSource
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/enums/TaskSceneEnum.java :: TaskSceneEnum, enumOf, getTaskExtraByScene, getTaskCountDown, getFunctypeByScene, getExtraInfoData, isWorldSoldierScene, isWorldUnionScene
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/crazyDig/CrazyDigExchangeModel.java :: CrazyDigExchangeModel, getShopType, crazyDigExchangeIndex, getUnlockLevel, crazyDigExchange, getMaxUnlockLevel, buildExchangeShopPackList, buildShopIndexParam
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/crazyDig/CrazyDigModel.java :: CrazyDigModel, init, getTimeOffsetScene, crazyDigIndex, getDigRemainTime, crazyDigDig, getConfigActivityMain, getConfigCrazyDig
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/crazyDig/CrazyDigPackModel.java :: CrazyDigPackModel, init, crazyDigPackIndex, sortPackList, findUnDonePackListByType, findDonePackListByType, crazyDigRecharge, crazyDigBuyShop
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/crazyDig/CrazyDigRankModel.java :: CrazyDigRankModel, init, getPrizeLockType, getPrizeLockId, getPrizeAddSource, checkPrizeUser, getRankType, getRankConf
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/monitor/ThreadMonitorModel.java :: ThreadMonitorModel, register, stop, init, run, RuntimeException
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/pack/base/AbstractPackHandler.java :: AbstractPackHandler, getPackTypeList, getActivityType, check, buildPackList, sortPackList, checkBuy, buildShopPackList
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/AbstractShopHandler.java :: AbstractShopHandler, check, buildGoodsInfoList, buildConsumeItemList, createSign, checkBuy, moneyReduce, sendItem
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/IShopHandler.java :: IShopHandler
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/handlers/CrazyDigExchangeShopHandler.java :: CrazyDigExchangeShopHandler, getType, getLevel, sendItem
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/handlers/CrazyDigExchangeShopHandlerV2.java :: CrazyDigExchangeShopHandlerV2, getType, getLevel, sendItem
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/handlers/HunyuanShopHandler.java :: HunyuanShopHandler, getType, getLevel, checkBuy
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/handlers/SevenDayTaskExchangeShopHandler.java :: SevenDayTaskExchangeShopHandler, getType, getLevel, checkBuy, moneyReduce, getShopRedDot, buildGoodsInfoList, buildConsumeItemList
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/sign/WelfareModel.java :: WelfareModel, getBottomTabList, IllegalStateException, redPot
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/IImmortalMoaServiceImpl.java :: IImmortalMoaServiceImpl
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/compact/WorldCompactService.java :: WorldCompactService, index, findMyGroup, applyUserHonor

## 测试视角

- 没有真实执行快照。
- 该模块静态识别测试文件 0 个。

## 风险与存疑

- 自动扫描只保留文件路径和有限结构符号，不复制源码正文。

## 原始证据

### 5.1 代码版本边界

- 类型：GIT
- 来源：immortal-game-service
- 版本：5.1
- 位置：836abbd7f805 到 f7e0e22bec30
- Commit：f7e0e22bec3068a45636ec2985e21abc1975c3e5
- 核验状态：VERIFIED

> 836abbd7f805 到 f7e0e22bec30；提交说明：Merge branch 'V5.1.0' into 'master'；纳入 78 个受控代码/配置文件。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/bean/config/StageDowngradeConfig.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.1
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/bean/config/StageDowngradeConfig.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/bean/config/StageDowngradeConfig.java
- 符号：StageDowngradeConfig, isDowngrade
- Commit：f7e0e22bec3068a45636ec2985e21abc1975c3e5
- 核验状态：VERIFIED

> Git f7e0e22bec30 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/bean/config/StageDowngradeConfig.java；识别到结构符号：StageDowngradeConfig, isDowngrade。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/constant/HistoryConstants.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.1
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/constant/HistoryConstants.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/constant/HistoryConstants.java
- 符号：HistoryConstants
- Commit：f7e0e22bec3068a45636ec2985e21abc1975c3e5
- 核验状态：VERIFIED

> Git f7e0e22bec30 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/constant/HistoryConstants.java；识别到结构符号：HistoryConstants。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/enums/WarGoodsOpType.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.1
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/enums/WarGoodsOpType.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/enums/WarGoodsOpType.java
- 符号：WarGoodsOpType, fromId, isCost
- Commit：f7e0e22bec3068a45636ec2985e21abc1975c3e5
- 核验状态：VERIFIED

> Git f7e0e22bec30 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/enums/WarGoodsOpType.java；识别到结构符号：WarGoodsOpType, fromId, isCost。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/growFund/GrowFundModel.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.1
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/growFund/GrowFundModel.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/growFund/GrowFundModel.java
- 符号：GrowFundModel, isActivated, getRoleLevel, getRechargeConfigId, getRechargeInfoMap, redPot, cancelRedPot, isAllReceived
- Commit：f7e0e22bec3068a45636ec2985e21abc1975c3e5
- 核验状态：VERIFIED

> Git f7e0e22bec30 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/growFund/GrowFundModel.java；识别到结构符号：GrowFundModel, isActivated, getRoleLevel, getRechargeConfigId, getRechargeInfoMap, redPot, cancelRedPot, isAllReceived。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/growFund/GrowFundTaskModel.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.1
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/growFund/GrowFundTaskModel.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/growFund/GrowFundTaskModel.java
- 符号：GrowFundTaskModel, check
- Commit：f7e0e22bec3068a45636ec2985e21abc1975c3e5
- 核验状态：VERIFIED

> Git f7e0e22bec30 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/growFund/GrowFundTaskModel.java；识别到结构符号：GrowFundTaskModel, check。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/union/WarGoodsRecordModel.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.1
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/union/WarGoodsRecordModel.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/union/WarGoodsRecordModel.java
- 符号：WarGoodsRecordModel, init, addWarGoodsRecord, getRecordList, clearRedDot, onBattlefieldGoodsAutoUse
- Commit：f7e0e22bec3068a45636ec2985e21abc1975c3e5
- 核验状态：VERIFIED

> Git f7e0e22bec30 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/union/WarGoodsRecordModel.java；识别到结构符号：WarGoodsRecordModel, init, addWarGoodsRecord, getRecordList, clearRedDot, onBattlefieldGoodsAutoUse。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/growFund/GrowFundService.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.1
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/growFund/GrowFundService.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/growFund/GrowFundService.java
- 符号：GrowFundService, index, canBuy, buy
- Commit：f7e0e22bec3068a45636ec2985e21abc1975c3e5
- 核验状态：VERIFIED

> Git f7e0e22bec30 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/growFund/GrowFundService.java；识别到结构符号：GrowFundService, index, canBuy, buy。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/GrowFundMoaServiceImpl.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.1
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/GrowFundMoaServiceImpl.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/GrowFundMoaServiceImpl.java
- 符号：GrowFundMoaServiceImpl, growFundIndex, growFundBuy
- Commit：f7e0e22bec3068a45636ec2985e21abc1975c3e5
- 核验状态：VERIFIED

> Git f7e0e22bec30 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/GrowFundMoaServiceImpl.java；识别到结构符号：GrowFundMoaServiceImpl, growFundIndex, growFundBuy。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/warGoodsRecord/WarGoodsRecordService.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.1
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/warGoodsRecord/WarGoodsRecordService.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/warGoodsRecord/WarGoodsRecordService.java
- 符号：WarGoodsRecordService, canBuildRecordList, buildRecordList, getHistoryType, getRecordList, fillUserNames, fillUnionNames
- Commit：f7e0e22bec3068a45636ec2985e21abc1975c3e5
- 核验状态：VERIFIED

> Git f7e0e22bec30 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/warGoodsRecord/WarGoodsRecordService.java；识别到结构符号：WarGoodsRecordService, canBuildRecordList, buildRecordList, getHistoryType, getRecordList, fillUserNames, fillUnionNames。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/cache/FirstFullSyncCache.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.1
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/cache/FirstFullSyncCache.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/cache/FirstFullSyncCache.java
- 符号：FirstFullSyncCache, WaiterEntry, init, destroy, buildKey, createWaiter, complete, poll
- Commit：f7e0e22bec3068a45636ec2985e21abc1975c3e5
- 核验状态：VERIFIED

> Git f7e0e22bec30 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/cache/FirstFullSyncCache.java；识别到结构符号：FirstFullSyncCache, WaiterEntry, init, destroy, buildKey, createWaiter, complete, poll。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/GameMomoApplication.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.1
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/GameMomoApplication.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/GameMomoApplication.java
- 符号：GameMomoApplication, main
- Commit：f7e0e22bec3068a45636ec2985e21abc1975c3e5
- 核验状态：VERIFIED

> Git f7e0e22bec30 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/GameMomoApplication.java；识别到结构符号：GameMomoApplication, main。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/bean/funcUnlock/FunctionType.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.1
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/bean/funcUnlock/FunctionType.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/bean/funcUnlock/FunctionType.java
- 符号：FunctionType, getConfig, of, getByConfig, getBySubFunction
- Commit：f7e0e22bec3068a45636ec2985e21abc1975c3e5
- 核验状态：VERIFIED

> Git f7e0e22bec30 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/bean/funcUnlock/FunctionType.java；识别到结构符号：FunctionType, getConfig, of, getByConfig, getBySubFunction。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/bean/money/MoneyType.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.1
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/bean/money/MoneyType.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/bean/money/MoneyType.java
- 符号：MoneyType, get, contains, canRemoveEmpty, isJade
- Commit：f7e0e22bec3068a45636ec2985e21abc1975c3e5
- 核验状态：VERIFIED

> Git f7e0e22bec30 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/bean/money/MoneyType.java；识别到结构符号：MoneyType, get, contains, canRemoveEmpty, isJade。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/constant/ResultEnum.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.1
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/constant/ResultEnum.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/constant/ResultEnum.java
- 符号：ResultEnum, get, getEc, getEm
- Commit：f7e0e22bec3068a45636ec2985e21abc1975c3e5
- 核验状态：VERIFIED

> Git f7e0e22bec30 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/constant/ResultEnum.java；识别到结构符号：ResultEnum, get, getEc, getEm。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/redis/HistoryDao.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.1
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/redis/HistoryDao.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/redis/HistoryDao.java
- 符号：HistoryDao, addHistory, addHistories, getHistory, getLastHistory, addQueryHistoryTime, getQueryHistoryTime, delHistory
- Commit：f7e0e22bec3068a45636ec2985e21abc1975c3e5
- 核验状态：VERIFIED

> Git f7e0e22bec30 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/redis/HistoryDao.java；识别到结构符号：HistoryDao, addHistory, addHistories, getHistory, getLastHistory, addQueryHistoryTime, getQueryHistoryTime, delHistory。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/redis/UnionDao.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.1
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/redis/UnionDao.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/redis/UnionDao.java
- 符号：UnionDao, getUserUnionInfo, UserUnionDO, getUserUnionInfoFromMaster, batchQueryUserUnionIdMap, exception, applyReduce, query
- Commit：f7e0e22bec3068a45636ec2985e21abc1975c3e5
- 核验状态：VERIFIED

> Git f7e0e22bec30 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/redis/UnionDao.java；识别到结构符号：UnionDao, getUserUnionInfo, UserUnionDO, getUserUnionInfoFromMaster, batchQueryUserUnionIdMap, exception, applyReduce, query。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/redis/activity/CrazyDigDao.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.1
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/redis/activity/CrazyDigDao.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/redis/activity/CrazyDigDao.java
- 符号：CrazyDigDao, addDigNum, getDigNumMap, incExchangeLevel, getExchangeLevel, getPackRechargeInfo, addPackRechargeInfo, addAchStep
- Commit：f7e0e22bec3068a45636ec2985e21abc1975c3e5
- 核验状态：VERIFIED

> Git f7e0e22bec30 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/redis/activity/CrazyDigDao.java；识别到结构符号：CrazyDigDao, addDigNum, getDigNumMap, incExchangeLevel, getExchangeLevel, getPackRechargeInfo, addPackRechargeInfo, addAchStep。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/rediskey/UnionCacheKeyUtils.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.1
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/rediskey/UnionCacheKeyUtils.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/rediskey/UnionCacheKeyUtils.java
- 符号：UnionCacheKeyUtils, getUserUnionInfoKey, getUserUnionApplyKey, getUnionUserApplyKey, getUnionUserListKey, getUnionTotalContributionKey, getUnionDayContributionKey, getUnionMoneyKey
- Commit：f7e0e22bec3068a45636ec2985e21abc1975c3e5
- 核验状态：VERIFIED

> Git f7e0e22bec30 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/rediskey/UnionCacheKeyUtils.java；识别到结构符号：UnionCacheKeyUtils, getUserUnionInfoKey, getUserUnionApplyKey, getUnionUserApplyKey, getUnionUserListKey, getUnionTotalContributionKey, getUnionDayContributionKey, getUnionMoneyKey。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/rediskey/activity/CrazyDigCacheKeyUtils.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.1
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/rediskey/activity/CrazyDigCacheKeyUtils.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/rediskey/activity/CrazyDigCacheKeyUtils.java
- 符号：CrazyDigCacheKeyUtils, getDigKey, getDigExchangeKey, getDigPackKey, getDigAchKey
- Commit：f7e0e22bec3068a45636ec2985e21abc1975c3e5
- 核验状态：VERIFIED

> Git f7e0e22bec30 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/rediskey/activity/CrazyDigCacheKeyUtils.java；识别到结构符号：CrazyDigCacheKeyUtils, getDigKey, getDigExchangeKey, getDigPackKey, getDigAchKey。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/enums/AddSource.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.1
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/enums/AddSource.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/enums/AddSource.java
- 符号：AddSource
- Commit：f7e0e22bec3068a45636ec2985e21abc1975c3e5
- 核验状态：VERIFIED

> Git f7e0e22bec30 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/enums/AddSource.java；识别到结构符号：AddSource。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/enums/HistoryType.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.1
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/enums/HistoryType.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/enums/HistoryType.java
- 符号：HistoryType, getMaxNum, getType, getByType, getRedisKeyExtraInfo
- Commit：f7e0e22bec3068a45636ec2985e21abc1975c3e5
- 核验状态：VERIFIED

> Git f7e0e22bec30 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/enums/HistoryType.java；识别到结构符号：HistoryType, getMaxNum, getType, getByType, getRedisKeyExtraInfo。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/enums/RechargeType.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.1
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/enums/RechargeType.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/enums/RechargeType.java
- 符号：RechargeType, getByType, getTimeUnitStr
- Commit：f7e0e22bec3068a45636ec2985e21abc1975c3e5
- 核验状态：VERIFIED

> Git f7e0e22bec30 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/enums/RechargeType.java；识别到结构符号：RechargeType, getByType, getTimeUnitStr。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/enums/Reminder.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.1
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/enums/Reminder.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/enums/Reminder.java
- 符号：Reminder, of
- Commit：f7e0e22bec3068a45636ec2985e21abc1975c3e5
- 核验状态：VERIFIED

> Git f7e0e22bec30 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/enums/Reminder.java；识别到结构符号：Reminder, of。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/enums/RemoveSource.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.1
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/enums/RemoveSource.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/enums/RemoveSource.java
- 符号：RemoveSource
- Commit：f7e0e22bec3068a45636ec2985e21abc1975c3e5
- 核验状态：VERIFIED

> Git f7e0e22bec30 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/enums/RemoveSource.java；识别到结构符号：RemoveSource。

