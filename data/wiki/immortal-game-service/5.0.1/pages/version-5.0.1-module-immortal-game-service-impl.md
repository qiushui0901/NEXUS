---
featureId: "version-5.0.1-module-immortal-game-service-impl"
projectId: "immortal-game-service"
version: "5.0.1"
pageType: FEATURE
status: CODE_VERIFIED
codeCommit: "6b7a154851c6f7979d58c89485eb28899687a234"
generatedAt: "2026-07-24T00:00:00+08:00"
---

# 5.0.1 · immortal-game-service-impl 模块

Git 版本 5.0.1 中，模块 immortal-game-service-impl 受控识别 18 个文件；内容来自 commit 6b7a154851c6 的路径和结构扫描。

## 产品视角

- 没有关联需求原文，因此不把类名解释为产品规则。

## 开发视角

- ADDED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/message/s2c/RoomConsumer.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/aspect/ContextAspect.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/redis/RankDao.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/common/LoggerModel.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/fight/FightSkillManager.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/utils/AsyncExecPoolUtils.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/utils/LogTestEnvUtils.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/constant/WorldConstants.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/dao/redis/LotteryDao.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/dao/redis/WorldRankDao.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/enums/hubble/Title.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/lottery/LotteryModel.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/message/s2c/BatchMsgConsumer.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/message/s2c/MessageListProcessor.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/message/s2c/VersionMessageManager.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/message/s2c/VersionRoomMessageProcessor.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/march/MarchPluginCommon.java
- MODIFIED: immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/stage/StageGlobalService.java
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/message/s2c/RoomConsumer.java :: RoomConsumer, start, stop, offer, queueSize, run, buildEventCodes
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/aspect/ContextAspect.java :: ContextAspect, doAspect, doAfter, doBefore, doException, parseExpression, createParameterContext, createMethodContext
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/redis/RankDao.java :: RankDao, init, setRankList, addRankList, deductRankScore, updateRankList, updateRankTime, getRankList
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/common/LoggerModel.java :: LoggerModel, sendLog
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/fight/FightSkillManager.java :: FightSkillManager, isSkillRunByCondition, choosePassiveSkill, chooseSkill, getEffectIdList, getSkillRepeatTimes, getEffectRepeatTimes, isRunSubSkill
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/utils/AsyncExecPoolUtils.java :: AsyncExecPoolUtils, execute, executeNotServerLogic, submit, RuntimeException
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/utils/LogTestEnvUtils.java :: LogTestEnvUtils, info, error
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/constant/WorldConstants.java :: WorldConstants
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/dao/redis/LotteryDao.java :: LotteryDao, addRewardBox, deductRewardBox, getRewardBox
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/dao/redis/WorldRankDao.java :: WorldRankDao, init, addUnionFightUser, delUnionFightUser, getUnionFightUser, isFightUser, getUnionScore, getUnionScoreBatch
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/enums/hubble/Title.java :: Title, EVENT_MSG_QUEUE_CONSUMER_TIME, getTitle, getAction
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/lottery/LotteryModel.java :: LotteryModel, init, lotteryIndex, buildWheel, buildNextReward, queryShowItemNode, buildWheelBo, isDefalutBox
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/message/s2c/BatchMsgConsumer.java :: BatchMsgConsumer, init, stop, registerRoom, unregisterRoom, add, dispatch
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/message/s2c/MessageListProcessor.java :: MessageListProcessor, buildMsgList, buildEmptyMsgList, convertMsgPack, recordFullSyncPartPbLength
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/message/s2c/VersionMessageManager.java :: VersionMessageManager, getVersionMessage, clear
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/message/s2c/VersionRoomMessageProcessor.java :: VersionRoomMessageProcessor, sendVersionMessage, sendPartRoomMsg, sendFirstFullGameInfo, doSendFirstFullGameInfo, sendFullRoomMsg
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/march/MarchPluginCommon.java :: MarchPluginCommon, reGenPlugin, getStageWorldConfig, canAtkMarch, atkMarch, teamAtkMarch, doAtkMarch, canDefMarch
- immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/stage/StageGlobalService.java :: StageGlobalService, init, getSV, addCSMsg, createStage, getStage, destructStage, __resetStageTest

## 测试视角

- 没有真实执行快照。
- 该模块静态识别测试文件 0 个。

## 风险与存疑

- 自动扫描只保留文件路径和有限结构符号，不复制源码正文。

## 原始证据

### 5.0.1 代码版本边界

- 类型：GIT
- 来源：immortal-game-service
- 版本：5.0.1
- 位置：099915074ce7 到 6b7a154851c6
- Commit：6b7a154851c6f7979d58c89485eb28899687a234
- 核验状态：VERIFIED

> 099915074ce7 到 6b7a154851c6；提交说明：V5.0.1；纳入 18 个受控代码/配置文件。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/message/s2c/RoomConsumer.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0.1
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/message/s2c/RoomConsumer.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/message/s2c/RoomConsumer.java
- 符号：RoomConsumer, start, stop, offer, queueSize, run, buildEventCodes
- Commit：6b7a154851c6f7979d58c89485eb28899687a234
- 核验状态：VERIFIED

