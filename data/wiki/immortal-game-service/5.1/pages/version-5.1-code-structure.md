---
featureId: "version-5.1-code-structure"
projectId: "immortal-game-service"
version: "5.1"
pageType: FEATURE
status: CODE_VERIFIED
codeCommit: "f7e0e22bec3068a45636ec2985e21abc1975c3e5"
generatedAt: "2026-07-24T00:00:00+08:00"
---

# 5.1 代码结构与变更

这是由 Git 自动生成的版本代码证据页。比较基线 836abbd7f805 与目标 f7e0e22bec30；共纳入 78 个受控文件。

## 产品视角

- 本页不生成未经需求原文核验的产品规则。

## 开发视角

- Git 代码边界：836abbd7f805 → f7e0e22bec30。
- 本版本受控识别 78 个代码/配置文件，其中 Java/Kotlin 75 个、测试文件 0 个、配置文件 3 个。
- 提交说明：Merge branch 'V5.1.0' into 'master'。 提交时间：2026-07-16T14:18:40+00:00。
- ADDED: immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/request/union/WarGoodsRecordParam.java
- ADDED: immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/response/growFund/GrowFundIndexResp.java
- ADDED: immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/response/union/WarGoodsRecordNode.java
- ADDED: immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/response/union/WarGoodsRecordResp.java
- ADDED: immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IGrowFundMoaService.java
- ADDED: immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/request/world/PullFirstFullGameInfoParam.java
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
- MODIFIED: immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/IImmortalMoaService.java
- MODIFIED: immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/request/world/BattlefieldShowParam.java
- MODIFIED: immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/response/compact/ResCompactHallIndex.java
- MODIFIED: immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/service/IWorldHistoryMoaService.java
- MODIFIED: immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/service/IWorldMessageMoaService.java
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
- MODIFIED: immortal-game-service-api/pom.xml
- MODIFIED: immortal-game-service-impl/pom.xml
- MODIFIED: pom.xml
- DELETED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/FullSyncHandler.java
- immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/request/union/WarGoodsRecordParam.java :: WarGoodsRecordParam
- immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/response/growFund/GrowFundIndexResp.java :: GrowFundIndexResp
- immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/response/union/WarGoodsRecordNode.java :: WarGoodsRecordNode
- immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/response/union/WarGoodsRecordResp.java :: WarGoodsRecordResp
- immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IGrowFundMoaService.java :: IGrowFundMoaService
- immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/request/world/PullFirstFullGameInfoParam.java :: PullFirstFullGameInfoParam, isInvalid
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
- immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/IImmortalMoaService.java :: IImmortalMoaService
- immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/request/world/BattlefieldShowParam.java :: BattlefieldShowParam
- immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/response/compact/ResCompactHallIndex.java :: ResCompactHallIndex
- immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/service/IWorldHistoryMoaService.java :: IWorldHistoryMoaService, worldFightHistory, worldRewardsHistory
- immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/service/IWorldMessageMoaService.java :: IWorldMessageMoaService
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

## 测试视角

- 没有真实执行快照。
- 静态识别 0 个测试文件，需在发布流程关联执行报告。

## 风险与存疑

- 代码路径和结构证据不能证明运行时行为。
- 文件列表按安全上限截断，完整源码仍以 Git commit 为准。

## 原始证据

### 5.1 代码版本边界

- 类型：GIT
- 来源：immortal-game-service
- 版本：5.1
- 位置：836abbd7f805 到 f7e0e22bec30
- Commit：f7e0e22bec3068a45636ec2985e21abc1975c3e5
- 核验状态：VERIFIED

> 836abbd7f805 到 f7e0e22bec30；提交说明：Merge branch 'V5.1.0' into 'master'；纳入 78 个受控代码/配置文件。

### 代码文件：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/request/union/WarGoodsRecordParam.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.1
- 位置：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/request/union/WarGoodsRecordParam.java
- 文件：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/request/union/WarGoodsRecordParam.java
- 符号：WarGoodsRecordParam
- Commit：f7e0e22bec3068a45636ec2985e21abc1975c3e5
- 核验状态：VERIFIED

> Git f7e0e22bec30 的版本证据：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/request/union/WarGoodsRecordParam.java；识别到结构符号：WarGoodsRecordParam。

### 代码文件：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/response/growFund/GrowFundIndexResp.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.1
- 位置：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/response/growFund/GrowFundIndexResp.java
- 文件：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/response/growFund/GrowFundIndexResp.java
- 符号：GrowFundIndexResp
- Commit：f7e0e22bec3068a45636ec2985e21abc1975c3e5
- 核验状态：VERIFIED