> Git 6b7a154851c6 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/message/s2c/RoomConsumer.java；识别到结构符号：RoomConsumer, start, stop, offer, queueSize, run, buildEventCodes。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/aspect/ContextAspect.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0.1
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/aspect/ContextAspect.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/aspect/ContextAspect.java
- 符号：ContextAspect, doAspect, doAfter, doBefore, doException, parseExpression, createParameterContext, createMethodContext
- Commit：6b7a154851c6f7979d58c89485eb28899687a234
- 核验状态：VERIFIED

> Git 6b7a154851c6 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/aspect/ContextAspect.java；识别到结构符号：ContextAspect, doAspect, doAfter, doBefore, doException, parseExpression, createParameterContext, createMethodContext。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/redis/RankDao.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0.1
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/redis/RankDao.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/redis/RankDao.java
- 符号：RankDao, init, setRankList, addRankList, deductRankScore, updateRankList, updateRankTime, getRankList
- Commit：6b7a154851c6f7979d58c89485eb28899687a234
- 核验状态：VERIFIED

> Git 6b7a154851c6 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/redis/RankDao.java；识别到结构符号：RankDao, init, setRankList, addRankList, deductRankScore, updateRankList, updateRankTime, getRankList。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/common/LoggerModel.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0.1
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/common/LoggerModel.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/common/LoggerModel.java
- 符号：LoggerModel, sendLog
- Commit：6b7a154851c6f7979d58c89485eb28899687a234
- 核验状态：VERIFIED

> Git 6b7a154851c6 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/common/LoggerModel.java；识别到结构符号：LoggerModel, sendLog。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/fight/FightSkillManager.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0.1
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/fight/FightSkillManager.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/fight/FightSkillManager.java
- 符号：FightSkillManager, isSkillRunByCondition, choosePassiveSkill, chooseSkill, getEffectIdList, getSkillRepeatTimes, getEffectRepeatTimes, isRunSubSkill
- Commit：6b7a154851c6f7979d58c89485eb28899687a234
- 核验状态：VERIFIED

> Git 6b7a154851c6 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/fight/FightSkillManager.java；识别到结构符号：FightSkillManager, isSkillRunByCondition, choosePassiveSkill, chooseSkill, getEffectIdList, getSkillRepeatTimes, getEffectRepeatTimes, isRunSubSkill。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/utils/AsyncExecPoolUtils.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0.1
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/utils/AsyncExecPoolUtils.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/utils/AsyncExecPoolUtils.java
- 符号：AsyncExecPoolUtils, execute, executeNotServerLogic, submit, RuntimeException
- Commit：6b7a154851c6f7979d58c89485eb28899687a234
- 核验状态：VERIFIED

> Git 6b7a154851c6 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/utils/AsyncExecPoolUtils.java；识别到结构符号：AsyncExecPoolUtils, execute, executeNotServerLogic, submit, RuntimeException。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/utils/LogTestEnvUtils.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0.1
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/utils/LogTestEnvUtils.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/utils/LogTestEnvUtils.java
- 符号：LogTestEnvUtils, info, error
- Commit：6b7a154851c6f7979d58c89485eb28899687a234
- 核验状态：VERIFIED

> Git 6b7a154851c6 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/utils/LogTestEnvUtils.java；识别到结构符号：LogTestEnvUtils, info, error。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/constant/WorldConstants.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0.1
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/constant/WorldConstants.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/constant/WorldConstants.java
- 符号：WorldConstants
- Commit：6b7a154851c6f7979d58c89485eb28899687a234
- 核验状态：VERIFIED

> Git 6b7a154851c6 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/constant/WorldConstants.java；识别到结构符号：WorldConstants。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/dao/redis/LotteryDao.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0.1
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/dao/redis/LotteryDao.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/dao/redis/LotteryDao.java
- 符号：LotteryDao, addRewardBox, deductRewardBox, getRewardBox
- Commit：6b7a154851c6f7979d58c89485eb28899687a234
- 核验状态：VERIFIED

> Git 6b7a154851c6 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/dao/redis/LotteryDao.java；识别到结构符号：LotteryDao, addRewardBox, deductRewardBox, getRewardBox。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/dao/redis/WorldRankDao.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0.1
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/dao/redis/WorldRankDao.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/dao/redis/WorldRankDao.java
- 符号：WorldRankDao, init, addUnionFightUser, delUnionFightUser, getUnionFightUser, isFightUser, getUnionScore, getUnionScoreBatch
- Commit：6b7a154851c6f7979d58c89485eb28899687a234
- 核验状态：VERIFIED