> Git f7e0e22bec30 的版本证据：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/response/growFund/GrowFundIndexResp.java；识别到结构符号：GrowFundIndexResp。

### 代码文件：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/response/union/WarGoodsRecordNode.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.1
- 位置：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/response/union/WarGoodsRecordNode.java
- 文件：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/response/union/WarGoodsRecordNode.java
- 符号：WarGoodsRecordNode
- Commit：f7e0e22bec3068a45636ec2985e21abc1975c3e5
- 核验状态：VERIFIED

> Git f7e0e22bec30 的版本证据：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/response/union/WarGoodsRecordNode.java；识别到结构符号：WarGoodsRecordNode。

### 代码文件：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/response/union/WarGoodsRecordResp.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.1
- 位置：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/response/union/WarGoodsRecordResp.java
- 文件：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/response/union/WarGoodsRecordResp.java
- 符号：WarGoodsRecordResp
- Commit：f7e0e22bec3068a45636ec2985e21abc1975c3e5
- 核验状态：VERIFIED

> Git f7e0e22bec30 的版本证据：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/response/union/WarGoodsRecordResp.java；识别到结构符号：WarGoodsRecordResp。

### 代码文件：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IGrowFundMoaService.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.1
- 位置：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IGrowFundMoaService.java
- 文件：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IGrowFundMoaService.java
- 符号：IGrowFundMoaService
- Commit：f7e0e22bec3068a45636ec2985e21abc1975c3e5
- 核验状态：VERIFIED

> Git f7e0e22bec30 的版本证据：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IGrowFundMoaService.java；识别到结构符号：IGrowFundMoaService。

### 代码文件：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/request/world/PullFirstFullGameInfoParam.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.1
- 位置：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/request/world/PullFirstFullGameInfoParam.java
- 文件：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/request/world/PullFirstFullGameInfoParam.java
- 符号：PullFirstFullGameInfoParam, isInvalid
- Commit：f7e0e22bec3068a45636ec2985e21abc1975c3e5
- 核验状态：VERIFIED

> Git f7e0e22bec30 的版本证据：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/request/world/PullFirstFullGameInfoParam.java；识别到结构符号：PullFirstFullGameInfoParam, isInvalid。

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

### 代码文件：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/IImmortalMoaService.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.1
- 位置：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/IImmortalMoaService.java
- 文件：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/IImmortalMoaService.java
- 符号：IImmortalMoaService
- Commit：f7e0e22bec3068a45636ec2985e21abc1975c3e5
- 核验状态：VERIFIED

> Git f7e0e22bec30 的版本证据：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/IImmortalMoaService.java；识别到结构符号：IImmortalMoaService。

### 代码文件：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/request/world/BattlefieldShowParam.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.1
- 位置：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/request/world/BattlefieldShowParam.java
- 文件：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/request/world/BattlefieldShowParam.java
- 符号：BattlefieldShowParam
- Commit：f7e0e22bec3068a45636ec2985e21abc1975c3e5
- 核验状态：VERIFIED

> Git f7e0e22bec30 的版本证据：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/request/world/BattlefieldShowParam.java；识别到结构符号：BattlefieldShowParam。

### 代码文件：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/response/compact/ResCompactHallIndex.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.1
- 位置：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/response/compact/ResCompactHallIndex.java
- 文件：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/response/compact/ResCompactHallIndex.java
- 符号：ResCompactHallIndex
- Commit：f7e0e22bec3068a45636ec2985e21abc1975c3e5
- 核验状态：VERIFIED

> Git f7e0e22bec30 的版本证据：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/response/compact/ResCompactHallIndex.java；识别到结构符号：ResCompactHallIndex。

### 代码文件：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/service/IWorldHistoryMoaService.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.1
- 位置：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/service/IWorldHistoryMoaService.java
- 文件：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/service/IWorldHistoryMoaService.java
- 符号：IWorldHistoryMoaService, worldFightHistory, worldRewardsHistory
- Commit：f7e0e22bec3068a45636ec2985e21abc1975c3e5
- 核验状态：VERIFIED

> Git f7e0e22bec30 的版本证据：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/service/IWorldHistoryMoaService.java；识别到结构符号：IWorldHistoryMoaService, worldFightHistory, worldRewardsHistory。

### 代码文件：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/service/IWorldMessageMoaService.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.1
- 位置：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/service/IWorldMessageMoaService.java
- 文件：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/service/IWorldMessageMoaService.java
- 符号：IWorldMessageMoaService
- Commit：f7e0e22bec3068a45636ec2985e21abc1975c3e5
- 核验状态：VERIFIED

> Git f7e0e22bec30 的版本证据：immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/service/IWorldMessageMoaService.java；识别到结构符号：IWorldMessageMoaService。

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