> Git 6b7a154851c6 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/dao/redis/WorldRankDao.java；识别到结构符号：WorldRankDao, init, addUnionFightUser, delUnionFightUser, getUnionFightUser, isFightUser, getUnionScore, getUnionScoreBatch。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/enums/hubble/Title.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0.1
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/enums/hubble/Title.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/enums/hubble/Title.java
- 符号：Title, EVENT_MSG_QUEUE_CONSUMER_TIME, getTitle, getAction
- Commit：6b7a154851c6f7979d58c89485eb28899687a234
- 核验状态：VERIFIED

> Git 6b7a154851c6 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/enums/hubble/Title.java；识别到结构符号：Title, EVENT_MSG_QUEUE_CONSUMER_TIME, getTitle, getAction。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/lottery/LotteryModel.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0.1
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/lottery/LotteryModel.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/lottery/LotteryModel.java
- 符号：LotteryModel, init, lotteryIndex, buildWheel, buildNextReward, queryShowItemNode, buildWheelBo, isDefalutBox
- Commit：6b7a154851c6f7979d58c89485eb28899687a234
- 核验状态：VERIFIED

> Git 6b7a154851c6 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/lottery/LotteryModel.java；识别到结构符号：LotteryModel, init, lotteryIndex, buildWheel, buildNextReward, queryShowItemNode, buildWheelBo, isDefalutBox。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/message/s2c/BatchMsgConsumer.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0.1
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/message/s2c/BatchMsgConsumer.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/message/s2c/BatchMsgConsumer.java
- 符号：BatchMsgConsumer, init, stop, registerRoom, unregisterRoom, add, dispatch
- Commit：6b7a154851c6f7979d58c89485eb28899687a234
- 核验状态：VERIFIED

> Git 6b7a154851c6 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/message/s2c/BatchMsgConsumer.java；识别到结构符号：BatchMsgConsumer, init, stop, registerRoom, unregisterRoom, add, dispatch。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/message/s2c/MessageListProcessor.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0.1
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/message/s2c/MessageListProcessor.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/message/s2c/MessageListProcessor.java
- 符号：MessageListProcessor, buildMsgList, buildEmptyMsgList, convertMsgPack, recordFullSyncPartPbLength
- Commit：6b7a154851c6f7979d58c89485eb28899687a234
- 核验状态：VERIFIED

> Git 6b7a154851c6 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/message/s2c/MessageListProcessor.java；识别到结构符号：MessageListProcessor, buildMsgList, buildEmptyMsgList, convertMsgPack, recordFullSyncPartPbLength。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/message/s2c/VersionMessageManager.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0.1
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/message/s2c/VersionMessageManager.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/message/s2c/VersionMessageManager.java
- 符号：VersionMessageManager, getVersionMessage, clear
- Commit：6b7a154851c6f7979d58c89485eb28899687a234
- 核验状态：VERIFIED

> Git 6b7a154851c6 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/message/s2c/VersionMessageManager.java；识别到结构符号：VersionMessageManager, getVersionMessage, clear。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/message/s2c/VersionRoomMessageProcessor.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0.1
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/message/s2c/VersionRoomMessageProcessor.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/message/s2c/VersionRoomMessageProcessor.java
- 符号：VersionRoomMessageProcessor, sendVersionMessage, sendPartRoomMsg, sendFirstFullGameInfo, doSendFirstFullGameInfo, sendFullRoomMsg
- Commit：6b7a154851c6f7979d58c89485eb28899687a234
- 核验状态：VERIFIED

> Git 6b7a154851c6 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/message/s2c/VersionRoomMessageProcessor.java；识别到结构符号：VersionRoomMessageProcessor, sendVersionMessage, sendPartRoomMsg, sendFirstFullGameInfo, doSendFirstFullGameInfo, sendFullRoomMsg。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/march/MarchPluginCommon.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0.1
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/march/MarchPluginCommon.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/march/MarchPluginCommon.java
- 符号：MarchPluginCommon, reGenPlugin, getStageWorldConfig, canAtkMarch, atkMarch, teamAtkMarch, doAtkMarch, canDefMarch
- Commit：6b7a154851c6f7979d58c89485eb28899687a234
- 核验状态：VERIFIED

> Git 6b7a154851c6 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/march/MarchPluginCommon.java；识别到结构符号：MarchPluginCommon, reGenPlugin, getStageWorldConfig, canAtkMarch, atkMarch, teamAtkMarch, doAtkMarch, canDefMarch。

### 代码文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/stage/StageGlobalService.java

- 类型：CODE
- 来源：immortal-game-service
- 版本：5.0.1
- 位置：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/stage/StageGlobalService.java
- 文件：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/stage/StageGlobalService.java
- 符号：StageGlobalService, init, getSV, addCSMsg, createStage, getStage, destructStage, __resetStageTest
- Commit：6b7a154851c6f7979d58c89485eb28899687a234
- 核验状态：VERIFIED

> Git 6b7a154851c6 的版本证据：immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/stage/StageGlobalService.java；识别到结构符号：StageGlobalService, init, getSV, addCSMsg, createStage, getStage, destructStage, __resetStageTest。

