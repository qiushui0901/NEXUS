# 封神代码召回评估报告 · fengshen-code-retrieval-eval-500

> 评估对象：500 道代码召回题（`fengshen-code-retrieval-eval-500.md / .jsonl`）
> 检索方式：**仅依靠自身搜索（本地词法检索，未调用任何向量库 / RAG 服务 / 外部 API）**
> 代码库：`/Users/user/Documents/immortal/immortal-game-service`（2139 个 `.java` 文件，约 16 MB）
> 索引构建 0.86s，500 题检索总耗时 0.070s

---

## 一、总体指标（两套检索配置对比）

> **配置B（纯符号检索）为主口径**：它有真实的 R@1<R@5<R@10 上升曲线，反映排序难度；配置A（类名增强）为上界，因 query 自带类名而趋近 oracle，曲线变平。

| 指标 | 配置A 类名增强 | **配置B 纯符号检索（主）** |
|---|---:|---:|
| Recall@1（符号+文件） | 82.00% | **63.20%** |
| Recall@5（符号+文件） | 82.00% | **72.40%** |
| Recall@10（符号+文件） | 82.00% | **73.20%** |
| MRR@10 | 82.00% | **67.58%** |
| nDCG@10 | 82.00% | **69.00%** |
| 文件命中率（Top-10） | 100.00% | 73.20% |
| 解析失败率 | 18.00% | 18.00% |
| 真正 no-result 比例 | 0.00% | 0.00% |
| 延迟 P50 / P95 / P99 (ms) | 0.1409 / 0.1912 / 0.2667 | 0.1524 / 0.2328 / 0.4063 |

## 二、为什么 Recall@1/@5/@10 在配置A 中没有提升（平成 82%）

配置A 的结果呈**二元分布**，没有任何一题 Gold 落在 rank 2~10：
- **410 题符号显式题**：query 直接给了 `Class.method`，配置A 用「类名 basename 精确匹配 +50」这一**近似 oracle 的强信号**叠加「定义命中 +100」→ Gold **必然 rank 1**，所以 @1=@5=@10 全命中。
- **90 题纯中文描述题**：抽不出方法符号 → rank=0 → @1/@5/@10 全不命中。
- 两类之间没有“Gold 在 rank 3/7”的中间态，故曲线被压平。

**这正是类名 oracle 把排序难度消解掉的副作用**——Recall@1 vs @5 vs @10 本该衡量“Gold 进了 Top-10 但不在第 1 名”的比例，配置A 把这部分清零了。

**配置B（去掉类名 oracle，纯按方法符号检索）恢复真实梯度**：高频方法名（`init`/`refresh`/`canUse`/`modify` 等在几十到上百个文件里都有定义）会让多个文件并列“定义命中”，纯符号检索无法分辨哪个是 Gold，Gold 因此落在 rank 2~10 甚至 Top-10 之外，于是 R@1 < R@5 < R@10。

### 配置B 的 Gold 排名分布（Top-10 内）

| Gold 排名 | 题数 |
|---:|---:|
| 1 | 316 |
| 2 | 38 |
| 3 | 6 |
| 5 | 2 |
| 8 | 4 |
| 未进 Top-10 / 解析失败 | 134 |

---

## 三、分 queryMode 指标（配置B 纯符号检索）

| queryMode | 题数 | 解析失败 | R@1 | R@5 | R@10 | MRR@10 |
|---|---:|---:|---:|---:|---:|---:|
| BUSINESS_TERM | 125 | 0 | 76.8% | 90.4% | 91.2% | 83.2% |
| REQUIREMENT_TO_CODE | 125 | 45 | 49.6% | 54.4% | 55.2% | 52.0% |
| SYMBOL | 125 | 0 | 76.8% | 90.4% | 91.2% | 83.2% |
| BEHAVIOR | 125 | 45 | 49.6% | 54.4% | 55.2% | 52.0% |

- **BUSINESS_TERM / SYMBOL**：query 显式给 `Class.method`，纯符号检索 R@1≈76.8%、R@10≈91.2%，梯度来自高频方法名（同符号多文件定义）。
- **REQUIREMENT_TO_CODE / BEHAVIOR**：含 45 题纯中文描述变体（无方法符号 → 全 miss）拖低均值；显式 `执行 methodName` 变体与上两类一致。

---

## 四、评估方法（自检索，无外部服务）

1. **构建本地词法索引**：遍历代码库 2139 个 `.java` 文件，每文件记录路径、基准类名、内部类集合、方法定义集合（识别 `public/private/protected … name(` 及 `@Override` 后默认可见性方法）、token 计数。耗时 0.86s。
2. **Query 解析（不偷看 Gold）**：`Class.method` 主模式；类名 `在 X 中`/`召回 X 的`/最长 MixedCase；方法符号 `执行 methodName` 显式形态。**纯中文描述型抽不出 ASCII 方法符号 → 解析失败**。
3. **配置B 检索排序（主口径，每题真实计时）**：候选=含该符号的文件；打分=`定义命中 +200`、`+min(提及次数,50)`；**不使用类名信号**；取 Top-10。
4. **配置A 检索排序（上界对比）**：在配置B 基础上叠加`基准类名==查询类名 +50`、`类名出现在内部类 +20`。
5. **判定**：`符号+文件命中` = Top-k 中存在 Gold 文件且以方法定义形态出现 Gold 符号；`文件命中` = Gold 文件入 Top-k。

---

## 五、延迟分布（配置B 单题 = 索引扫描+排序+Top-10）

| 统计量 | 值(ms) |
|---|---:|
| P50 | 0.1524 |
| P95 | 0.2328 |
| P99 | 0.4063 |
| mean | 0.1653 |
| max | 0.9696 |

> 一次性索引构建 0.86s；500 题检索总墙钟 0.068s。延迟为内存词法索引上的扫描+排序耗时（标准检索评估口径：单题延迟不含建索引）。

---

## 六、逐题召回结果（500 题，配置B 纯符号检索，Top-10）

格式：`排名. [得分] 路径 (定义?/类名:提及次数)`；`★★符号+文件命中` 标注 Gold。Gold 排名=1 表示精准命中，2~10 表示进了 Top-10 但非第一（高频符号歧义），— 表示未进 Top-10/解析失败。


### fengshen-code-001  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=queryVipShopIndex，Gold=VipMoaServiceImpl#queryVipShopIndex
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/VipMoaServiceImpl.java` (def=True, cls=VipMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IVipMoaService.java` (def=False, cls=IVipMoaService, n=1)

### fengshen-code-002  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=vipUsePrivilege，Gold=VipMoaServiceImpl#vipUsePrivilege
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/VipMoaServiceImpl.java` (def=True, cls=VipMoaServiceImpl, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/vip/VipService.java` (def=True, cls=VipService, n=1)
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IVipMoaService.java` (def=False, cls=IVipMoaService, n=1)

### fengshen-code-003  [BUSINESS_TERM]  Gold排名=2
- 解析：方法符号=vipReceiveGift，Gold=VipService#vipReceiveGift
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/VipMoaServiceImpl.java` (def=True, cls=VipMoaServiceImpl, n=2)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/vip/VipService.java` (def=True, cls=VipService, n=1) ★★符号+文件命中
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IVipMoaService.java` (def=False, cls=IVipMoaService, n=1)

### fengshen-code-004  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=arenaMatch，Gold=ArenaMoaServiceImpl#arenaMatch
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/ArenaMoaServiceImpl.java` (def=True, cls=ArenaMoaServiceImpl, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/arena/ArenaModel.java` (def=True, cls=ArenaModel, n=1)
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IArenaMoaService.java` (def=False, cls=IArenaMoaService, n=1)

### fengshen-code-005  [BUSINESS_TERM]  Gold排名=—
- 解析：方法符号=handle，Gold=BuffActivateHandler#handle
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/HeroResonanceIndexHandler.java` (def=True, cls=HeroResonanceIndexHandler, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainHandler.java` (def=True, cls=QuickTrainHandler, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainIndexHandler.java` (def=True, cls=QuickTrainIndexHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=True, cls=SoldierStandbyBuyMomoHandler, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyExchangeHandler.java` (def=True, cls=SoldierStandbyExchangeHandler, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamIndexHandler.java` (def=True, cls=TeamIndexHandler, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksIndexHandler.java` (def=True, cls=TeamLooksIndexHandler, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksStarUpgradeHandler.java` (def=True, cls=TeamLooksStarUpgradeHandler, n=1)
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUnlockHandler.java` (def=True, cls=TeamLooksUnlockHandler, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUseHandler.java` (def=True, cls=TeamLooksUseHandler, n=1)

### fengshen-code-006  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=messageDispatchProcess，Gold=C2SMessageService#messageDispatchProcess
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/C2SMessageService.java` (def=True, cls=C2SMessageService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/impl/WorldMessageMoaServiceImpl.java` (def=False, cls=WorldMessageMoaServiceImpl, n=1)

### fengshen-code-007  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=canChestExchange，Gold=FarmService#canChestExchange
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/farm/FarmService.java` (def=True, cls=FarmService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/FarmMoaServiceImpl.java` (def=False, cls=FarmMoaServiceImpl, n=1)

### fengshen-code-008  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=canDig，Gold=FarmService#canDig
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/farm/FarmService.java` (def=True, cls=FarmService, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/farm/FarmModel.java` (def=True, cls=FarmModel, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/FarmMoaServiceImpl.java` (def=False, cls=FarmMoaServiceImpl, n=1)

### fengshen-code-009  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=chestAccelerate，Gold=FarmService#chestAccelerate
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/farm/FarmService.java` (def=True, cls=FarmService, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/farm/FarmModel.java` (def=True, cls=FarmModel, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/FarmMoaServiceImpl.java` (def=False, cls=FarmMoaServiceImpl, n=1)

### fengshen-code-010  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=chestSteal，Gold=FarmService#chestSteal
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/farm/FarmService.java` (def=True, cls=FarmService, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/farm/FarmModel.java` (def=True, cls=FarmModel, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/FarmMoaServiceImpl.java` (def=False, cls=FarmMoaServiceImpl, n=1)

### fengshen-code-011  [BUSINESS_TERM]  Gold排名=2
- 解析：方法符号=levelUpgrade，Gold=FarmService#levelUpgrade
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/artifact/ArtifactService.java` (def=True, cls=ArtifactService, n=2)
  2. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/farm/FarmService.java` (def=True, cls=FarmService, n=2) ★★符号+文件命中
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/artifact/ArtifactModel.java` (def=True, cls=ArtifactModel, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/farm/FarmModel.java` (def=True, cls=FarmModel, n=1)
  5. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/ArtifactMoaServiceImpl.java` (def=False, cls=ArtifactMoaServiceImpl, n=1)
  6. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/FarmMoaServiceImpl.java` (def=False, cls=FarmMoaServiceImpl, n=1)

### fengshen-code-012  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=farmChestBatchReceive，Gold=FarmMoaServiceImpl#farmChestBatchReceive
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/FarmMoaServiceImpl.java` (def=True, cls=FarmMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IFarmMoaService.java` (def=False, cls=IFarmMoaService, n=1)

### fengshen-code-013  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=farmChestStealFirst，Gold=FarmMoaServiceImpl#farmChestStealFirst
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/FarmMoaServiceImpl.java` (def=True, cls=FarmMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IFarmMoaService.java` (def=False, cls=IFarmMoaService, n=1)

### fengshen-code-014  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=farmDigV2，Gold=FarmMoaServiceImpl#farmDigV2
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/FarmMoaServiceImpl.java` (def=True, cls=FarmMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IFarmMoaService.java` (def=False, cls=IFarmMoaService, n=1)

### fengshen-code-015  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=farmLevelUpgrade，Gold=FarmMoaServiceImpl#farmLevelUpgrade
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/FarmMoaServiceImpl.java` (def=True, cls=FarmMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IFarmMoaService.java` (def=False, cls=IFarmMoaService, n=1)

### fengshen-code-016  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=addMineRewardsHisWithType，Gold=HistoryPluginCommon#addMineRewardsHisWithType
- Top-10：
  1. [203] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/history/HistoryPluginCommon.java` (def=True, cls=HistoryPluginCommon, n=3) ★★符号+文件命中

### fengshen-code-017  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=handleFocus，Gold=HistoryPluginCommon#handleFocus
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/history/HistoryPluginCommon.java` (def=True, cls=HistoryPluginCommon, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/history/HistoryPluginRoutine.java` (def=False, cls=HistoryPluginRoutine, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/history/HistoryPluginWar.java` (def=False, cls=HistoryPluginWar, n=1)

### fengshen-code-018  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=handleSkillDestroyCity，Gold=HistoryPluginCommon#handleSkillDestroyCity
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/history/HistoryPluginCommon.java` (def=True, cls=HistoryPluginCommon, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/history/HistoryPluginRoutine.java` (def=False, cls=HistoryPluginRoutine, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/history/HistoryPluginWar.java` (def=False, cls=HistoryPluginWar, n=1)

### fengshen-code-019  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=receiveBoardReward，Gold=MainIndexMoaServiceImpl#receiveBoardReward
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/MainIndexMoaServiceImpl.java` (def=True, cls=MainIndexMoaServiceImpl, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/mainidex/MainIndexModel.java` (def=True, cls=MainIndexModel, n=1)
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IMainIndexMoaService.java` (def=False, cls=IMainIndexMoaService, n=1)

### fengshen-code-020  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=commandOfflineQuick，Gold=OfflineMoaServiceImpl#commandOfflineQuick
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/OfflineMoaServiceImpl.java` (def=True, cls=OfflineMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IOfflineMoaService.java` (def=False, cls=IOfflineMoaService, n=1)

### fengshen-code-021  [BUSINESS_TERM]  Gold排名=—
- 解析：方法符号=handle，Gold=PongHandler#handle
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/HeroResonanceIndexHandler.java` (def=True, cls=HeroResonanceIndexHandler, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainHandler.java` (def=True, cls=QuickTrainHandler, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainIndexHandler.java` (def=True, cls=QuickTrainIndexHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=True, cls=SoldierStandbyBuyMomoHandler, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyExchangeHandler.java` (def=True, cls=SoldierStandbyExchangeHandler, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamIndexHandler.java` (def=True, cls=TeamIndexHandler, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksIndexHandler.java` (def=True, cls=TeamLooksIndexHandler, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksStarUpgradeHandler.java` (def=True, cls=TeamLooksStarUpgradeHandler, n=1)
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUnlockHandler.java` (def=True, cls=TeamLooksUnlockHandler, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUseHandler.java` (def=True, cls=TeamLooksUseHandler, n=1)

### fengshen-code-022  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=sendMessageByRoomId，Gold=QchatMoaService#sendMessageByRoomId
- Top-10：
  1. [203] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/moa/QchatMoaService.java` (def=True, cls=QchatMoaService, n=3) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/message/QchatMessageModel.java` (def=False, cls=QchatMessageModel, n=1)

### fengshen-code-023  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=reGenGet，Gold=ReGenMoaServiceImpl#reGenGet
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/ReGenMoaServiceImpl.java` (def=True, cls=ReGenMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IReGenMoaService.java` (def=False, cls=IReGenMoaService, n=1)

### fengshen-code-024  [BUSINESS_TERM]  Gold排名=—
- 解析：方法符号=handle，Gold=RetryHandler#handle
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/HeroResonanceIndexHandler.java` (def=True, cls=HeroResonanceIndexHandler, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainHandler.java` (def=True, cls=QuickTrainHandler, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainIndexHandler.java` (def=True, cls=QuickTrainIndexHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=True, cls=SoldierStandbyBuyMomoHandler, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyExchangeHandler.java` (def=True, cls=SoldierStandbyExchangeHandler, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamIndexHandler.java` (def=True, cls=TeamIndexHandler, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksIndexHandler.java` (def=True, cls=TeamLooksIndexHandler, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksStarUpgradeHandler.java` (def=True, cls=TeamLooksStarUpgradeHandler, n=1)
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUnlockHandler.java` (def=True, cls=TeamLooksUnlockHandler, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUseHandler.java` (def=True, cls=TeamLooksUseHandler, n=1)

### fengshen-code-025  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=handleBuildOccupy，Gold=SkillPluginCommon#handleBuildOccupy
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/skill/SkillPluginCommon.java` (def=True, cls=SkillPluginCommon, n=2) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/skill/SkillPluginRoutine.java` (def=False, cls=SkillPluginRoutine, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/skill/SkillPluginWar.java` (def=False, cls=SkillPluginWar, n=1)

### fengshen-code-026  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=buyMomoSoldier，Gold=SoldierPluginCommon#buyMomoSoldier
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/soldier/SoldierPluginCommon.java` (def=True, cls=SoldierPluginCommon, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/soldier/ISoldierPlugin.java` (def=False, cls=ISoldierPlugin, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=False, cls=SoldierStandbyBuyMomoHandler, n=1)

### fengshen-code-027  [BUSINESS_TERM]  Gold排名=—
- 解析：方法符号=handle，Gold=StageCreateHandler#handle
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/HeroResonanceIndexHandler.java` (def=True, cls=HeroResonanceIndexHandler, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainHandler.java` (def=True, cls=QuickTrainHandler, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainIndexHandler.java` (def=True, cls=QuickTrainIndexHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=True, cls=SoldierStandbyBuyMomoHandler, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyExchangeHandler.java` (def=True, cls=SoldierStandbyExchangeHandler, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamIndexHandler.java` (def=True, cls=TeamIndexHandler, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksIndexHandler.java` (def=True, cls=TeamLooksIndexHandler, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksStarUpgradeHandler.java` (def=True, cls=TeamLooksStarUpgradeHandler, n=1)
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUnlockHandler.java` (def=True, cls=TeamLooksUnlockHandler, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUseHandler.java` (def=True, cls=TeamLooksUseHandler, n=1)

### fengshen-code-028  [BUSINESS_TERM]  Gold排名=2
- 解析：方法符号=init，Gold=StageGlobalService#init
- Top-10：
  1. [208] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/build/BuildPluginWar.java` (def=True, cls=BuildPluginWar, n=8)
  2. [204] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/stage/StageGlobalService.java` (def=True, cls=StageGlobalService, n=4) ★★符号+文件命中
  3. [203] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/bean/stage/StageWorldConfig.java` (def=True, cls=StageWorldConfig, n=3)
  4. [203] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/build/BuildPluginCommon.java` (def=True, cls=BuildPluginCommon, n=3)
  5. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/bean/limitPack/LimitPackContext.java` (def=True, cls=LimitPackContext, n=2)
  6. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/bean/vip/CommonRechargeContext.java` (def=True, cls=CommonRechargeContext, n=2)
  7. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/moa/DelayQueueMoaService.java` (def=True, cls=DelayQueueMoaService, n=2)
  8. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/hunyuan/HunyuanPackModel.java` (def=True, cls=HunyuanPackModel, n=2)
  9. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/growDiscount/GrowDiscountModel.java` (def=True, cls=GrowDiscountModel, n=2)
  10. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/bean/stage/StageWorld.java` (def=True, cls=StageWorld, n=2)

### fengshen-code-029  [BUSINESS_TERM]  Gold排名=8
- 解析：方法符号=handle，Gold=TeamLooksStarUpgradeHandler#handle
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/HeroResonanceIndexHandler.java` (def=True, cls=HeroResonanceIndexHandler, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainHandler.java` (def=True, cls=QuickTrainHandler, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainIndexHandler.java` (def=True, cls=QuickTrainIndexHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=True, cls=SoldierStandbyBuyMomoHandler, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyExchangeHandler.java` (def=True, cls=SoldierStandbyExchangeHandler, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamIndexHandler.java` (def=True, cls=TeamIndexHandler, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksIndexHandler.java` (def=True, cls=TeamLooksIndexHandler, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksStarUpgradeHandler.java` (def=True, cls=TeamLooksStarUpgradeHandler, n=1) ★★符号+文件命中
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUnlockHandler.java` (def=True, cls=TeamLooksUnlockHandler, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUseHandler.java` (def=True, cls=TeamLooksUseHandler, n=1)

### fengshen-code-030  [BUSINESS_TERM]  Gold排名=5
- 解析：方法符号=canUse，Gold=TeamLooksPluginCommon#canUse
- Top-10：
  1. [203] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/item/ItemManger.java` (def=True, cls=ItemManger, n=3)
  2. [203] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/item/ItemService.java` (def=True, cls=ItemService, n=3)
  3. [203] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/skill/SkillPluginCommon.java` (def=True, cls=SkillPluginCommon, n=3)
  4. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/roleLooks/RoleLooksService.java` (def=True, cls=RoleLooksService, n=2)
  5. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/teamlooks/TeamLooksPluginCommon.java` (def=True, cls=TeamLooksPluginCommon, n=2) ★★符号+文件命中
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/roleLooks/RoleLooksModel.java` (def=True, cls=RoleLooksModel, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/spirit/SpiritService.java` (def=True, cls=SpiritService, n=1)
  8. [2] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/common/TaskPrize.java` (def=False, cls=TaskPrize, n=2)
  9. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/ItemMoaServiceImpl.java` (def=False, cls=ItemMoaServiceImpl, n=1)
  10. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/RoleLooksMoaServiceImpl.java` (def=False, cls=RoleLooksMoaServiceImpl, n=1)

### fengshen-code-031  [BUSINESS_TERM]  Gold排名=—
- 解析：方法符号=handle，Gold=TeamRaidMarchHandler#handle
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/HeroResonanceIndexHandler.java` (def=True, cls=HeroResonanceIndexHandler, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainHandler.java` (def=True, cls=QuickTrainHandler, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainIndexHandler.java` (def=True, cls=QuickTrainIndexHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=True, cls=SoldierStandbyBuyMomoHandler, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyExchangeHandler.java` (def=True, cls=SoldierStandbyExchangeHandler, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamIndexHandler.java` (def=True, cls=TeamIndexHandler, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksIndexHandler.java` (def=True, cls=TeamLooksIndexHandler, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksStarUpgradeHandler.java` (def=True, cls=TeamLooksStarUpgradeHandler, n=1)
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUnlockHandler.java` (def=True, cls=TeamLooksUnlockHandler, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUseHandler.java` (def=True, cls=TeamLooksUseHandler, n=1)

### fengshen-code-032  [BUSINESS_TERM]  Gold排名=—
- 解析：方法符号=handle，Gold=TeamMarchSpeedHandler#handle
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/HeroResonanceIndexHandler.java` (def=True, cls=HeroResonanceIndexHandler, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainHandler.java` (def=True, cls=QuickTrainHandler, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainIndexHandler.java` (def=True, cls=QuickTrainIndexHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=True, cls=SoldierStandbyBuyMomoHandler, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyExchangeHandler.java` (def=True, cls=SoldierStandbyExchangeHandler, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamIndexHandler.java` (def=True, cls=TeamIndexHandler, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksIndexHandler.java` (def=True, cls=TeamLooksIndexHandler, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksStarUpgradeHandler.java` (def=True, cls=TeamLooksStarUpgradeHandler, n=1)
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUnlockHandler.java` (def=True, cls=TeamLooksUnlockHandler, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUseHandler.java` (def=True, cls=TeamLooksUseHandler, n=1)

### fengshen-code-033  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=initWorldTeams，Gold=TeamPluginCommon#initWorldTeams
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/team/TeamPluginCommon.java` (def=True, cls=TeamPluginCommon, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/bean/stage/StageWorld.java` (def=False, cls=StageWorld, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/team/ITeamPlugin.java` (def=False, cls=ITeamPlugin, n=1)

### fengshen-code-034  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=teamRemovedAutoSave，Gold=TeamPluginCommon#teamRemovedAutoSave
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/team/TeamPluginCommon.java` (def=True, cls=TeamPluginCommon, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/team/ITeamPlugin.java` (def=False, cls=ITeamPlugin, n=1)

### fengshen-code-035  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=testClearUnionInfo，Gold=TestMoaServiceImpl#testClearUnionInfo
- Top-10：
  1. [203] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/TestMoaServiceImpl.java` (def=True, cls=TestMoaServiceImpl, n=3) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/union/UnionQuitModel.java` (def=True, cls=UnionQuitModel, n=1)
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/ITestMoaService.java` (def=False, cls=ITestMoaService, n=1)

### fengshen-code-036  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=tickSendFarmChestOpenFinishMsg，Gold=TickMoaServiceImpl#tickSendFarmChestOpenFinishMsg
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/TickMoaServiceImpl.java` (def=True, cls=TickMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/ITickMoaService.java` (def=False, cls=ITickMoaService, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/enums/CallBackMethod.java` (def=False, cls=CallBackMethod, n=1)

### fengshen-code-037  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=commandTowerFight，Gold=TowerMoaServiceImpl#commandTowerFight
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/TowerMoaServiceImpl.java` (def=True, cls=TowerMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/ITowerMoaService.java` (def=False, cls=ITowerMoaService, n=1)

### fengshen-code-038  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=turntableLottery，Gold=TurntableMoaServiceImpl#turntableLottery
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/TurntableMoaServiceImpl.java` (def=True, cls=TurntableMoaServiceImpl, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/turntable/TurntableModel.java` (def=True, cls=TurntableModel, n=1)
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/activity/ITurntableMoaService.java` (def=False, cls=ITurntableMoaService, n=1)

### fengshen-code-039  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=turntableTaskList，Gold=TurntableMoaServiceImpl#turntableTaskList
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/TurntableMoaServiceImpl.java` (def=True, cls=TurntableMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/activity/ITurntableMoaService.java` (def=False, cls=ITurntableMoaService, n=1)

### fengshen-code-040  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=breakRole，Gold=RoleMoaServiceImpl#breakRole
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/RoleMoaServiceImpl.java` (def=True, cls=RoleMoaServiceImpl, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/role/RoleModel.java` (def=True, cls=RoleModel, n=1)
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IRoleMoaService.java` (def=False, cls=IRoleMoaService, n=1)

### fengshen-code-041  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=roleLooksUse，Gold=RoleLooksMoaServiceImpl#roleLooksUse
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/RoleLooksMoaServiceImpl.java` (def=True, cls=RoleLooksMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IRoleLooksService.java` (def=False, cls=IRoleLooksService, n=1)

### fengshen-code-042  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=petFusionChoose，Gold=PetMoaServiceImpl#petFusionChoose
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/PetMoaServiceImpl.java` (def=True, cls=PetMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IPetMoaService.java` (def=False, cls=IPetMoaService, n=1)

### fengshen-code-043  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=petPutOn，Gold=PetMoaServiceImpl#petPutOn
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/PetMoaServiceImpl.java` (def=True, cls=PetMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IPetMoaService.java` (def=False, cls=IPetMoaService, n=1)

### fengshen-code-044  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=petSummonRefresh，Gold=PetMoaServiceImpl#petSummonRefresh
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/PetMoaServiceImpl.java` (def=True, cls=PetMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IPetMoaService.java` (def=False, cls=IPetMoaService, n=1)

### fengshen-code-045  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=canMergeByParam，Gold=PetService#canMergeByParam
- Top-10：
  1. [205] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/pet/PetService.java` (def=True, cls=PetService, n=5) ★★符号+文件命中

### fengshen-code-046  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=findMergeTarget，Gold=PetService#findMergeTarget
- Top-10：
  1. [206] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/pet/PetService.java` (def=True, cls=PetService, n=6) ★★符号+文件命中

### fengshen-code-047  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=refresh，Gold=PetService#refresh
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/pet/PetService.java` (def=True, cls=PetService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/PetMoaServiceImpl.java` (def=False, cls=PetMoaServiceImpl, n=1)

### fengshen-code-048  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=chooseKeep，Gold=PetFusionService#chooseKeep
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/pet/PetFusionService.java` (def=True, cls=PetFusionService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/PetMoaServiceImpl.java` (def=False, cls=PetMoaServiceImpl, n=1)

### fengshen-code-049  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=spiritMark，Gold=SpiritMoaServiceImpl#spiritMark
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/SpiritMoaServiceImpl.java` (def=True, cls=SpiritMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/ISpiritMoaService.java` (def=False, cls=ISpiritMoaService, n=1)

### fengshen-code-050  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=canAffixUnlock，Gold=SpiritService#canAffixUnlock
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/spirit/SpiritService.java` (def=True, cls=SpiritService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/SpiritMoaServiceImpl.java` (def=False, cls=SpiritMoaServiceImpl, n=1)

### fengshen-code-051  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=refine，Gold=SpiritService#refine
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/spirit/SpiritService.java` (def=True, cls=SpiritService, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/spirit/SpiritModel.java` (def=True, cls=SpiritModel, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/SpiritMoaServiceImpl.java` (def=False, cls=SpiritMoaServiceImpl, n=1)

### fengshen-code-052  [BUSINESS_TERM]  Gold排名=2
- 解析：方法符号=receiveTaskActivePrize，Gold=TaskMoaServiceImpl#receiveTaskActivePrize
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/crazydig/CrazyDigService.java` (def=True, cls=CrazyDigService, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/TaskMoaServiceImpl.java` (def=True, cls=TaskMoaServiceImpl, n=1) ★★符号+文件命中
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/ITaskMoaService.java` (def=False, cls=ITaskMoaService, n=1)
  4. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/CrazyDigMoaServiceImpl.java` (def=False, cls=CrazyDigMoaServiceImpl, n=1)
  5. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/CrazyDigMoaServiceImplV2.java` (def=False, cls=CrazyDigMoaServiceImplV2, n=1)

### fengshen-code-053  [BUSINESS_TERM]  Gold排名=2
- 解析：方法符号=checkActivityStatus，Gold=AnnounceService#checkActivityStatus
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/announce/AnnounceModel.java` (def=True, cls=AnnounceModel, n=2)
  2. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/announce/AnnounceService.java` (def=True, cls=AnnounceService, n=2) ★★符号+文件命中
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/TimingMoaServiceImpl.java` (def=False, cls=TimingMoaServiceImpl, n=1)

### fengshen-code-054  [BUSINESS_TERM]  Gold排名=—
- 解析：方法符号=handle，Gold=CompactRefuseInviteHandler#handle
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/HeroResonanceIndexHandler.java` (def=True, cls=HeroResonanceIndexHandler, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainHandler.java` (def=True, cls=QuickTrainHandler, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainIndexHandler.java` (def=True, cls=QuickTrainIndexHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=True, cls=SoldierStandbyBuyMomoHandler, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyExchangeHandler.java` (def=True, cls=SoldierStandbyExchangeHandler, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamIndexHandler.java` (def=True, cls=TeamIndexHandler, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksIndexHandler.java` (def=True, cls=TeamLooksIndexHandler, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksStarUpgradeHandler.java` (def=True, cls=TeamLooksStarUpgradeHandler, n=1)
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUnlockHandler.java` (def=True, cls=TeamLooksUnlockHandler, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUseHandler.java` (def=True, cls=TeamLooksUseHandler, n=1)

### fengshen-code-055  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=canKick，Gold=CompactPluginCommon#canKick
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/compact/CompactPluginCommon.java` (def=True, cls=CompactPluginCommon, n=2) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/compact/ICompactPlugin.java` (def=False, cls=ICompactPlugin, n=1)

### fengshen-code-056  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=inviteList，Gold=CompactPluginCommon#inviteList
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/compact/CompactPluginCommon.java` (def=True, cls=CompactPluginCommon, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/compact/ICompactPlugin.java` (def=False, cls=ICompactPlugin, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/compact/CompactInviteListHandler.java` (def=False, cls=CompactInviteListHandler, n=1)

### fengshen-code-057  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=sendInvite，Gold=CompactPluginCommon#sendInvite
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/compact/CompactPluginCommon.java` (def=True, cls=CompactPluginCommon, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/compact/ICompactPlugin.java` (def=False, cls=ICompactPlugin, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/compact/CompactSendInviteHandler.java` (def=False, cls=CompactSendInviteHandler, n=1)

### fengshen-code-058  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=queryWorldShopIndex，Gold=ShopMoaServiceImpl#queryWorldShopIndex
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/ShopMoaServiceImpl.java` (def=True, cls=ShopMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IShopMoaService.java` (def=False, cls=IShopMoaService, n=1)

### fengshen-code-059  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=testLotteryScripte，Gold=WorldLotteryMoaServiceImpl#testLotteryScripte
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/impl/WorldLotteryMoaServiceImpl.java` (def=True, cls=WorldLotteryMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/service/IWorldLotteryMoaService.java` (def=False, cls=IWorldLotteryMoaService, n=1)

### fengshen-code-060  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=processTest，Gold=WorldMessageMoaServiceImpl#processTest
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/impl/WorldMessageMoaServiceImpl.java` (def=True, cls=WorldMessageMoaServiceImpl, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/season/RoomShardTestModel.java` (def=True, cls=RoomShardTestModel, n=1)
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/service/IWorldMessageMoaService.java` (def=False, cls=IWorldMessageMoaService, n=1)

### fengshen-code-061  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=receiveTaskRewardAll，Gold=WorldTaskMoaServiceImpl#receiveTaskRewardAll
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/impl/WorldTaskMoaServiceImpl.java` (def=True, cls=WorldTaskMoaServiceImpl, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/lottery/WorldTaskModel.java` (def=True, cls=WorldTaskModel, n=1)
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/service/IWorldTaskMoaService.java` (def=False, cls=IWorldTaskMoaService, n=1)

### fengshen-code-062  [BUSINESS_TERM]  Gold排名=—
- 解析：方法符号=handle，Gold=WorldFightRewardHandler#handle
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/HeroResonanceIndexHandler.java` (def=True, cls=HeroResonanceIndexHandler, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainHandler.java` (def=True, cls=QuickTrainHandler, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainIndexHandler.java` (def=True, cls=QuickTrainIndexHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=True, cls=SoldierStandbyBuyMomoHandler, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyExchangeHandler.java` (def=True, cls=SoldierStandbyExchangeHandler, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamIndexHandler.java` (def=True, cls=TeamIndexHandler, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksIndexHandler.java` (def=True, cls=TeamLooksIndexHandler, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksStarUpgradeHandler.java` (def=True, cls=TeamLooksStarUpgradeHandler, n=1)
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUnlockHandler.java` (def=True, cls=TeamLooksUnlockHandler, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUseHandler.java` (def=True, cls=TeamLooksUseHandler, n=1)

### fengshen-code-063  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=worldUnionNoticeLike，Gold=WorldUnionNoticeMoaServiceImpl#worldUnionNoticeLike
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/impl/WorldUnionNoticeMoaServiceImpl.java` (def=True, cls=WorldUnionNoticeMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/service/IWorldUnionNoticeMoaService.java` (def=False, cls=IWorldUnionNoticeMoaService, n=1)

### fengshen-code-064  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=modify，Gold=WorldUnionNoticeService#modify
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/unionNotice/WorldUnionNoticeService.java` (def=True, cls=WorldUnionNoticeService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/impl/WorldUnionNoticeMoaServiceImpl.java` (def=False, cls=WorldUnionNoticeMoaServiceImpl, n=1)

### fengshen-code-065  [BUSINESS_TERM]  Gold排名=—
- 解析：方法符号=handle，Gold=BuildKillRankHandler#handle
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/HeroResonanceIndexHandler.java` (def=True, cls=HeroResonanceIndexHandler, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainHandler.java` (def=True, cls=QuickTrainHandler, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainIndexHandler.java` (def=True, cls=QuickTrainIndexHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=True, cls=SoldierStandbyBuyMomoHandler, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyExchangeHandler.java` (def=True, cls=SoldierStandbyExchangeHandler, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamIndexHandler.java` (def=True, cls=TeamIndexHandler, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksIndexHandler.java` (def=True, cls=TeamLooksIndexHandler, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksStarUpgradeHandler.java` (def=True, cls=TeamLooksStarUpgradeHandler, n=1)
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUnlockHandler.java` (def=True, cls=TeamLooksUnlockHandler, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUseHandler.java` (def=True, cls=TeamLooksUseHandler, n=1)

### fengshen-code-066  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=doExecuteCancelFocusFire，Gold=BuildPluginCommon#doExecuteCancelFocusFire
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/build/BuildPluginCommon.java` (def=True, cls=BuildPluginCommon, n=2) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/build/IBuildPlugin.java` (def=False, cls=IBuildPlugin, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/compact/CompactPluginWar.java` (def=False, cls=CompactPluginWar, n=1)

### fengshen-code-067  [BUSINESS_TERM]  Gold排名=2
- 解析：方法符号=canBuy，Gold=GrowFundService#canBuy
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/activityLimitPack/ActivityLimitPackService.java` (def=True, cls=ActivityLimitPackService, n=2)
  2. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/growFund/GrowFundService.java` (def=True, cls=GrowFundService, n=2) ★★符号+文件命中
  3. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/regen/ReGenPluginCommon.java` (def=True, cls=ReGenPluginCommon, n=2)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/destiny/DestinyPackModel.java` (def=True, cls=DestinyPackModel, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/growFund/GrowFundModel.java` (def=True, cls=GrowFundModel, n=1)
  6. [3] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/reGen/ReGenModel.java` (def=False, cls=ReGenModel, n=3)
  7. [2] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/GrowFundMoaServiceImpl.java` (def=False, cls=GrowFundMoaServiceImpl, n=2)
  8. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/response/reGen/ReGenNode.java` (def=False, cls=ReGenNode, n=1)
  9. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/destiny/DestinyService.java` (def=False, cls=DestinyService, n=1)
  10. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/ActivityLimitPackMoaServiceImpl.java` (def=False, cls=ActivityLimitPackMoaServiceImpl, n=1)

### fengshen-code-068  [BUSINESS_TERM]  Gold排名=2
- 解析：方法符号=buildRecordList，Gold=WarGoodsRecordService#buildRecordList
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/union/UnionWareHouseRecordModel.java` (def=True, cls=UnionWareHouseRecordModel, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/warGoodsRecord/WarGoodsRecordService.java` (def=True, cls=WarGoodsRecordService, n=1) ★★符号+文件命中
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/UnionMoaServiceImpl.java` (def=False, cls=UnionMoaServiceImpl, n=1)
  4. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/impl/WorldHistoryMoaServiceImpl.java` (def=False, cls=WorldHistoryMoaServiceImpl, n=1)

### fengshen-code-069  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=towerFight，Gold=FightService#towerFight
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/fight/FightService.java` (def=True, cls=FightService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/bean/tower/TowerFightContext.java` (def=False, cls=TowerFightContext, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/event/EventKey.java` (def=False, cls=EventKey, n=1)

### fengshen-code-070  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=receivePveReward，Gold=FightMoaServiceImpl#receivePveReward
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/FightMoaServiceImpl.java` (def=True, cls=FightMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IFightMoaService.java` (def=False, cls=IFightMoaService, n=1)

### fengshen-code-071  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=clearFightUser，Gold=FightPluginCommon#clearFightUser
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/fight/FightPluginCommon.java` (def=True, cls=FightPluginCommon, n=2) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/fight/IFightPlugin.java` (def=False, cls=IFightPlugin, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/user/UserPluginCommon.java` (def=False, cls=UserPluginCommon, n=1)

### fengshen-code-072  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=fightKillUserEvent，Gold=FightPluginCommon#fightKillUserEvent
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/fight/FightPluginCommon.java` (def=True, cls=FightPluginCommon, n=2) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/fight/FightPluginWar.java` (def=False, cls=FightPluginWar, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/fight/IFightPlugin.java` (def=False, cls=IFightPlugin, n=1)

### fengshen-code-073  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=addTestRankData，Gold=RankMoaServiceImpl#addTestRankData
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/RankMoaServiceImpl.java` (def=True, cls=RankMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IRankMoaService.java` (def=False, cls=IRankMoaService, n=1)

### fengshen-code-074  [BUSINESS_TERM]  Gold排名=2
- 解析：方法符号=moneyTreeIndex，Gold=MoneyTreeService#moneyTreeIndex
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/MoneyTreeMoaServiceImpl.java` (def=True, cls=MoneyTreeMoaServiceImpl, n=2)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/moneyTree/MoneyTreeService.java` (def=True, cls=MoneyTreeService, n=1) ★★符号+文件命中
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IMoneyTreeMoaService.java` (def=False, cls=IMoneyTreeMoaService, n=1)

### fengshen-code-075  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=packBuy，Gold=ActivityCommonPackService#packBuy
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/activity/ActivityCommonPackService.java` (def=True, cls=ActivityCommonPackService, n=1) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/destiny/DestinyService.java` (def=True, cls=DestinyService, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/ActivityCommonMoaServiceImpl.java` (def=False, cls=ActivityCommonMoaServiceImpl, n=1)
  4. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/DestinyMoaServiceImpl.java` (def=False, cls=DestinyMoaServiceImpl, n=1)

### fengshen-code-076  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=roll，Gold=ActivityMazeService#roll
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/activityMaze/ActivityMazeService.java` (def=True, cls=ActivityMazeService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/pet/PetFusionModel.java` (def=False, cls=PetFusionModel, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/ActivityMazeMoaServiceImpl.java` (def=False, cls=ActivityMazeMoaServiceImpl, n=1)
  4. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/enums/hubble/Title.java` (def=False, cls=Title, n=1)

### fengshen-code-077  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=canBuy，Gold=ActivityLimitPackService#canBuy
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/activityLimitPack/ActivityLimitPackService.java` (def=True, cls=ActivityLimitPackService, n=2) ★★符号+文件命中
  2. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/growFund/GrowFundService.java` (def=True, cls=GrowFundService, n=2)
  3. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/regen/ReGenPluginCommon.java` (def=True, cls=ReGenPluginCommon, n=2)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/destiny/DestinyPackModel.java` (def=True, cls=DestinyPackModel, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/growFund/GrowFundModel.java` (def=True, cls=GrowFundModel, n=1)
  6. [3] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/reGen/ReGenModel.java` (def=False, cls=ReGenModel, n=3)
  7. [2] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/GrowFundMoaServiceImpl.java` (def=False, cls=GrowFundMoaServiceImpl, n=2)
  8. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/response/reGen/ReGenNode.java` (def=False, cls=ReGenNode, n=1)
  9. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/destiny/DestinyService.java` (def=False, cls=DestinyService, n=1)
  10. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/ActivityLimitPackMoaServiceImpl.java` (def=False, cls=ActivityLimitPackMoaServiceImpl, n=1)

### fengshen-code-078  [BUSINESS_TERM]  Gold排名=2
- 解析：方法符号=hunyuanRankList，Gold=HunyuanService#hunyuanRankList
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/HunyuanMoaServiceImpl.java` (def=True, cls=HunyuanMoaServiceImpl, n=2)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/hunyuan/HunyuanService.java` (def=True, cls=HunyuanService, n=1) ★★符号+文件命中
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/activity/IHunyuanMoaService.java` (def=False, cls=IHunyuanMoaService, n=1)

### fengshen-code-079  [BUSINESS_TERM]  Gold排名=3
- 解析：方法符号=hunyuanPackIndex，Gold=HunyuanMoaServiceImpl#hunyuanPackIndex
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/hunyuan/HunyuanPackModel.java` (def=True, cls=HunyuanPackModel, n=2)
  2. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/hunyuan/HunyuanService.java` (def=True, cls=HunyuanService, n=2)
  3. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/HunyuanMoaServiceImpl.java` (def=True, cls=HunyuanMoaServiceImpl, n=2) ★★符号+文件命中
  4. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/activity/IHunyuanMoaService.java` (def=False, cls=IHunyuanMoaService, n=1)

### fengshen-code-080  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=crazyDigExchange，Gold=CrazyDigService#crazyDigExchange
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/crazydig/CrazyDigService.java` (def=True, cls=CrazyDigService, n=2) ★★符号+文件命中
  2. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/CrazyDigMoaServiceImpl.java` (def=True, cls=CrazyDigMoaServiceImpl, n=2)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/crazyDig/CrazyDigExchangeModel.java` (def=True, cls=CrazyDigExchangeModel, n=1)
  4. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/activity/ICrazyDigMoaService.java` (def=False, cls=ICrazyDigMoaService, n=1)
  5. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/crazyDig/CrazyDigModel.java` (def=False, cls=CrazyDigModel, n=1)
  6. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/CrazyDigMoaServiceImplV2.java` (def=False, cls=CrazyDigMoaServiceImplV2, n=1)

### fengshen-code-081  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=receiveDayPrize，Gold=CrazyDigService#receiveDayPrize
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/crazydig/CrazyDigService.java` (def=True, cls=CrazyDigService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/CrazyDigMoaServiceImplV2.java` (def=False, cls=CrazyDigMoaServiceImplV2, n=1)

### fengshen-code-082  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=receiveTaskRewardCrazyDig，Gold=CrazyDigMoaServiceImpl#receiveTaskRewardCrazyDig
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/CrazyDigMoaServiceImpl.java` (def=True, cls=CrazyDigMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/activity/ICrazyDigMoaService.java` (def=False, cls=ICrazyDigMoaService, n=1)

### fengshen-code-083  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=clearMineData，Gold=MinePluginCommon#clearMineData
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/mine/MinePluginCommon.java` (def=True, cls=MinePluginCommon, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/bean/stage/StageWorldRoutine.java` (def=False, cls=StageWorldRoutine, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/bean/stage/StageWorldWar.java` (def=False, cls=StageWorldWar, n=1)
  4. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/mine/IMinePlugin.java` (def=False, cls=IMinePlugin, n=1)

### fengshen-code-084  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=onTeamLeaveCollect，Gold=MinePluginCommon#onTeamLeaveCollect
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/mine/MinePluginCommon.java` (def=True, cls=MinePluginCommon, n=1) ★★符号+文件命中
  2. [4] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/march/MarchPluginCommon.java` (def=False, cls=MarchPluginCommon, n=4)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/enums/MineLeaveType.java` (def=False, cls=MineLeaveType, n=1)
  4. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/march/IMarchPlugin.java` (def=False, cls=IMarchPlugin, n=1)
  5. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/mine/IMinePlugin.java` (def=False, cls=IMinePlugin, n=1)

### fengshen-code-085  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=rewardMine，Gold=MinePluginCommon#rewardMine
- Top-10：
  1. [203] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/mine/MinePluginCommon.java` (def=True, cls=MinePluginCommon, n=3) ★★符号+文件命中

### fengshen-code-086  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=canWear，Gold=ArtifactService#canWear
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/artifact/ArtifactService.java` (def=True, cls=ArtifactService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/ArtifactMoaServiceImpl.java` (def=False, cls=ArtifactMoaServiceImpl, n=1)

### fengshen-code-087  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=starIndex，Gold=ArtifactService#starIndex
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/artifact/ArtifactService.java` (def=True, cls=ArtifactService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/ArtifactMoaServiceImpl.java` (def=False, cls=ArtifactMoaServiceImpl, n=1)

### fengshen-code-088  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=artifactReminderCancel，Gold=ArtifactMoaServiceImpl#artifactReminderCancel
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/ArtifactMoaServiceImpl.java` (def=True, cls=ArtifactMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IArtifactMoaService.java` (def=False, cls=IArtifactMoaService, n=1)

### fengshen-code-089  [BUSINESS_TERM]  Gold排名=2
- 解析：方法符号=queryChatIndex，Gold=ChatMoaServiceImpl#queryChatIndex
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/chat/ChatModel.java` (def=True, cls=ChatModel, n=2)
  2. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/impl/ChatMoaServiceImpl.java` (def=True, cls=ChatMoaServiceImpl, n=2) ★★符号+文件命中
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/chat/base/IChatHandler.java` (def=True, cls=IChatHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/chat/base/handlers/WorldChatHandler.java` (def=True, cls=WorldChatHandler, n=1)
  5. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/service/IChatMoaService.java` (def=False, cls=IChatMoaService, n=1)

### fengshen-code-090  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=linkageZhuGongReceive，Gold=LinkageMoaServiceImpl#linkageZhuGongReceive
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/LinkageMoaServiceImpl.java` (def=True, cls=LinkageMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/activity/ILinkageMoaService.java` (def=False, cls=ILinkageMoaService, n=1)

### fengshen-code-091  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=commandUnionApplyDirect，Gold=UnionMoaServiceImpl#commandUnionApplyDirect
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/UnionMoaServiceImpl.java` (def=True, cls=UnionMoaServiceImpl, n=2) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IUnionMoaService.java` (def=False, cls=IUnionMoaService, n=1)

### fengshen-code-092  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=commandUnionModifyAnnc，Gold=UnionMoaServiceImpl#commandUnionModifyAnnc
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/UnionMoaServiceImpl.java` (def=True, cls=UnionMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IUnionMoaService.java` (def=False, cls=IUnionMoaService, n=1)

### fengshen-code-093  [BUSINESS_TERM]  Gold排名=2
- 解析：方法符号=queryMomoGroupInfo，Gold=UnionMoaServiceImpl#queryMomoGroupInfo
- Top-10：
  1. [203] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/union/UnionModifyModel.java` (def=True, cls=UnionModifyModel, n=3)
  2. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/UnionMoaServiceImpl.java` (def=True, cls=UnionMoaServiceImpl, n=2) ★★符号+文件命中
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IUnionMoaService.java` (def=False, cls=IUnionMoaService, n=1)

### fengshen-code-094  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=queryUnionTaskList，Gold=UnionMoaServiceImpl#queryUnionTaskList
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/UnionMoaServiceImpl.java` (def=True, cls=UnionMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IUnionMoaService.java` (def=False, cls=IUnionMoaService, n=1)

### fengshen-code-095  [BUSINESS_TERM]  Gold排名=3
- 解析：方法符号=batchQueryUnionUserList，Gold=UnionService#batchQueryUnionUserList
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/redis/UnionDao.java` (def=True, cls=UnionDao, n=2)
  2. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/union/UnionUserModel.java` (def=True, cls=UnionUserModel, n=2)
  3. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/union/UnionService.java` (def=True, cls=UnionService, n=2) ★★符号+文件命中
  4. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/union/UnionPluginCommon.java` (def=False, cls=UnionPluginCommon, n=1)

### fengshen-code-096  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=unionAstrolabeDonate，Gold=UnionAstrolabeMoaServiceImpl#unionAstrolabeDonate
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/UnionAstrolabeMoaServiceImpl.java` (def=True, cls=UnionAstrolabeMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IUnionAstrolabeMoaService.java` (def=False, cls=IUnionAstrolabeMoaService, n=1)

### fengshen-code-097  [BUSINESS_TERM]  Gold排名=—
- 解析：方法符号=handle，Gold=UnionDisbandHandler#handle
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/HeroResonanceIndexHandler.java` (def=True, cls=HeroResonanceIndexHandler, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainHandler.java` (def=True, cls=QuickTrainHandler, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainIndexHandler.java` (def=True, cls=QuickTrainIndexHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=True, cls=SoldierStandbyBuyMomoHandler, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyExchangeHandler.java` (def=True, cls=SoldierStandbyExchangeHandler, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamIndexHandler.java` (def=True, cls=TeamIndexHandler, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksIndexHandler.java` (def=True, cls=TeamLooksIndexHandler, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksStarUpgradeHandler.java` (def=True, cls=TeamLooksStarUpgradeHandler, n=1)
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUnlockHandler.java` (def=True, cls=TeamLooksUnlockHandler, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUseHandler.java` (def=True, cls=TeamLooksUseHandler, n=1)

### fengshen-code-098  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=boxAdminIndex，Gold=UnionWarehouseService#boxAdminIndex
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/union/UnionWarehouseService.java` (def=True, cls=UnionWarehouseService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/UnionMoaServiceImpl.java` (def=False, cls=UnionMoaServiceImpl, n=1)

### fengshen-code-099  [BUSINESS_TERM]  Gold排名=—
- 解析：方法符号=index，Gold=UnionTitleService#index
- Top-10：
  1. [203] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/activityMaze/ActivityMazeService.java` (def=True, cls=ActivityMazeService, n=3)
  2. [203] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/spirit/SpiritService.java` (def=True, cls=SpiritService, n=3)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/union/UnionWarehouseModel.java` (def=True, cls=UnionWarehouseModel, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/activityLimitPack/ActivityLimitPackService.java` (def=True, cls=ActivityLimitPackService, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/announce/AnnounceService.java` (def=True, cls=AnnounceService, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/artifact/ArtifactService.java` (def=True, cls=ArtifactService, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/compact/WorldCompactService.java` (def=True, cls=WorldCompactService, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/destiny/DestinyService.java` (def=True, cls=DestinyService, n=1)
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/growFund/GrowFundService.java` (def=True, cls=GrowFundService, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/linkage/LinkageService.java` (def=True, cls=LinkageService, n=1)

### fengshen-code-100  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=querySingleUnionJobInfo，Gold=UnionPluginCommon#querySingleUnionJobInfo
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/union/UnionPluginCommon.java` (def=True, cls=UnionPluginCommon, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/union/UnionService.java` (def=True, cls=UnionService, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/lottery/LotteryModel.java` (def=False, cls=LotteryModel, n=1)
  4. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/union/IUnionPlugin.java` (def=False, cls=IUnionPlugin, n=1)

### fengshen-code-101  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=buildGuildHeroLotteryShowInfos，Gold=HeroService#buildGuildHeroLotteryShowInfos
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/hero/HeroService.java` (def=True, cls=HeroService, n=1) ★★符号+文件命中
  2. [2] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/pve/PveFightModel.java` (def=False, cls=PveFightModel, n=2)
  3. [2] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/farm/FarmService.java` (def=False, cls=FarmService, n=2)
  4. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/FightMoaServiceImpl.java` (def=False, cls=FightMoaServiceImpl, n=1)
  5. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/ItemMoaServiceImpl.java` (def=False, cls=ItemMoaServiceImpl, n=1)

### fengshen-code-102  [BUSINESS_TERM]  Gold排名=2
- 解析：方法符号=canReceivePoint，Gold=HeroService#canReceivePoint
- Top-10：
  1. [206] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/hero/HeroModel.java` (def=True, cls=HeroModel, n=6)
  2. [205] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/hero/HeroService.java` (def=True, cls=HeroService, n=5) ★★符号+文件命中
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/response/hero/ResHeroCataloguePoint.java` (def=False, cls=ResHeroCataloguePoint, n=1)
  4. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/response/hero/node/HeroCatalogueNode.java` (def=False, cls=HeroCatalogueNode, n=1)
  5. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/HeroMoaServiceImpl.java` (def=False, cls=HeroMoaServiceImpl, n=1)

### fengshen-code-103  [BUSINESS_TERM]  Gold排名=2
- 解析：方法符号=canUpgradeEquip，Gold=HeroService#canUpgradeEquip
- Top-10：
  1. [205] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/hero/HeroEquipModel.java` (def=True, cls=HeroEquipModel, n=5)
  2. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/hero/HeroService.java` (def=True, cls=HeroService, n=2) ★★符号+文件命中
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/HeroMoaServiceImpl.java` (def=False, cls=HeroMoaServiceImpl, n=1)

### fengshen-code-104  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=equipMergePreview，Gold=HeroService#equipMergePreview
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/hero/HeroService.java` (def=True, cls=HeroService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/HeroMoaServiceImpl.java` (def=False, cls=HeroMoaServiceImpl, n=1)

### fengshen-code-105  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=putOn，Gold=HeroService#putOn
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/hero/HeroService.java` (def=True, cls=HeroService, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/hero/HeroModel.java` (def=True, cls=HeroModel, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/pet/PetService.java` (def=True, cls=PetService, n=1)
  4. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/request/artifact/ArtifactWearParam.java` (def=False, cls=ArtifactWearParam, n=1)
  5. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/HeroMoaServiceImpl.java` (def=False, cls=HeroMoaServiceImpl, n=1)
  6. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/PetMoaServiceImpl.java` (def=False, cls=PetMoaServiceImpl, n=1)

### fengshen-code-106  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=takeOff，Gold=HeroService#takeOff
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/hero/HeroService.java` (def=True, cls=HeroService, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/hero/HeroModel.java` (def=True, cls=HeroModel, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/pet/PetService.java` (def=True, cls=PetService, n=1)
  4. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/request/artifact/ArtifactWearParam.java` (def=False, cls=ArtifactWearParam, n=1)
  5. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/HeroMoaServiceImpl.java` (def=False, cls=HeroMoaServiceImpl, n=1)
  6. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/PetMoaServiceImpl.java` (def=False, cls=PetMoaServiceImpl, n=1)

### fengshen-code-107  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=upgradeEquipAll，Gold=HeroService#upgradeEquipAll
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/hero/HeroService.java` (def=True, cls=HeroService, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/hero/HeroEquipModel.java` (def=True, cls=HeroEquipModel, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/HeroMoaServiceImpl.java` (def=False, cls=HeroMoaServiceImpl, n=1)

### fengshen-code-108  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=heroAwakeIndex，Gold=HeroMoaServiceImpl#heroAwakeIndex
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/HeroMoaServiceImpl.java` (def=True, cls=HeroMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IHeroMoaService.java` (def=False, cls=IHeroMoaService, n=1)

### fengshen-code-109  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=heroCultureIndex，Gold=HeroMoaServiceImpl#heroCultureIndex
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/HeroMoaServiceImpl.java` (def=True, cls=HeroMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IHeroMoaService.java` (def=False, cls=IHeroMoaService, n=1)

### fengshen-code-110  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=heroEquipMergeIndex，Gold=HeroMoaServiceImpl#heroEquipMergeIndex
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/HeroMoaServiceImpl.java` (def=True, cls=HeroMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IHeroMoaService.java` (def=False, cls=IHeroMoaService, n=1)

### fengshen-code-111  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=heroEquipUpgradeAll，Gold=HeroMoaServiceImpl#heroEquipUpgradeAll
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/HeroMoaServiceImpl.java` (def=True, cls=HeroMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IHeroMoaService.java` (def=False, cls=IHeroMoaService, n=1)

### fengshen-code-112  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=heroRealmBreakthrough，Gold=HeroMoaServiceImpl#heroRealmBreakthrough
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/HeroMoaServiceImpl.java` (def=True, cls=HeroMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IHeroMoaService.java` (def=False, cls=IHeroMoaService, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/event/EventKey.java` (def=False, cls=EventKey, n=1)

### fengshen-code-113  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=heroStarUpgrade，Gold=HeroMoaServiceImpl#heroStarUpgrade
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/HeroMoaServiceImpl.java` (def=True, cls=HeroMoaServiceImpl, n=2) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IHeroMoaService.java` (def=False, cls=IHeroMoaService, n=1)

### fengshen-code-114  [BUSINESS_TERM]  Gold排名=2
- 解析：方法符号=heroTeamRecommendUse，Gold=HeroMoaServiceImpl#heroTeamRecommendUse
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/hero/HeroService.java` (def=True, cls=HeroService, n=2)
  2. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/HeroMoaServiceImpl.java` (def=True, cls=HeroMoaServiceImpl, n=2) ★★符号+文件命中
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/hero/HeroModel.java` (def=True, cls=HeroModel, n=1)
  4. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IHeroMoaService.java` (def=False, cls=IHeroMoaService, n=1)

### fengshen-code-115  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=canBreakthrough，Gold=HeroRealmService#canBreakthrough
- Top-10：
  1. [205] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/hero/HeroRealmService.java` (def=True, cls=HeroRealmService, n=5) ★★符号+文件命中
  2. [204] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/hero/HeroRealmModel.java` (def=True, cls=HeroRealmModel, n=4)
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/response/hero/ResHeroRealmIndex.java` (def=False, cls=ResHeroRealmIndex, n=1)
  4. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/HeroMoaServiceImpl.java` (def=False, cls=HeroMoaServiceImpl, n=1)

### fengshen-code-116  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=smelt，Gold=HeroSmeltService#smelt
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/hero/HeroSmeltService.java` (def=True, cls=HeroSmeltService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/HeroMoaServiceImpl.java` (def=False, cls=HeroMoaServiceImpl, n=1)

### fengshen-code-117  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=unlink，Gold=HeroLinkService#unlink
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/hero/HeroLinkService.java` (def=True, cls=HeroLinkService, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/hero/HeroLinkModel.java` (def=True, cls=HeroLinkModel, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/HeroMoaServiceImpl.java` (def=False, cls=HeroMoaServiceImpl, n=1)

### fengshen-code-118  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=canGuardMarch，Gold=MarchPluginCommon#canGuardMarch
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/march/MarchPluginCommon.java` (def=True, cls=MarchPluginCommon, n=2) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/march/IMarchPlugin.java` (def=False, cls=IMarchPlugin, n=1)

### fengshen-code-119  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=collectMarch，Gold=MarchPluginCommon#collectMarch
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/march/MarchPluginCommon.java` (def=True, cls=MarchPluginCommon, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/march/IMarchPlugin.java` (def=False, cls=IMarchPlugin, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/march/TeamCollectMarchHandler.java` (def=False, cls=TeamCollectMarchHandler, n=1)

### fengshen-code-120  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=repatriateDeadToHomeBuild，Gold=MarchPluginCommon#repatriateDeadToHomeBuild
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/march/MarchPluginCommon.java` (def=True, cls=MarchPluginCommon, n=2) ★★符号+文件命中
  2. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/march/MarchPluginWar.java` (def=True, cls=MarchPluginWar, n=2)
  3. [11] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/fight/FightPluginCommon.java` (def=False, cls=FightPluginCommon, n=11)
  4. [3] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/fight/FightPluginWar.java` (def=False, cls=FightPluginWar, n=3)
  5. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/march/IMarchPlugin.java` (def=False, cls=IMarchPlugin, n=1)

### fengshen-code-121  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=buyShop，Gold=StandardService#buyShop
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/standard/StandardService.java` (def=True, cls=StandardService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/StandardMoaServiceImpl.java` (def=False, cls=StandardMoaServiceImpl, n=1)

### fengshen-code-122  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=itemUseBatch，Gold=ItemMoaServiceImpl#itemUseBatch
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/ItemMoaServiceImpl.java` (def=True, cls=ItemMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IItemMoaService.java` (def=False, cls=IItemMoaService, n=1)

### fengshen-code-123  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=canAdd，Gold=ItemService#canAdd
- Top-10：
  1. [207] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/item/ItemService.java` (def=True, cls=ItemService, n=7) ★★符号+文件命中
  2. [205] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/item/ItemManger.java` (def=True, cls=ItemManger, n=5)
  3. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/item/ItemFarmChestBagModel.java` (def=True, cls=ItemFarmChestBagModel, n=2)
  4. [8] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/tower/TowerModel.java` (def=False, cls=TowerModel, n=8)
  5. [4] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/pass/PassModel.java` (def=False, cls=PassModel, n=4)
  6. [4] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/ShopBlackModel.java` (def=False, cls=ShopBlackModel, n=4)
  7. [4] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/AbstractShopHandler.java` (def=False, cls=AbstractShopHandler, n=4)
  8. [4] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/handlers/FarmShopHandler.java` (def=False, cls=FarmShopHandler, n=4)
  9. [4] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/handlers/SevenDayTaskExchangeShopHandler.java` (def=False, cls=SevenDayTaskExchangeShopHandler, n=4)
  10. [3] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/draw/DrawModel.java` (def=False, cls=DrawModel, n=3)

### fengshen-code-124  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=createItemNodeWithoutBaseInfo，Gold=ItemService#createItemNodeWithoutBaseInfo
- Top-10：
  1. [204] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/item/ItemService.java` (def=True, cls=ItemService, n=4) ★★符号+文件命中
  2. [6] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/draw/DrawModel.java` (def=False, cls=DrawModel, n=6)
  3. [4] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/AbstractShopHandler.java` (def=False, cls=AbstractShopHandler, n=4)
  4. [4] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/artifact/ArtifactService.java` (def=False, cls=ArtifactService, n=4)
  5. [3] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/union/UnionModifyModel.java` (def=False, cls=UnionModifyModel, n=3)
  6. [3] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/union/UnionRcmdModel.java` (def=False, cls=UnionRcmdModel, n=3)
  7. [3] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/farm/FarmService.java` (def=False, cls=FarmService, n=3)
  8. [2] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/bean/union/UnionHallContext.java` (def=False, cls=UnionHallContext, n=2)
  9. [2] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/maze/ActivityMazeModel.java` (def=False, cls=ActivityMazeModel, n=2)
  10. [2] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/ShopBlackModel.java` (def=False, cls=ShopBlackModel, n=2)

### fengshen-code-125  [BUSINESS_TERM]  Gold排名=1
- 解析：方法符号=useItemsByConfigId，Gold=ItemService#useItemsByConfigId
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/item/ItemService.java` (def=True, cls=ItemService, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/item/ItemManger.java` (def=True, cls=ItemManger, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/ItemMoaServiceImpl.java` (def=False, cls=ItemMoaServiceImpl, n=1)

### fengshen-code-126  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=queryVipShopIndex，Gold=VipMoaServiceImpl#queryVipShopIndex
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/VipMoaServiceImpl.java` (def=True, cls=VipMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IVipMoaService.java` (def=False, cls=IVipMoaService, n=1)

### fengshen-code-127  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=vipUsePrivilege，Gold=VipMoaServiceImpl#vipUsePrivilege
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/VipMoaServiceImpl.java` (def=True, cls=VipMoaServiceImpl, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/vip/VipService.java` (def=True, cls=VipService, n=1)
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IVipMoaService.java` (def=False, cls=IVipMoaService, n=1)

### fengshen-code-128  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-129  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=arenaMatch，Gold=ArenaMoaServiceImpl#arenaMatch
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/ArenaMoaServiceImpl.java` (def=True, cls=ArenaMoaServiceImpl, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/arena/ArenaModel.java` (def=True, cls=ArenaModel, n=1)
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IArenaMoaService.java` (def=False, cls=IArenaMoaService, n=1)

### fengshen-code-130  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析：方法符号=handle，Gold=BuffActivateHandler#handle
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/HeroResonanceIndexHandler.java` (def=True, cls=HeroResonanceIndexHandler, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainHandler.java` (def=True, cls=QuickTrainHandler, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainIndexHandler.java` (def=True, cls=QuickTrainIndexHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=True, cls=SoldierStandbyBuyMomoHandler, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyExchangeHandler.java` (def=True, cls=SoldierStandbyExchangeHandler, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamIndexHandler.java` (def=True, cls=TeamIndexHandler, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksIndexHandler.java` (def=True, cls=TeamLooksIndexHandler, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksStarUpgradeHandler.java` (def=True, cls=TeamLooksStarUpgradeHandler, n=1)
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUnlockHandler.java` (def=True, cls=TeamLooksUnlockHandler, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUseHandler.java` (def=True, cls=TeamLooksUseHandler, n=1)

### fengshen-code-131  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=messageDispatchProcess，Gold=C2SMessageService#messageDispatchProcess
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/C2SMessageService.java` (def=True, cls=C2SMessageService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/impl/WorldMessageMoaServiceImpl.java` (def=False, cls=WorldMessageMoaServiceImpl, n=1)

### fengshen-code-132  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=canChestExchange，Gold=FarmService#canChestExchange
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/farm/FarmService.java` (def=True, cls=FarmService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/FarmMoaServiceImpl.java` (def=False, cls=FarmMoaServiceImpl, n=1)

### fengshen-code-133  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-134  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-135  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-136  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-137  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=farmChestBatchReceive，Gold=FarmMoaServiceImpl#farmChestBatchReceive
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/FarmMoaServiceImpl.java` (def=True, cls=FarmMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IFarmMoaService.java` (def=False, cls=IFarmMoaService, n=1)

### fengshen-code-138  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=farmChestStealFirst，Gold=FarmMoaServiceImpl#farmChestStealFirst
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/FarmMoaServiceImpl.java` (def=True, cls=FarmMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IFarmMoaService.java` (def=False, cls=IFarmMoaService, n=1)

### fengshen-code-139  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=farmDigV2，Gold=FarmMoaServiceImpl#farmDigV2
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/FarmMoaServiceImpl.java` (def=True, cls=FarmMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IFarmMoaService.java` (def=False, cls=IFarmMoaService, n=1)

### fengshen-code-140  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=farmLevelUpgrade，Gold=FarmMoaServiceImpl#farmLevelUpgrade
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/FarmMoaServiceImpl.java` (def=True, cls=FarmMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IFarmMoaService.java` (def=False, cls=IFarmMoaService, n=1)

### fengshen-code-141  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-142  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-143  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=handleSkillDestroyCity，Gold=HistoryPluginCommon#handleSkillDestroyCity
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/history/HistoryPluginCommon.java` (def=True, cls=HistoryPluginCommon, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/history/HistoryPluginRoutine.java` (def=False, cls=HistoryPluginRoutine, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/history/HistoryPluginWar.java` (def=False, cls=HistoryPluginWar, n=1)

### fengshen-code-144  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=receiveBoardReward，Gold=MainIndexMoaServiceImpl#receiveBoardReward
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/MainIndexMoaServiceImpl.java` (def=True, cls=MainIndexMoaServiceImpl, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/mainidex/MainIndexModel.java` (def=True, cls=MainIndexModel, n=1)
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IMainIndexMoaService.java` (def=False, cls=IMainIndexMoaService, n=1)

### fengshen-code-145  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=commandOfflineQuick，Gold=OfflineMoaServiceImpl#commandOfflineQuick
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/OfflineMoaServiceImpl.java` (def=True, cls=OfflineMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IOfflineMoaService.java` (def=False, cls=IOfflineMoaService, n=1)

### fengshen-code-146  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析：方法符号=handle，Gold=PongHandler#handle
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/HeroResonanceIndexHandler.java` (def=True, cls=HeroResonanceIndexHandler, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainHandler.java` (def=True, cls=QuickTrainHandler, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainIndexHandler.java` (def=True, cls=QuickTrainIndexHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=True, cls=SoldierStandbyBuyMomoHandler, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyExchangeHandler.java` (def=True, cls=SoldierStandbyExchangeHandler, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamIndexHandler.java` (def=True, cls=TeamIndexHandler, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksIndexHandler.java` (def=True, cls=TeamLooksIndexHandler, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksStarUpgradeHandler.java` (def=True, cls=TeamLooksStarUpgradeHandler, n=1)
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUnlockHandler.java` (def=True, cls=TeamLooksUnlockHandler, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUseHandler.java` (def=True, cls=TeamLooksUseHandler, n=1)

### fengshen-code-147  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-148  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=reGenGet，Gold=ReGenMoaServiceImpl#reGenGet
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/ReGenMoaServiceImpl.java` (def=True, cls=ReGenMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IReGenMoaService.java` (def=False, cls=IReGenMoaService, n=1)

### fengshen-code-149  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析：方法符号=handle，Gold=RetryHandler#handle
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/HeroResonanceIndexHandler.java` (def=True, cls=HeroResonanceIndexHandler, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainHandler.java` (def=True, cls=QuickTrainHandler, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainIndexHandler.java` (def=True, cls=QuickTrainIndexHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=True, cls=SoldierStandbyBuyMomoHandler, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyExchangeHandler.java` (def=True, cls=SoldierStandbyExchangeHandler, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamIndexHandler.java` (def=True, cls=TeamIndexHandler, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksIndexHandler.java` (def=True, cls=TeamLooksIndexHandler, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksStarUpgradeHandler.java` (def=True, cls=TeamLooksStarUpgradeHandler, n=1)
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUnlockHandler.java` (def=True, cls=TeamLooksUnlockHandler, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUseHandler.java` (def=True, cls=TeamLooksUseHandler, n=1)

### fengshen-code-150  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=handleBuildOccupy，Gold=SkillPluginCommon#handleBuildOccupy
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/skill/SkillPluginCommon.java` (def=True, cls=SkillPluginCommon, n=2) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/skill/SkillPluginRoutine.java` (def=False, cls=SkillPluginRoutine, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/skill/SkillPluginWar.java` (def=False, cls=SkillPluginWar, n=1)

### fengshen-code-151  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-152  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析：方法符号=handle，Gold=StageCreateHandler#handle
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/HeroResonanceIndexHandler.java` (def=True, cls=HeroResonanceIndexHandler, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainHandler.java` (def=True, cls=QuickTrainHandler, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainIndexHandler.java` (def=True, cls=QuickTrainIndexHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=True, cls=SoldierStandbyBuyMomoHandler, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyExchangeHandler.java` (def=True, cls=SoldierStandbyExchangeHandler, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamIndexHandler.java` (def=True, cls=TeamIndexHandler, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksIndexHandler.java` (def=True, cls=TeamLooksIndexHandler, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksStarUpgradeHandler.java` (def=True, cls=TeamLooksStarUpgradeHandler, n=1)
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUnlockHandler.java` (def=True, cls=TeamLooksUnlockHandler, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUseHandler.java` (def=True, cls=TeamLooksUseHandler, n=1)

### fengshen-code-153  [REQUIREMENT_TO_CODE]  Gold排名=2
- 解析：方法符号=init，Gold=StageGlobalService#init
- Top-10：
  1. [208] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/build/BuildPluginWar.java` (def=True, cls=BuildPluginWar, n=8)
  2. [204] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/stage/StageGlobalService.java` (def=True, cls=StageGlobalService, n=4) ★★符号+文件命中
  3. [203] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/bean/stage/StageWorldConfig.java` (def=True, cls=StageWorldConfig, n=3)
  4. [203] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/build/BuildPluginCommon.java` (def=True, cls=BuildPluginCommon, n=3)
  5. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/bean/limitPack/LimitPackContext.java` (def=True, cls=LimitPackContext, n=2)
  6. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/bean/vip/CommonRechargeContext.java` (def=True, cls=CommonRechargeContext, n=2)
  7. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/moa/DelayQueueMoaService.java` (def=True, cls=DelayQueueMoaService, n=2)
  8. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/hunyuan/HunyuanPackModel.java` (def=True, cls=HunyuanPackModel, n=2)
  9. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/growDiscount/GrowDiscountModel.java` (def=True, cls=GrowDiscountModel, n=2)
  10. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/bean/stage/StageWorld.java` (def=True, cls=StageWorld, n=2)

### fengshen-code-154  [REQUIREMENT_TO_CODE]  Gold排名=8
- 解析：方法符号=handle，Gold=TeamLooksStarUpgradeHandler#handle
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/HeroResonanceIndexHandler.java` (def=True, cls=HeroResonanceIndexHandler, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainHandler.java` (def=True, cls=QuickTrainHandler, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainIndexHandler.java` (def=True, cls=QuickTrainIndexHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=True, cls=SoldierStandbyBuyMomoHandler, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyExchangeHandler.java` (def=True, cls=SoldierStandbyExchangeHandler, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamIndexHandler.java` (def=True, cls=TeamIndexHandler, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksIndexHandler.java` (def=True, cls=TeamLooksIndexHandler, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksStarUpgradeHandler.java` (def=True, cls=TeamLooksStarUpgradeHandler, n=1) ★★符号+文件命中
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUnlockHandler.java` (def=True, cls=TeamLooksUnlockHandler, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUseHandler.java` (def=True, cls=TeamLooksUseHandler, n=1)

### fengshen-code-155  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-156  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析：方法符号=handle，Gold=TeamRaidMarchHandler#handle
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/HeroResonanceIndexHandler.java` (def=True, cls=HeroResonanceIndexHandler, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainHandler.java` (def=True, cls=QuickTrainHandler, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainIndexHandler.java` (def=True, cls=QuickTrainIndexHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=True, cls=SoldierStandbyBuyMomoHandler, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyExchangeHandler.java` (def=True, cls=SoldierStandbyExchangeHandler, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamIndexHandler.java` (def=True, cls=TeamIndexHandler, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksIndexHandler.java` (def=True, cls=TeamLooksIndexHandler, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksStarUpgradeHandler.java` (def=True, cls=TeamLooksStarUpgradeHandler, n=1)
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUnlockHandler.java` (def=True, cls=TeamLooksUnlockHandler, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUseHandler.java` (def=True, cls=TeamLooksUseHandler, n=1)

### fengshen-code-157  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析：方法符号=handle，Gold=TeamMarchSpeedHandler#handle
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/HeroResonanceIndexHandler.java` (def=True, cls=HeroResonanceIndexHandler, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainHandler.java` (def=True, cls=QuickTrainHandler, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainIndexHandler.java` (def=True, cls=QuickTrainIndexHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=True, cls=SoldierStandbyBuyMomoHandler, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyExchangeHandler.java` (def=True, cls=SoldierStandbyExchangeHandler, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamIndexHandler.java` (def=True, cls=TeamIndexHandler, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksIndexHandler.java` (def=True, cls=TeamLooksIndexHandler, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksStarUpgradeHandler.java` (def=True, cls=TeamLooksStarUpgradeHandler, n=1)
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUnlockHandler.java` (def=True, cls=TeamLooksUnlockHandler, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUseHandler.java` (def=True, cls=TeamLooksUseHandler, n=1)

### fengshen-code-158  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=initWorldTeams，Gold=TeamPluginCommon#initWorldTeams
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/team/TeamPluginCommon.java` (def=True, cls=TeamPluginCommon, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/bean/stage/StageWorld.java` (def=False, cls=StageWorld, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/team/ITeamPlugin.java` (def=False, cls=ITeamPlugin, n=1)

### fengshen-code-159  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=teamRemovedAutoSave，Gold=TeamPluginCommon#teamRemovedAutoSave
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/team/TeamPluginCommon.java` (def=True, cls=TeamPluginCommon, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/team/ITeamPlugin.java` (def=False, cls=ITeamPlugin, n=1)

### fengshen-code-160  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=testClearUnionInfo，Gold=TestMoaServiceImpl#testClearUnionInfo
- Top-10：
  1. [203] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/TestMoaServiceImpl.java` (def=True, cls=TestMoaServiceImpl, n=3) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/union/UnionQuitModel.java` (def=True, cls=UnionQuitModel, n=1)
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/ITestMoaService.java` (def=False, cls=ITestMoaService, n=1)

### fengshen-code-161  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=tickSendFarmChestOpenFinishMsg，Gold=TickMoaServiceImpl#tickSendFarmChestOpenFinishMsg
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/TickMoaServiceImpl.java` (def=True, cls=TickMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/ITickMoaService.java` (def=False, cls=ITickMoaService, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/enums/CallBackMethod.java` (def=False, cls=CallBackMethod, n=1)

### fengshen-code-162  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=commandTowerFight，Gold=TowerMoaServiceImpl#commandTowerFight
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/TowerMoaServiceImpl.java` (def=True, cls=TowerMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/ITowerMoaService.java` (def=False, cls=ITowerMoaService, n=1)

### fengshen-code-163  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=turntableLottery，Gold=TurntableMoaServiceImpl#turntableLottery
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/TurntableMoaServiceImpl.java` (def=True, cls=TurntableMoaServiceImpl, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/turntable/TurntableModel.java` (def=True, cls=TurntableModel, n=1)
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/activity/ITurntableMoaService.java` (def=False, cls=ITurntableMoaService, n=1)

### fengshen-code-164  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=turntableTaskList，Gold=TurntableMoaServiceImpl#turntableTaskList
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/TurntableMoaServiceImpl.java` (def=True, cls=TurntableMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/activity/ITurntableMoaService.java` (def=False, cls=ITurntableMoaService, n=1)

### fengshen-code-165  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=breakRole，Gold=RoleMoaServiceImpl#breakRole
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/RoleMoaServiceImpl.java` (def=True, cls=RoleMoaServiceImpl, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/role/RoleModel.java` (def=True, cls=RoleModel, n=1)
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IRoleMoaService.java` (def=False, cls=IRoleMoaService, n=1)

### fengshen-code-166  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=roleLooksUse，Gold=RoleLooksMoaServiceImpl#roleLooksUse
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/RoleLooksMoaServiceImpl.java` (def=True, cls=RoleLooksMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IRoleLooksService.java` (def=False, cls=IRoleLooksService, n=1)

### fengshen-code-167  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=petFusionChoose，Gold=PetMoaServiceImpl#petFusionChoose
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/PetMoaServiceImpl.java` (def=True, cls=PetMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IPetMoaService.java` (def=False, cls=IPetMoaService, n=1)

### fengshen-code-168  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=petPutOn，Gold=PetMoaServiceImpl#petPutOn
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/PetMoaServiceImpl.java` (def=True, cls=PetMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IPetMoaService.java` (def=False, cls=IPetMoaService, n=1)

### fengshen-code-169  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=petSummonRefresh，Gold=PetMoaServiceImpl#petSummonRefresh
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/PetMoaServiceImpl.java` (def=True, cls=PetMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IPetMoaService.java` (def=False, cls=IPetMoaService, n=1)

### fengshen-code-170  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-171  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-172  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=refresh，Gold=PetService#refresh
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/pet/PetService.java` (def=True, cls=PetService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/PetMoaServiceImpl.java` (def=False, cls=PetMoaServiceImpl, n=1)

### fengshen-code-173  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=chooseKeep，Gold=PetFusionService#chooseKeep
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/pet/PetFusionService.java` (def=True, cls=PetFusionService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/PetMoaServiceImpl.java` (def=False, cls=PetMoaServiceImpl, n=1)

### fengshen-code-174  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=spiritMark，Gold=SpiritMoaServiceImpl#spiritMark
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/SpiritMoaServiceImpl.java` (def=True, cls=SpiritMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/ISpiritMoaService.java` (def=False, cls=ISpiritMoaService, n=1)

### fengshen-code-175  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=canAffixUnlock，Gold=SpiritService#canAffixUnlock
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/spirit/SpiritService.java` (def=True, cls=SpiritService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/SpiritMoaServiceImpl.java` (def=False, cls=SpiritMoaServiceImpl, n=1)

### fengshen-code-176  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-177  [REQUIREMENT_TO_CODE]  Gold排名=2
- 解析：方法符号=receiveTaskActivePrize，Gold=TaskMoaServiceImpl#receiveTaskActivePrize
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/crazydig/CrazyDigService.java` (def=True, cls=CrazyDigService, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/TaskMoaServiceImpl.java` (def=True, cls=TaskMoaServiceImpl, n=1) ★★符号+文件命中
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/ITaskMoaService.java` (def=False, cls=ITaskMoaService, n=1)
  4. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/CrazyDigMoaServiceImpl.java` (def=False, cls=CrazyDigMoaServiceImpl, n=1)
  5. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/CrazyDigMoaServiceImplV2.java` (def=False, cls=CrazyDigMoaServiceImplV2, n=1)

### fengshen-code-178  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-179  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析：方法符号=handle，Gold=CompactRefuseInviteHandler#handle
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/HeroResonanceIndexHandler.java` (def=True, cls=HeroResonanceIndexHandler, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainHandler.java` (def=True, cls=QuickTrainHandler, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainIndexHandler.java` (def=True, cls=QuickTrainIndexHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=True, cls=SoldierStandbyBuyMomoHandler, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyExchangeHandler.java` (def=True, cls=SoldierStandbyExchangeHandler, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamIndexHandler.java` (def=True, cls=TeamIndexHandler, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksIndexHandler.java` (def=True, cls=TeamLooksIndexHandler, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksStarUpgradeHandler.java` (def=True, cls=TeamLooksStarUpgradeHandler, n=1)
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUnlockHandler.java` (def=True, cls=TeamLooksUnlockHandler, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUseHandler.java` (def=True, cls=TeamLooksUseHandler, n=1)

### fengshen-code-180  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-181  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=inviteList，Gold=CompactPluginCommon#inviteList
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/compact/CompactPluginCommon.java` (def=True, cls=CompactPluginCommon, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/compact/ICompactPlugin.java` (def=False, cls=ICompactPlugin, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/compact/CompactInviteListHandler.java` (def=False, cls=CompactInviteListHandler, n=1)

### fengshen-code-182  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-183  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=queryWorldShopIndex，Gold=ShopMoaServiceImpl#queryWorldShopIndex
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/ShopMoaServiceImpl.java` (def=True, cls=ShopMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IShopMoaService.java` (def=False, cls=IShopMoaService, n=1)

### fengshen-code-184  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=testLotteryScripte，Gold=WorldLotteryMoaServiceImpl#testLotteryScripte
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/impl/WorldLotteryMoaServiceImpl.java` (def=True, cls=WorldLotteryMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/service/IWorldLotteryMoaService.java` (def=False, cls=IWorldLotteryMoaService, n=1)

### fengshen-code-185  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-186  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=receiveTaskRewardAll，Gold=WorldTaskMoaServiceImpl#receiveTaskRewardAll
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/impl/WorldTaskMoaServiceImpl.java` (def=True, cls=WorldTaskMoaServiceImpl, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/lottery/WorldTaskModel.java` (def=True, cls=WorldTaskModel, n=1)
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/service/IWorldTaskMoaService.java` (def=False, cls=IWorldTaskMoaService, n=1)

### fengshen-code-187  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析：方法符号=handle，Gold=WorldFightRewardHandler#handle
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/HeroResonanceIndexHandler.java` (def=True, cls=HeroResonanceIndexHandler, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainHandler.java` (def=True, cls=QuickTrainHandler, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainIndexHandler.java` (def=True, cls=QuickTrainIndexHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=True, cls=SoldierStandbyBuyMomoHandler, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyExchangeHandler.java` (def=True, cls=SoldierStandbyExchangeHandler, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamIndexHandler.java` (def=True, cls=TeamIndexHandler, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksIndexHandler.java` (def=True, cls=TeamLooksIndexHandler, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksStarUpgradeHandler.java` (def=True, cls=TeamLooksStarUpgradeHandler, n=1)
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUnlockHandler.java` (def=True, cls=TeamLooksUnlockHandler, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUseHandler.java` (def=True, cls=TeamLooksUseHandler, n=1)

### fengshen-code-188  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-189  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-190  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析：方法符号=handle，Gold=BuildKillRankHandler#handle
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/HeroResonanceIndexHandler.java` (def=True, cls=HeroResonanceIndexHandler, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainHandler.java` (def=True, cls=QuickTrainHandler, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainIndexHandler.java` (def=True, cls=QuickTrainIndexHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=True, cls=SoldierStandbyBuyMomoHandler, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyExchangeHandler.java` (def=True, cls=SoldierStandbyExchangeHandler, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamIndexHandler.java` (def=True, cls=TeamIndexHandler, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksIndexHandler.java` (def=True, cls=TeamLooksIndexHandler, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksStarUpgradeHandler.java` (def=True, cls=TeamLooksStarUpgradeHandler, n=1)
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUnlockHandler.java` (def=True, cls=TeamLooksUnlockHandler, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUseHandler.java` (def=True, cls=TeamLooksUseHandler, n=1)

### fengshen-code-191  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=doExecuteCancelFocusFire，Gold=BuildPluginCommon#doExecuteCancelFocusFire
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/build/BuildPluginCommon.java` (def=True, cls=BuildPluginCommon, n=2) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/build/IBuildPlugin.java` (def=False, cls=IBuildPlugin, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/compact/CompactPluginWar.java` (def=False, cls=CompactPluginWar, n=1)

### fengshen-code-192  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-193  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-194  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-195  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=receivePveReward，Gold=FightMoaServiceImpl#receivePveReward
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/FightMoaServiceImpl.java` (def=True, cls=FightMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IFightMoaService.java` (def=False, cls=IFightMoaService, n=1)

### fengshen-code-196  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=clearFightUser，Gold=FightPluginCommon#clearFightUser
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/fight/FightPluginCommon.java` (def=True, cls=FightPluginCommon, n=2) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/fight/IFightPlugin.java` (def=False, cls=IFightPlugin, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/user/UserPluginCommon.java` (def=False, cls=UserPluginCommon, n=1)

### fengshen-code-197  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=fightKillUserEvent，Gold=FightPluginCommon#fightKillUserEvent
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/fight/FightPluginCommon.java` (def=True, cls=FightPluginCommon, n=2) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/fight/FightPluginWar.java` (def=False, cls=FightPluginWar, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/fight/IFightPlugin.java` (def=False, cls=IFightPlugin, n=1)

### fengshen-code-198  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=addTestRankData，Gold=RankMoaServiceImpl#addTestRankData
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/RankMoaServiceImpl.java` (def=True, cls=RankMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IRankMoaService.java` (def=False, cls=IRankMoaService, n=1)

### fengshen-code-199  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-200  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=packBuy，Gold=ActivityCommonPackService#packBuy
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/activity/ActivityCommonPackService.java` (def=True, cls=ActivityCommonPackService, n=1) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/destiny/DestinyService.java` (def=True, cls=DestinyService, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/ActivityCommonMoaServiceImpl.java` (def=False, cls=ActivityCommonMoaServiceImpl, n=1)
  4. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/DestinyMoaServiceImpl.java` (def=False, cls=DestinyMoaServiceImpl, n=1)

### fengshen-code-201  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-202  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=canBuy，Gold=ActivityLimitPackService#canBuy
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/activityLimitPack/ActivityLimitPackService.java` (def=True, cls=ActivityLimitPackService, n=2) ★★符号+文件命中
  2. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/growFund/GrowFundService.java` (def=True, cls=GrowFundService, n=2)
  3. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/regen/ReGenPluginCommon.java` (def=True, cls=ReGenPluginCommon, n=2)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/destiny/DestinyPackModel.java` (def=True, cls=DestinyPackModel, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/growFund/GrowFundModel.java` (def=True, cls=GrowFundModel, n=1)
  6. [3] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/reGen/ReGenModel.java` (def=False, cls=ReGenModel, n=3)
  7. [2] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/GrowFundMoaServiceImpl.java` (def=False, cls=GrowFundMoaServiceImpl, n=2)
  8. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/response/reGen/ReGenNode.java` (def=False, cls=ReGenNode, n=1)
  9. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/destiny/DestinyService.java` (def=False, cls=DestinyService, n=1)
  10. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/ActivityLimitPackMoaServiceImpl.java` (def=False, cls=ActivityLimitPackMoaServiceImpl, n=1)

### fengshen-code-203  [REQUIREMENT_TO_CODE]  Gold排名=2
- 解析：方法符号=hunyuanRankList，Gold=HunyuanService#hunyuanRankList
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/HunyuanMoaServiceImpl.java` (def=True, cls=HunyuanMoaServiceImpl, n=2)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/hunyuan/HunyuanService.java` (def=True, cls=HunyuanService, n=1) ★★符号+文件命中
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/activity/IHunyuanMoaService.java` (def=False, cls=IHunyuanMoaService, n=1)

### fengshen-code-204  [REQUIREMENT_TO_CODE]  Gold排名=3
- 解析：方法符号=hunyuanPackIndex，Gold=HunyuanMoaServiceImpl#hunyuanPackIndex
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/hunyuan/HunyuanPackModel.java` (def=True, cls=HunyuanPackModel, n=2)
  2. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/hunyuan/HunyuanService.java` (def=True, cls=HunyuanService, n=2)
  3. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/HunyuanMoaServiceImpl.java` (def=True, cls=HunyuanMoaServiceImpl, n=2) ★★符号+文件命中
  4. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/activity/IHunyuanMoaService.java` (def=False, cls=IHunyuanMoaService, n=1)

### fengshen-code-205  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-206  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-207  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=receiveTaskRewardCrazyDig，Gold=CrazyDigMoaServiceImpl#receiveTaskRewardCrazyDig
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/CrazyDigMoaServiceImpl.java` (def=True, cls=CrazyDigMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/activity/ICrazyDigMoaService.java` (def=False, cls=ICrazyDigMoaService, n=1)

### fengshen-code-208  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=clearMineData，Gold=MinePluginCommon#clearMineData
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/mine/MinePluginCommon.java` (def=True, cls=MinePluginCommon, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/bean/stage/StageWorldRoutine.java` (def=False, cls=StageWorldRoutine, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/bean/stage/StageWorldWar.java` (def=False, cls=StageWorldWar, n=1)
  4. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/mine/IMinePlugin.java` (def=False, cls=IMinePlugin, n=1)

### fengshen-code-209  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=onTeamLeaveCollect，Gold=MinePluginCommon#onTeamLeaveCollect
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/mine/MinePluginCommon.java` (def=True, cls=MinePluginCommon, n=1) ★★符号+文件命中
  2. [4] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/march/MarchPluginCommon.java` (def=False, cls=MarchPluginCommon, n=4)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/enums/MineLeaveType.java` (def=False, cls=MineLeaveType, n=1)
  4. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/march/IMarchPlugin.java` (def=False, cls=IMarchPlugin, n=1)
  5. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/mine/IMinePlugin.java` (def=False, cls=IMinePlugin, n=1)

### fengshen-code-210  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=rewardMine，Gold=MinePluginCommon#rewardMine
- Top-10：
  1. [203] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/mine/MinePluginCommon.java` (def=True, cls=MinePluginCommon, n=3) ★★符号+文件命中

### fengshen-code-211  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-212  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-213  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-214  [REQUIREMENT_TO_CODE]  Gold排名=2
- 解析：方法符号=queryChatIndex，Gold=ChatMoaServiceImpl#queryChatIndex
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/chat/ChatModel.java` (def=True, cls=ChatModel, n=2)
  2. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/impl/ChatMoaServiceImpl.java` (def=True, cls=ChatMoaServiceImpl, n=2) ★★符号+文件命中
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/chat/base/IChatHandler.java` (def=True, cls=IChatHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/chat/base/handlers/WorldChatHandler.java` (def=True, cls=WorldChatHandler, n=1)
  5. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/service/IChatMoaService.java` (def=False, cls=IChatMoaService, n=1)

### fengshen-code-215  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=linkageZhuGongReceive，Gold=LinkageMoaServiceImpl#linkageZhuGongReceive
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/LinkageMoaServiceImpl.java` (def=True, cls=LinkageMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/activity/ILinkageMoaService.java` (def=False, cls=ILinkageMoaService, n=1)

### fengshen-code-216  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=commandUnionApplyDirect，Gold=UnionMoaServiceImpl#commandUnionApplyDirect
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/UnionMoaServiceImpl.java` (def=True, cls=UnionMoaServiceImpl, n=2) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IUnionMoaService.java` (def=False, cls=IUnionMoaService, n=1)

### fengshen-code-217  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=commandUnionModifyAnnc，Gold=UnionMoaServiceImpl#commandUnionModifyAnnc
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/UnionMoaServiceImpl.java` (def=True, cls=UnionMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IUnionMoaService.java` (def=False, cls=IUnionMoaService, n=1)

### fengshen-code-218  [REQUIREMENT_TO_CODE]  Gold排名=2
- 解析：方法符号=queryMomoGroupInfo，Gold=UnionMoaServiceImpl#queryMomoGroupInfo
- Top-10：
  1. [203] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/union/UnionModifyModel.java` (def=True, cls=UnionModifyModel, n=3)
  2. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/UnionMoaServiceImpl.java` (def=True, cls=UnionMoaServiceImpl, n=2) ★★符号+文件命中
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IUnionMoaService.java` (def=False, cls=IUnionMoaService, n=1)

### fengshen-code-219  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=queryUnionTaskList，Gold=UnionMoaServiceImpl#queryUnionTaskList
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/UnionMoaServiceImpl.java` (def=True, cls=UnionMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IUnionMoaService.java` (def=False, cls=IUnionMoaService, n=1)

### fengshen-code-220  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-221  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=unionAstrolabeDonate，Gold=UnionAstrolabeMoaServiceImpl#unionAstrolabeDonate
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/UnionAstrolabeMoaServiceImpl.java` (def=True, cls=UnionAstrolabeMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IUnionAstrolabeMoaService.java` (def=False, cls=IUnionAstrolabeMoaService, n=1)

### fengshen-code-222  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析：方法符号=handle，Gold=UnionDisbandHandler#handle
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/HeroResonanceIndexHandler.java` (def=True, cls=HeroResonanceIndexHandler, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainHandler.java` (def=True, cls=QuickTrainHandler, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainIndexHandler.java` (def=True, cls=QuickTrainIndexHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=True, cls=SoldierStandbyBuyMomoHandler, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyExchangeHandler.java` (def=True, cls=SoldierStandbyExchangeHandler, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamIndexHandler.java` (def=True, cls=TeamIndexHandler, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksIndexHandler.java` (def=True, cls=TeamLooksIndexHandler, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksStarUpgradeHandler.java` (def=True, cls=TeamLooksStarUpgradeHandler, n=1)
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUnlockHandler.java` (def=True, cls=TeamLooksUnlockHandler, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUseHandler.java` (def=True, cls=TeamLooksUseHandler, n=1)

### fengshen-code-223  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=boxAdminIndex，Gold=UnionWarehouseService#boxAdminIndex
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/union/UnionWarehouseService.java` (def=True, cls=UnionWarehouseService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/UnionMoaServiceImpl.java` (def=False, cls=UnionMoaServiceImpl, n=1)

### fengshen-code-224  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析：方法符号=index，Gold=UnionTitleService#index
- Top-10：
  1. [203] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/activityMaze/ActivityMazeService.java` (def=True, cls=ActivityMazeService, n=3)
  2. [203] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/spirit/SpiritService.java` (def=True, cls=SpiritService, n=3)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/union/UnionWarehouseModel.java` (def=True, cls=UnionWarehouseModel, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/activityLimitPack/ActivityLimitPackService.java` (def=True, cls=ActivityLimitPackService, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/announce/AnnounceService.java` (def=True, cls=AnnounceService, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/artifact/ArtifactService.java` (def=True, cls=ArtifactService, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/compact/WorldCompactService.java` (def=True, cls=WorldCompactService, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/destiny/DestinyService.java` (def=True, cls=DestinyService, n=1)
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/growFund/GrowFundService.java` (def=True, cls=GrowFundService, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/linkage/LinkageService.java` (def=True, cls=LinkageService, n=1)

### fengshen-code-225  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=querySingleUnionJobInfo，Gold=UnionPluginCommon#querySingleUnionJobInfo
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/union/UnionPluginCommon.java` (def=True, cls=UnionPluginCommon, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/union/UnionService.java` (def=True, cls=UnionService, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/lottery/LotteryModel.java` (def=False, cls=LotteryModel, n=1)
  4. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/union/IUnionPlugin.java` (def=False, cls=IUnionPlugin, n=1)

### fengshen-code-226  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-227  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-228  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-229  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-230  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-231  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-232  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-233  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=heroAwakeIndex，Gold=HeroMoaServiceImpl#heroAwakeIndex
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/HeroMoaServiceImpl.java` (def=True, cls=HeroMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IHeroMoaService.java` (def=False, cls=IHeroMoaService, n=1)

### fengshen-code-234  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-235  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=heroEquipMergeIndex，Gold=HeroMoaServiceImpl#heroEquipMergeIndex
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/HeroMoaServiceImpl.java` (def=True, cls=HeroMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IHeroMoaService.java` (def=False, cls=IHeroMoaService, n=1)

### fengshen-code-236  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-237  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=heroRealmBreakthrough，Gold=HeroMoaServiceImpl#heroRealmBreakthrough
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/HeroMoaServiceImpl.java` (def=True, cls=HeroMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IHeroMoaService.java` (def=False, cls=IHeroMoaService, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/event/EventKey.java` (def=False, cls=EventKey, n=1)

### fengshen-code-238  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=heroStarUpgrade，Gold=HeroMoaServiceImpl#heroStarUpgrade
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/HeroMoaServiceImpl.java` (def=True, cls=HeroMoaServiceImpl, n=2) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IHeroMoaService.java` (def=False, cls=IHeroMoaService, n=1)

### fengshen-code-239  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-240  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-241  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=smelt，Gold=HeroSmeltService#smelt
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/hero/HeroSmeltService.java` (def=True, cls=HeroSmeltService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/HeroMoaServiceImpl.java` (def=False, cls=HeroMoaServiceImpl, n=1)

### fengshen-code-242  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-243  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=canGuardMarch，Gold=MarchPluginCommon#canGuardMarch
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/march/MarchPluginCommon.java` (def=True, cls=MarchPluginCommon, n=2) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/march/IMarchPlugin.java` (def=False, cls=IMarchPlugin, n=1)

### fengshen-code-244  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=collectMarch，Gold=MarchPluginCommon#collectMarch
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/march/MarchPluginCommon.java` (def=True, cls=MarchPluginCommon, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/march/IMarchPlugin.java` (def=False, cls=IMarchPlugin, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/march/TeamCollectMarchHandler.java` (def=False, cls=TeamCollectMarchHandler, n=1)

### fengshen-code-245  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-246  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=buyShop，Gold=StandardService#buyShop
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/standard/StandardService.java` (def=True, cls=StandardService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/StandardMoaServiceImpl.java` (def=False, cls=StandardMoaServiceImpl, n=1)

### fengshen-code-247  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=itemUseBatch，Gold=ItemMoaServiceImpl#itemUseBatch
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/ItemMoaServiceImpl.java` (def=True, cls=ItemMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IItemMoaService.java` (def=False, cls=IItemMoaService, n=1)

### fengshen-code-248  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-249  [REQUIREMENT_TO_CODE]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-250  [REQUIREMENT_TO_CODE]  Gold排名=1
- 解析：方法符号=useItemsByConfigId，Gold=ItemService#useItemsByConfigId
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/item/ItemService.java` (def=True, cls=ItemService, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/item/ItemManger.java` (def=True, cls=ItemManger, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/ItemMoaServiceImpl.java` (def=False, cls=ItemMoaServiceImpl, n=1)

### fengshen-code-251  [SYMBOL]  Gold排名=1
- 解析：方法符号=queryVipShopIndex，Gold=VipMoaServiceImpl#queryVipShopIndex
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/VipMoaServiceImpl.java` (def=True, cls=VipMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IVipMoaService.java` (def=False, cls=IVipMoaService, n=1)

### fengshen-code-252  [SYMBOL]  Gold排名=1
- 解析：方法符号=vipUsePrivilege，Gold=VipMoaServiceImpl#vipUsePrivilege
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/VipMoaServiceImpl.java` (def=True, cls=VipMoaServiceImpl, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/vip/VipService.java` (def=True, cls=VipService, n=1)
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IVipMoaService.java` (def=False, cls=IVipMoaService, n=1)

### fengshen-code-253  [SYMBOL]  Gold排名=2
- 解析：方法符号=vipReceiveGift，Gold=VipService#vipReceiveGift
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/VipMoaServiceImpl.java` (def=True, cls=VipMoaServiceImpl, n=2)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/vip/VipService.java` (def=True, cls=VipService, n=1) ★★符号+文件命中
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IVipMoaService.java` (def=False, cls=IVipMoaService, n=1)

### fengshen-code-254  [SYMBOL]  Gold排名=1
- 解析：方法符号=arenaMatch，Gold=ArenaMoaServiceImpl#arenaMatch
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/ArenaMoaServiceImpl.java` (def=True, cls=ArenaMoaServiceImpl, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/arena/ArenaModel.java` (def=True, cls=ArenaModel, n=1)
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IArenaMoaService.java` (def=False, cls=IArenaMoaService, n=1)

### fengshen-code-255  [SYMBOL]  Gold排名=—
- 解析：方法符号=handle，Gold=BuffActivateHandler#handle
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/HeroResonanceIndexHandler.java` (def=True, cls=HeroResonanceIndexHandler, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainHandler.java` (def=True, cls=QuickTrainHandler, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainIndexHandler.java` (def=True, cls=QuickTrainIndexHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=True, cls=SoldierStandbyBuyMomoHandler, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyExchangeHandler.java` (def=True, cls=SoldierStandbyExchangeHandler, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamIndexHandler.java` (def=True, cls=TeamIndexHandler, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksIndexHandler.java` (def=True, cls=TeamLooksIndexHandler, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksStarUpgradeHandler.java` (def=True, cls=TeamLooksStarUpgradeHandler, n=1)
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUnlockHandler.java` (def=True, cls=TeamLooksUnlockHandler, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUseHandler.java` (def=True, cls=TeamLooksUseHandler, n=1)

### fengshen-code-256  [SYMBOL]  Gold排名=1
- 解析：方法符号=messageDispatchProcess，Gold=C2SMessageService#messageDispatchProcess
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/C2SMessageService.java` (def=True, cls=C2SMessageService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/impl/WorldMessageMoaServiceImpl.java` (def=False, cls=WorldMessageMoaServiceImpl, n=1)

### fengshen-code-257  [SYMBOL]  Gold排名=1
- 解析：方法符号=canChestExchange，Gold=FarmService#canChestExchange
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/farm/FarmService.java` (def=True, cls=FarmService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/FarmMoaServiceImpl.java` (def=False, cls=FarmMoaServiceImpl, n=1)

### fengshen-code-258  [SYMBOL]  Gold排名=1
- 解析：方法符号=canDig，Gold=FarmService#canDig
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/farm/FarmService.java` (def=True, cls=FarmService, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/farm/FarmModel.java` (def=True, cls=FarmModel, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/FarmMoaServiceImpl.java` (def=False, cls=FarmMoaServiceImpl, n=1)

### fengshen-code-259  [SYMBOL]  Gold排名=1
- 解析：方法符号=chestAccelerate，Gold=FarmService#chestAccelerate
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/farm/FarmService.java` (def=True, cls=FarmService, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/farm/FarmModel.java` (def=True, cls=FarmModel, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/FarmMoaServiceImpl.java` (def=False, cls=FarmMoaServiceImpl, n=1)

### fengshen-code-260  [SYMBOL]  Gold排名=1
- 解析：方法符号=chestSteal，Gold=FarmService#chestSteal
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/farm/FarmService.java` (def=True, cls=FarmService, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/farm/FarmModel.java` (def=True, cls=FarmModel, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/FarmMoaServiceImpl.java` (def=False, cls=FarmMoaServiceImpl, n=1)

### fengshen-code-261  [SYMBOL]  Gold排名=2
- 解析：方法符号=levelUpgrade，Gold=FarmService#levelUpgrade
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/artifact/ArtifactService.java` (def=True, cls=ArtifactService, n=2)
  2. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/farm/FarmService.java` (def=True, cls=FarmService, n=2) ★★符号+文件命中
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/artifact/ArtifactModel.java` (def=True, cls=ArtifactModel, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/farm/FarmModel.java` (def=True, cls=FarmModel, n=1)
  5. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/ArtifactMoaServiceImpl.java` (def=False, cls=ArtifactMoaServiceImpl, n=1)
  6. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/FarmMoaServiceImpl.java` (def=False, cls=FarmMoaServiceImpl, n=1)

### fengshen-code-262  [SYMBOL]  Gold排名=1
- 解析：方法符号=farmChestBatchReceive，Gold=FarmMoaServiceImpl#farmChestBatchReceive
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/FarmMoaServiceImpl.java` (def=True, cls=FarmMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IFarmMoaService.java` (def=False, cls=IFarmMoaService, n=1)

### fengshen-code-263  [SYMBOL]  Gold排名=1
- 解析：方法符号=farmChestStealFirst，Gold=FarmMoaServiceImpl#farmChestStealFirst
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/FarmMoaServiceImpl.java` (def=True, cls=FarmMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IFarmMoaService.java` (def=False, cls=IFarmMoaService, n=1)

### fengshen-code-264  [SYMBOL]  Gold排名=1
- 解析：方法符号=farmDigV2，Gold=FarmMoaServiceImpl#farmDigV2
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/FarmMoaServiceImpl.java` (def=True, cls=FarmMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IFarmMoaService.java` (def=False, cls=IFarmMoaService, n=1)

### fengshen-code-265  [SYMBOL]  Gold排名=1
- 解析：方法符号=farmLevelUpgrade，Gold=FarmMoaServiceImpl#farmLevelUpgrade
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/FarmMoaServiceImpl.java` (def=True, cls=FarmMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IFarmMoaService.java` (def=False, cls=IFarmMoaService, n=1)

### fengshen-code-266  [SYMBOL]  Gold排名=1
- 解析：方法符号=addMineRewardsHisWithType，Gold=HistoryPluginCommon#addMineRewardsHisWithType
- Top-10：
  1. [203] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/history/HistoryPluginCommon.java` (def=True, cls=HistoryPluginCommon, n=3) ★★符号+文件命中

### fengshen-code-267  [SYMBOL]  Gold排名=1
- 解析：方法符号=handleFocus，Gold=HistoryPluginCommon#handleFocus
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/history/HistoryPluginCommon.java` (def=True, cls=HistoryPluginCommon, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/history/HistoryPluginRoutine.java` (def=False, cls=HistoryPluginRoutine, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/history/HistoryPluginWar.java` (def=False, cls=HistoryPluginWar, n=1)

### fengshen-code-268  [SYMBOL]  Gold排名=1
- 解析：方法符号=handleSkillDestroyCity，Gold=HistoryPluginCommon#handleSkillDestroyCity
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/history/HistoryPluginCommon.java` (def=True, cls=HistoryPluginCommon, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/history/HistoryPluginRoutine.java` (def=False, cls=HistoryPluginRoutine, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/history/HistoryPluginWar.java` (def=False, cls=HistoryPluginWar, n=1)

### fengshen-code-269  [SYMBOL]  Gold排名=1
- 解析：方法符号=receiveBoardReward，Gold=MainIndexMoaServiceImpl#receiveBoardReward
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/MainIndexMoaServiceImpl.java` (def=True, cls=MainIndexMoaServiceImpl, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/mainidex/MainIndexModel.java` (def=True, cls=MainIndexModel, n=1)
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IMainIndexMoaService.java` (def=False, cls=IMainIndexMoaService, n=1)

### fengshen-code-270  [SYMBOL]  Gold排名=1
- 解析：方法符号=commandOfflineQuick，Gold=OfflineMoaServiceImpl#commandOfflineQuick
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/OfflineMoaServiceImpl.java` (def=True, cls=OfflineMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IOfflineMoaService.java` (def=False, cls=IOfflineMoaService, n=1)

### fengshen-code-271  [SYMBOL]  Gold排名=—
- 解析：方法符号=handle，Gold=PongHandler#handle
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/HeroResonanceIndexHandler.java` (def=True, cls=HeroResonanceIndexHandler, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainHandler.java` (def=True, cls=QuickTrainHandler, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainIndexHandler.java` (def=True, cls=QuickTrainIndexHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=True, cls=SoldierStandbyBuyMomoHandler, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyExchangeHandler.java` (def=True, cls=SoldierStandbyExchangeHandler, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamIndexHandler.java` (def=True, cls=TeamIndexHandler, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksIndexHandler.java` (def=True, cls=TeamLooksIndexHandler, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksStarUpgradeHandler.java` (def=True, cls=TeamLooksStarUpgradeHandler, n=1)
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUnlockHandler.java` (def=True, cls=TeamLooksUnlockHandler, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUseHandler.java` (def=True, cls=TeamLooksUseHandler, n=1)

### fengshen-code-272  [SYMBOL]  Gold排名=1
- 解析：方法符号=sendMessageByRoomId，Gold=QchatMoaService#sendMessageByRoomId
- Top-10：
  1. [203] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/moa/QchatMoaService.java` (def=True, cls=QchatMoaService, n=3) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/message/QchatMessageModel.java` (def=False, cls=QchatMessageModel, n=1)

### fengshen-code-273  [SYMBOL]  Gold排名=1
- 解析：方法符号=reGenGet，Gold=ReGenMoaServiceImpl#reGenGet
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/ReGenMoaServiceImpl.java` (def=True, cls=ReGenMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IReGenMoaService.java` (def=False, cls=IReGenMoaService, n=1)

### fengshen-code-274  [SYMBOL]  Gold排名=—
- 解析：方法符号=handle，Gold=RetryHandler#handle
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/HeroResonanceIndexHandler.java` (def=True, cls=HeroResonanceIndexHandler, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainHandler.java` (def=True, cls=QuickTrainHandler, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainIndexHandler.java` (def=True, cls=QuickTrainIndexHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=True, cls=SoldierStandbyBuyMomoHandler, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyExchangeHandler.java` (def=True, cls=SoldierStandbyExchangeHandler, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamIndexHandler.java` (def=True, cls=TeamIndexHandler, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksIndexHandler.java` (def=True, cls=TeamLooksIndexHandler, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksStarUpgradeHandler.java` (def=True, cls=TeamLooksStarUpgradeHandler, n=1)
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUnlockHandler.java` (def=True, cls=TeamLooksUnlockHandler, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUseHandler.java` (def=True, cls=TeamLooksUseHandler, n=1)

### fengshen-code-275  [SYMBOL]  Gold排名=1
- 解析：方法符号=handleBuildOccupy，Gold=SkillPluginCommon#handleBuildOccupy
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/skill/SkillPluginCommon.java` (def=True, cls=SkillPluginCommon, n=2) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/skill/SkillPluginRoutine.java` (def=False, cls=SkillPluginRoutine, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/skill/SkillPluginWar.java` (def=False, cls=SkillPluginWar, n=1)

### fengshen-code-276  [SYMBOL]  Gold排名=1
- 解析：方法符号=buyMomoSoldier，Gold=SoldierPluginCommon#buyMomoSoldier
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/soldier/SoldierPluginCommon.java` (def=True, cls=SoldierPluginCommon, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/soldier/ISoldierPlugin.java` (def=False, cls=ISoldierPlugin, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=False, cls=SoldierStandbyBuyMomoHandler, n=1)

### fengshen-code-277  [SYMBOL]  Gold排名=—
- 解析：方法符号=handle，Gold=StageCreateHandler#handle
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/HeroResonanceIndexHandler.java` (def=True, cls=HeroResonanceIndexHandler, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainHandler.java` (def=True, cls=QuickTrainHandler, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainIndexHandler.java` (def=True, cls=QuickTrainIndexHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=True, cls=SoldierStandbyBuyMomoHandler, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyExchangeHandler.java` (def=True, cls=SoldierStandbyExchangeHandler, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamIndexHandler.java` (def=True, cls=TeamIndexHandler, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksIndexHandler.java` (def=True, cls=TeamLooksIndexHandler, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksStarUpgradeHandler.java` (def=True, cls=TeamLooksStarUpgradeHandler, n=1)
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUnlockHandler.java` (def=True, cls=TeamLooksUnlockHandler, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUseHandler.java` (def=True, cls=TeamLooksUseHandler, n=1)

### fengshen-code-278  [SYMBOL]  Gold排名=2
- 解析：方法符号=init，Gold=StageGlobalService#init
- Top-10：
  1. [208] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/build/BuildPluginWar.java` (def=True, cls=BuildPluginWar, n=8)
  2. [204] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/stage/StageGlobalService.java` (def=True, cls=StageGlobalService, n=4) ★★符号+文件命中
  3. [203] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/bean/stage/StageWorldConfig.java` (def=True, cls=StageWorldConfig, n=3)
  4. [203] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/build/BuildPluginCommon.java` (def=True, cls=BuildPluginCommon, n=3)
  5. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/bean/limitPack/LimitPackContext.java` (def=True, cls=LimitPackContext, n=2)
  6. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/bean/vip/CommonRechargeContext.java` (def=True, cls=CommonRechargeContext, n=2)
  7. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/moa/DelayQueueMoaService.java` (def=True, cls=DelayQueueMoaService, n=2)
  8. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/hunyuan/HunyuanPackModel.java` (def=True, cls=HunyuanPackModel, n=2)
  9. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/growDiscount/GrowDiscountModel.java` (def=True, cls=GrowDiscountModel, n=2)
  10. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/bean/stage/StageWorld.java` (def=True, cls=StageWorld, n=2)

### fengshen-code-279  [SYMBOL]  Gold排名=8
- 解析：方法符号=handle，Gold=TeamLooksStarUpgradeHandler#handle
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/HeroResonanceIndexHandler.java` (def=True, cls=HeroResonanceIndexHandler, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainHandler.java` (def=True, cls=QuickTrainHandler, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainIndexHandler.java` (def=True, cls=QuickTrainIndexHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=True, cls=SoldierStandbyBuyMomoHandler, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyExchangeHandler.java` (def=True, cls=SoldierStandbyExchangeHandler, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamIndexHandler.java` (def=True, cls=TeamIndexHandler, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksIndexHandler.java` (def=True, cls=TeamLooksIndexHandler, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksStarUpgradeHandler.java` (def=True, cls=TeamLooksStarUpgradeHandler, n=1) ★★符号+文件命中
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUnlockHandler.java` (def=True, cls=TeamLooksUnlockHandler, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUseHandler.java` (def=True, cls=TeamLooksUseHandler, n=1)

### fengshen-code-280  [SYMBOL]  Gold排名=5
- 解析：方法符号=canUse，Gold=TeamLooksPluginCommon#canUse
- Top-10：
  1. [203] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/item/ItemManger.java` (def=True, cls=ItemManger, n=3)
  2. [203] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/item/ItemService.java` (def=True, cls=ItemService, n=3)
  3. [203] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/skill/SkillPluginCommon.java` (def=True, cls=SkillPluginCommon, n=3)
  4. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/roleLooks/RoleLooksService.java` (def=True, cls=RoleLooksService, n=2)
  5. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/teamlooks/TeamLooksPluginCommon.java` (def=True, cls=TeamLooksPluginCommon, n=2) ★★符号+文件命中
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/roleLooks/RoleLooksModel.java` (def=True, cls=RoleLooksModel, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/spirit/SpiritService.java` (def=True, cls=SpiritService, n=1)
  8. [2] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/common/TaskPrize.java` (def=False, cls=TaskPrize, n=2)
  9. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/ItemMoaServiceImpl.java` (def=False, cls=ItemMoaServiceImpl, n=1)
  10. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/RoleLooksMoaServiceImpl.java` (def=False, cls=RoleLooksMoaServiceImpl, n=1)

### fengshen-code-281  [SYMBOL]  Gold排名=—
- 解析：方法符号=handle，Gold=TeamRaidMarchHandler#handle
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/HeroResonanceIndexHandler.java` (def=True, cls=HeroResonanceIndexHandler, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainHandler.java` (def=True, cls=QuickTrainHandler, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainIndexHandler.java` (def=True, cls=QuickTrainIndexHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=True, cls=SoldierStandbyBuyMomoHandler, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyExchangeHandler.java` (def=True, cls=SoldierStandbyExchangeHandler, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamIndexHandler.java` (def=True, cls=TeamIndexHandler, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksIndexHandler.java` (def=True, cls=TeamLooksIndexHandler, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksStarUpgradeHandler.java` (def=True, cls=TeamLooksStarUpgradeHandler, n=1)
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUnlockHandler.java` (def=True, cls=TeamLooksUnlockHandler, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUseHandler.java` (def=True, cls=TeamLooksUseHandler, n=1)

### fengshen-code-282  [SYMBOL]  Gold排名=—
- 解析：方法符号=handle，Gold=TeamMarchSpeedHandler#handle
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/HeroResonanceIndexHandler.java` (def=True, cls=HeroResonanceIndexHandler, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainHandler.java` (def=True, cls=QuickTrainHandler, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainIndexHandler.java` (def=True, cls=QuickTrainIndexHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=True, cls=SoldierStandbyBuyMomoHandler, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyExchangeHandler.java` (def=True, cls=SoldierStandbyExchangeHandler, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamIndexHandler.java` (def=True, cls=TeamIndexHandler, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksIndexHandler.java` (def=True, cls=TeamLooksIndexHandler, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksStarUpgradeHandler.java` (def=True, cls=TeamLooksStarUpgradeHandler, n=1)
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUnlockHandler.java` (def=True, cls=TeamLooksUnlockHandler, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUseHandler.java` (def=True, cls=TeamLooksUseHandler, n=1)

### fengshen-code-283  [SYMBOL]  Gold排名=1
- 解析：方法符号=initWorldTeams，Gold=TeamPluginCommon#initWorldTeams
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/team/TeamPluginCommon.java` (def=True, cls=TeamPluginCommon, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/bean/stage/StageWorld.java` (def=False, cls=StageWorld, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/team/ITeamPlugin.java` (def=False, cls=ITeamPlugin, n=1)

### fengshen-code-284  [SYMBOL]  Gold排名=1
- 解析：方法符号=teamRemovedAutoSave，Gold=TeamPluginCommon#teamRemovedAutoSave
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/team/TeamPluginCommon.java` (def=True, cls=TeamPluginCommon, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/team/ITeamPlugin.java` (def=False, cls=ITeamPlugin, n=1)

### fengshen-code-285  [SYMBOL]  Gold排名=1
- 解析：方法符号=testClearUnionInfo，Gold=TestMoaServiceImpl#testClearUnionInfo
- Top-10：
  1. [203] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/TestMoaServiceImpl.java` (def=True, cls=TestMoaServiceImpl, n=3) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/union/UnionQuitModel.java` (def=True, cls=UnionQuitModel, n=1)
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/ITestMoaService.java` (def=False, cls=ITestMoaService, n=1)

### fengshen-code-286  [SYMBOL]  Gold排名=1
- 解析：方法符号=tickSendFarmChestOpenFinishMsg，Gold=TickMoaServiceImpl#tickSendFarmChestOpenFinishMsg
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/TickMoaServiceImpl.java` (def=True, cls=TickMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/ITickMoaService.java` (def=False, cls=ITickMoaService, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/enums/CallBackMethod.java` (def=False, cls=CallBackMethod, n=1)

### fengshen-code-287  [SYMBOL]  Gold排名=1
- 解析：方法符号=commandTowerFight，Gold=TowerMoaServiceImpl#commandTowerFight
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/TowerMoaServiceImpl.java` (def=True, cls=TowerMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/ITowerMoaService.java` (def=False, cls=ITowerMoaService, n=1)

### fengshen-code-288  [SYMBOL]  Gold排名=1
- 解析：方法符号=turntableLottery，Gold=TurntableMoaServiceImpl#turntableLottery
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/TurntableMoaServiceImpl.java` (def=True, cls=TurntableMoaServiceImpl, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/turntable/TurntableModel.java` (def=True, cls=TurntableModel, n=1)
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/activity/ITurntableMoaService.java` (def=False, cls=ITurntableMoaService, n=1)

### fengshen-code-289  [SYMBOL]  Gold排名=1
- 解析：方法符号=turntableTaskList，Gold=TurntableMoaServiceImpl#turntableTaskList
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/TurntableMoaServiceImpl.java` (def=True, cls=TurntableMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/activity/ITurntableMoaService.java` (def=False, cls=ITurntableMoaService, n=1)

### fengshen-code-290  [SYMBOL]  Gold排名=1
- 解析：方法符号=breakRole，Gold=RoleMoaServiceImpl#breakRole
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/RoleMoaServiceImpl.java` (def=True, cls=RoleMoaServiceImpl, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/role/RoleModel.java` (def=True, cls=RoleModel, n=1)
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IRoleMoaService.java` (def=False, cls=IRoleMoaService, n=1)

### fengshen-code-291  [SYMBOL]  Gold排名=1
- 解析：方法符号=roleLooksUse，Gold=RoleLooksMoaServiceImpl#roleLooksUse
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/RoleLooksMoaServiceImpl.java` (def=True, cls=RoleLooksMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IRoleLooksService.java` (def=False, cls=IRoleLooksService, n=1)

### fengshen-code-292  [SYMBOL]  Gold排名=1
- 解析：方法符号=petFusionChoose，Gold=PetMoaServiceImpl#petFusionChoose
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/PetMoaServiceImpl.java` (def=True, cls=PetMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IPetMoaService.java` (def=False, cls=IPetMoaService, n=1)

### fengshen-code-293  [SYMBOL]  Gold排名=1
- 解析：方法符号=petPutOn，Gold=PetMoaServiceImpl#petPutOn
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/PetMoaServiceImpl.java` (def=True, cls=PetMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IPetMoaService.java` (def=False, cls=IPetMoaService, n=1)

### fengshen-code-294  [SYMBOL]  Gold排名=1
- 解析：方法符号=petSummonRefresh，Gold=PetMoaServiceImpl#petSummonRefresh
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/PetMoaServiceImpl.java` (def=True, cls=PetMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IPetMoaService.java` (def=False, cls=IPetMoaService, n=1)

### fengshen-code-295  [SYMBOL]  Gold排名=1
- 解析：方法符号=canMergeByParam，Gold=PetService#canMergeByParam
- Top-10：
  1. [205] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/pet/PetService.java` (def=True, cls=PetService, n=5) ★★符号+文件命中

### fengshen-code-296  [SYMBOL]  Gold排名=1
- 解析：方法符号=findMergeTarget，Gold=PetService#findMergeTarget
- Top-10：
  1. [206] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/pet/PetService.java` (def=True, cls=PetService, n=6) ★★符号+文件命中

### fengshen-code-297  [SYMBOL]  Gold排名=1
- 解析：方法符号=refresh，Gold=PetService#refresh
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/pet/PetService.java` (def=True, cls=PetService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/PetMoaServiceImpl.java` (def=False, cls=PetMoaServiceImpl, n=1)

### fengshen-code-298  [SYMBOL]  Gold排名=1
- 解析：方法符号=chooseKeep，Gold=PetFusionService#chooseKeep
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/pet/PetFusionService.java` (def=True, cls=PetFusionService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/PetMoaServiceImpl.java` (def=False, cls=PetMoaServiceImpl, n=1)

### fengshen-code-299  [SYMBOL]  Gold排名=1
- 解析：方法符号=spiritMark，Gold=SpiritMoaServiceImpl#spiritMark
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/SpiritMoaServiceImpl.java` (def=True, cls=SpiritMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/ISpiritMoaService.java` (def=False, cls=ISpiritMoaService, n=1)

### fengshen-code-300  [SYMBOL]  Gold排名=1
- 解析：方法符号=canAffixUnlock，Gold=SpiritService#canAffixUnlock
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/spirit/SpiritService.java` (def=True, cls=SpiritService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/SpiritMoaServiceImpl.java` (def=False, cls=SpiritMoaServiceImpl, n=1)

### fengshen-code-301  [SYMBOL]  Gold排名=1
- 解析：方法符号=refine，Gold=SpiritService#refine
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/spirit/SpiritService.java` (def=True, cls=SpiritService, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/spirit/SpiritModel.java` (def=True, cls=SpiritModel, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/SpiritMoaServiceImpl.java` (def=False, cls=SpiritMoaServiceImpl, n=1)

### fengshen-code-302  [SYMBOL]  Gold排名=2
- 解析：方法符号=receiveTaskActivePrize，Gold=TaskMoaServiceImpl#receiveTaskActivePrize
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/crazydig/CrazyDigService.java` (def=True, cls=CrazyDigService, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/TaskMoaServiceImpl.java` (def=True, cls=TaskMoaServiceImpl, n=1) ★★符号+文件命中
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/ITaskMoaService.java` (def=False, cls=ITaskMoaService, n=1)
  4. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/CrazyDigMoaServiceImpl.java` (def=False, cls=CrazyDigMoaServiceImpl, n=1)
  5. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/CrazyDigMoaServiceImplV2.java` (def=False, cls=CrazyDigMoaServiceImplV2, n=1)

### fengshen-code-303  [SYMBOL]  Gold排名=2
- 解析：方法符号=checkActivityStatus，Gold=AnnounceService#checkActivityStatus
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/announce/AnnounceModel.java` (def=True, cls=AnnounceModel, n=2)
  2. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/announce/AnnounceService.java` (def=True, cls=AnnounceService, n=2) ★★符号+文件命中
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/TimingMoaServiceImpl.java` (def=False, cls=TimingMoaServiceImpl, n=1)

### fengshen-code-304  [SYMBOL]  Gold排名=—
- 解析：方法符号=handle，Gold=CompactRefuseInviteHandler#handle
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/HeroResonanceIndexHandler.java` (def=True, cls=HeroResonanceIndexHandler, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainHandler.java` (def=True, cls=QuickTrainHandler, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainIndexHandler.java` (def=True, cls=QuickTrainIndexHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=True, cls=SoldierStandbyBuyMomoHandler, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyExchangeHandler.java` (def=True, cls=SoldierStandbyExchangeHandler, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamIndexHandler.java` (def=True, cls=TeamIndexHandler, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksIndexHandler.java` (def=True, cls=TeamLooksIndexHandler, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksStarUpgradeHandler.java` (def=True, cls=TeamLooksStarUpgradeHandler, n=1)
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUnlockHandler.java` (def=True, cls=TeamLooksUnlockHandler, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUseHandler.java` (def=True, cls=TeamLooksUseHandler, n=1)

### fengshen-code-305  [SYMBOL]  Gold排名=1
- 解析：方法符号=canKick，Gold=CompactPluginCommon#canKick
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/compact/CompactPluginCommon.java` (def=True, cls=CompactPluginCommon, n=2) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/compact/ICompactPlugin.java` (def=False, cls=ICompactPlugin, n=1)

### fengshen-code-306  [SYMBOL]  Gold排名=1
- 解析：方法符号=inviteList，Gold=CompactPluginCommon#inviteList
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/compact/CompactPluginCommon.java` (def=True, cls=CompactPluginCommon, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/compact/ICompactPlugin.java` (def=False, cls=ICompactPlugin, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/compact/CompactInviteListHandler.java` (def=False, cls=CompactInviteListHandler, n=1)

### fengshen-code-307  [SYMBOL]  Gold排名=1
- 解析：方法符号=sendInvite，Gold=CompactPluginCommon#sendInvite
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/compact/CompactPluginCommon.java` (def=True, cls=CompactPluginCommon, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/compact/ICompactPlugin.java` (def=False, cls=ICompactPlugin, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/compact/CompactSendInviteHandler.java` (def=False, cls=CompactSendInviteHandler, n=1)

### fengshen-code-308  [SYMBOL]  Gold排名=1
- 解析：方法符号=queryWorldShopIndex，Gold=ShopMoaServiceImpl#queryWorldShopIndex
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/ShopMoaServiceImpl.java` (def=True, cls=ShopMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IShopMoaService.java` (def=False, cls=IShopMoaService, n=1)

### fengshen-code-309  [SYMBOL]  Gold排名=1
- 解析：方法符号=testLotteryScripte，Gold=WorldLotteryMoaServiceImpl#testLotteryScripte
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/impl/WorldLotteryMoaServiceImpl.java` (def=True, cls=WorldLotteryMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/service/IWorldLotteryMoaService.java` (def=False, cls=IWorldLotteryMoaService, n=1)

### fengshen-code-310  [SYMBOL]  Gold排名=1
- 解析：方法符号=processTest，Gold=WorldMessageMoaServiceImpl#processTest
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/impl/WorldMessageMoaServiceImpl.java` (def=True, cls=WorldMessageMoaServiceImpl, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/season/RoomShardTestModel.java` (def=True, cls=RoomShardTestModel, n=1)
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/service/IWorldMessageMoaService.java` (def=False, cls=IWorldMessageMoaService, n=1)

### fengshen-code-311  [SYMBOL]  Gold排名=1
- 解析：方法符号=receiveTaskRewardAll，Gold=WorldTaskMoaServiceImpl#receiveTaskRewardAll
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/impl/WorldTaskMoaServiceImpl.java` (def=True, cls=WorldTaskMoaServiceImpl, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/lottery/WorldTaskModel.java` (def=True, cls=WorldTaskModel, n=1)
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/service/IWorldTaskMoaService.java` (def=False, cls=IWorldTaskMoaService, n=1)

### fengshen-code-312  [SYMBOL]  Gold排名=—
- 解析：方法符号=handle，Gold=WorldFightRewardHandler#handle
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/HeroResonanceIndexHandler.java` (def=True, cls=HeroResonanceIndexHandler, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainHandler.java` (def=True, cls=QuickTrainHandler, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainIndexHandler.java` (def=True, cls=QuickTrainIndexHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=True, cls=SoldierStandbyBuyMomoHandler, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyExchangeHandler.java` (def=True, cls=SoldierStandbyExchangeHandler, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamIndexHandler.java` (def=True, cls=TeamIndexHandler, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksIndexHandler.java` (def=True, cls=TeamLooksIndexHandler, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksStarUpgradeHandler.java` (def=True, cls=TeamLooksStarUpgradeHandler, n=1)
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUnlockHandler.java` (def=True, cls=TeamLooksUnlockHandler, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUseHandler.java` (def=True, cls=TeamLooksUseHandler, n=1)

### fengshen-code-313  [SYMBOL]  Gold排名=1
- 解析：方法符号=worldUnionNoticeLike，Gold=WorldUnionNoticeMoaServiceImpl#worldUnionNoticeLike
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/impl/WorldUnionNoticeMoaServiceImpl.java` (def=True, cls=WorldUnionNoticeMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/service/IWorldUnionNoticeMoaService.java` (def=False, cls=IWorldUnionNoticeMoaService, n=1)

### fengshen-code-314  [SYMBOL]  Gold排名=1
- 解析：方法符号=modify，Gold=WorldUnionNoticeService#modify
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/unionNotice/WorldUnionNoticeService.java` (def=True, cls=WorldUnionNoticeService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/impl/WorldUnionNoticeMoaServiceImpl.java` (def=False, cls=WorldUnionNoticeMoaServiceImpl, n=1)

### fengshen-code-315  [SYMBOL]  Gold排名=—
- 解析：方法符号=handle，Gold=BuildKillRankHandler#handle
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/HeroResonanceIndexHandler.java` (def=True, cls=HeroResonanceIndexHandler, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainHandler.java` (def=True, cls=QuickTrainHandler, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainIndexHandler.java` (def=True, cls=QuickTrainIndexHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=True, cls=SoldierStandbyBuyMomoHandler, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyExchangeHandler.java` (def=True, cls=SoldierStandbyExchangeHandler, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamIndexHandler.java` (def=True, cls=TeamIndexHandler, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksIndexHandler.java` (def=True, cls=TeamLooksIndexHandler, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksStarUpgradeHandler.java` (def=True, cls=TeamLooksStarUpgradeHandler, n=1)
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUnlockHandler.java` (def=True, cls=TeamLooksUnlockHandler, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUseHandler.java` (def=True, cls=TeamLooksUseHandler, n=1)

### fengshen-code-316  [SYMBOL]  Gold排名=1
- 解析：方法符号=doExecuteCancelFocusFire，Gold=BuildPluginCommon#doExecuteCancelFocusFire
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/build/BuildPluginCommon.java` (def=True, cls=BuildPluginCommon, n=2) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/build/IBuildPlugin.java` (def=False, cls=IBuildPlugin, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/compact/CompactPluginWar.java` (def=False, cls=CompactPluginWar, n=1)

### fengshen-code-317  [SYMBOL]  Gold排名=2
- 解析：方法符号=canBuy，Gold=GrowFundService#canBuy
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/activityLimitPack/ActivityLimitPackService.java` (def=True, cls=ActivityLimitPackService, n=2)
  2. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/growFund/GrowFundService.java` (def=True, cls=GrowFundService, n=2) ★★符号+文件命中
  3. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/regen/ReGenPluginCommon.java` (def=True, cls=ReGenPluginCommon, n=2)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/destiny/DestinyPackModel.java` (def=True, cls=DestinyPackModel, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/growFund/GrowFundModel.java` (def=True, cls=GrowFundModel, n=1)
  6. [3] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/reGen/ReGenModel.java` (def=False, cls=ReGenModel, n=3)
  7. [2] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/GrowFundMoaServiceImpl.java` (def=False, cls=GrowFundMoaServiceImpl, n=2)
  8. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/response/reGen/ReGenNode.java` (def=False, cls=ReGenNode, n=1)
  9. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/destiny/DestinyService.java` (def=False, cls=DestinyService, n=1)
  10. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/ActivityLimitPackMoaServiceImpl.java` (def=False, cls=ActivityLimitPackMoaServiceImpl, n=1)

### fengshen-code-318  [SYMBOL]  Gold排名=2
- 解析：方法符号=buildRecordList，Gold=WarGoodsRecordService#buildRecordList
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/union/UnionWareHouseRecordModel.java` (def=True, cls=UnionWareHouseRecordModel, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/warGoodsRecord/WarGoodsRecordService.java` (def=True, cls=WarGoodsRecordService, n=1) ★★符号+文件命中
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/UnionMoaServiceImpl.java` (def=False, cls=UnionMoaServiceImpl, n=1)
  4. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/impl/WorldHistoryMoaServiceImpl.java` (def=False, cls=WorldHistoryMoaServiceImpl, n=1)

### fengshen-code-319  [SYMBOL]  Gold排名=1
- 解析：方法符号=towerFight，Gold=FightService#towerFight
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/fight/FightService.java` (def=True, cls=FightService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/bean/tower/TowerFightContext.java` (def=False, cls=TowerFightContext, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/event/EventKey.java` (def=False, cls=EventKey, n=1)

### fengshen-code-320  [SYMBOL]  Gold排名=1
- 解析：方法符号=receivePveReward，Gold=FightMoaServiceImpl#receivePveReward
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/FightMoaServiceImpl.java` (def=True, cls=FightMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IFightMoaService.java` (def=False, cls=IFightMoaService, n=1)

### fengshen-code-321  [SYMBOL]  Gold排名=1
- 解析：方法符号=clearFightUser，Gold=FightPluginCommon#clearFightUser
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/fight/FightPluginCommon.java` (def=True, cls=FightPluginCommon, n=2) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/fight/IFightPlugin.java` (def=False, cls=IFightPlugin, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/user/UserPluginCommon.java` (def=False, cls=UserPluginCommon, n=1)

### fengshen-code-322  [SYMBOL]  Gold排名=1
- 解析：方法符号=fightKillUserEvent，Gold=FightPluginCommon#fightKillUserEvent
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/fight/FightPluginCommon.java` (def=True, cls=FightPluginCommon, n=2) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/fight/FightPluginWar.java` (def=False, cls=FightPluginWar, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/fight/IFightPlugin.java` (def=False, cls=IFightPlugin, n=1)

### fengshen-code-323  [SYMBOL]  Gold排名=1
- 解析：方法符号=addTestRankData，Gold=RankMoaServiceImpl#addTestRankData
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/RankMoaServiceImpl.java` (def=True, cls=RankMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IRankMoaService.java` (def=False, cls=IRankMoaService, n=1)

### fengshen-code-324  [SYMBOL]  Gold排名=2
- 解析：方法符号=moneyTreeIndex，Gold=MoneyTreeService#moneyTreeIndex
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/MoneyTreeMoaServiceImpl.java` (def=True, cls=MoneyTreeMoaServiceImpl, n=2)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/moneyTree/MoneyTreeService.java` (def=True, cls=MoneyTreeService, n=1) ★★符号+文件命中
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IMoneyTreeMoaService.java` (def=False, cls=IMoneyTreeMoaService, n=1)

### fengshen-code-325  [SYMBOL]  Gold排名=1
- 解析：方法符号=packBuy，Gold=ActivityCommonPackService#packBuy
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/activity/ActivityCommonPackService.java` (def=True, cls=ActivityCommonPackService, n=1) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/destiny/DestinyService.java` (def=True, cls=DestinyService, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/ActivityCommonMoaServiceImpl.java` (def=False, cls=ActivityCommonMoaServiceImpl, n=1)
  4. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/DestinyMoaServiceImpl.java` (def=False, cls=DestinyMoaServiceImpl, n=1)

### fengshen-code-326  [SYMBOL]  Gold排名=1
- 解析：方法符号=roll，Gold=ActivityMazeService#roll
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/activityMaze/ActivityMazeService.java` (def=True, cls=ActivityMazeService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/pet/PetFusionModel.java` (def=False, cls=PetFusionModel, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/ActivityMazeMoaServiceImpl.java` (def=False, cls=ActivityMazeMoaServiceImpl, n=1)
  4. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/enums/hubble/Title.java` (def=False, cls=Title, n=1)

### fengshen-code-327  [SYMBOL]  Gold排名=1
- 解析：方法符号=canBuy，Gold=ActivityLimitPackService#canBuy
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/activityLimitPack/ActivityLimitPackService.java` (def=True, cls=ActivityLimitPackService, n=2) ★★符号+文件命中
  2. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/growFund/GrowFundService.java` (def=True, cls=GrowFundService, n=2)
  3. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/regen/ReGenPluginCommon.java` (def=True, cls=ReGenPluginCommon, n=2)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/destiny/DestinyPackModel.java` (def=True, cls=DestinyPackModel, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/growFund/GrowFundModel.java` (def=True, cls=GrowFundModel, n=1)
  6. [3] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/reGen/ReGenModel.java` (def=False, cls=ReGenModel, n=3)
  7. [2] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/GrowFundMoaServiceImpl.java` (def=False, cls=GrowFundMoaServiceImpl, n=2)
  8. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/response/reGen/ReGenNode.java` (def=False, cls=ReGenNode, n=1)
  9. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/destiny/DestinyService.java` (def=False, cls=DestinyService, n=1)
  10. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/ActivityLimitPackMoaServiceImpl.java` (def=False, cls=ActivityLimitPackMoaServiceImpl, n=1)

### fengshen-code-328  [SYMBOL]  Gold排名=2
- 解析：方法符号=hunyuanRankList，Gold=HunyuanService#hunyuanRankList
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/HunyuanMoaServiceImpl.java` (def=True, cls=HunyuanMoaServiceImpl, n=2)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/hunyuan/HunyuanService.java` (def=True, cls=HunyuanService, n=1) ★★符号+文件命中
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/activity/IHunyuanMoaService.java` (def=False, cls=IHunyuanMoaService, n=1)

### fengshen-code-329  [SYMBOL]  Gold排名=3
- 解析：方法符号=hunyuanPackIndex，Gold=HunyuanMoaServiceImpl#hunyuanPackIndex
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/hunyuan/HunyuanPackModel.java` (def=True, cls=HunyuanPackModel, n=2)
  2. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/hunyuan/HunyuanService.java` (def=True, cls=HunyuanService, n=2)
  3. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/HunyuanMoaServiceImpl.java` (def=True, cls=HunyuanMoaServiceImpl, n=2) ★★符号+文件命中
  4. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/activity/IHunyuanMoaService.java` (def=False, cls=IHunyuanMoaService, n=1)

### fengshen-code-330  [SYMBOL]  Gold排名=1
- 解析：方法符号=crazyDigExchange，Gold=CrazyDigService#crazyDigExchange
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/crazydig/CrazyDigService.java` (def=True, cls=CrazyDigService, n=2) ★★符号+文件命中
  2. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/CrazyDigMoaServiceImpl.java` (def=True, cls=CrazyDigMoaServiceImpl, n=2)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/crazyDig/CrazyDigExchangeModel.java` (def=True, cls=CrazyDigExchangeModel, n=1)
  4. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/activity/ICrazyDigMoaService.java` (def=False, cls=ICrazyDigMoaService, n=1)
  5. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/crazyDig/CrazyDigModel.java` (def=False, cls=CrazyDigModel, n=1)
  6. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/CrazyDigMoaServiceImplV2.java` (def=False, cls=CrazyDigMoaServiceImplV2, n=1)

### fengshen-code-331  [SYMBOL]  Gold排名=1
- 解析：方法符号=receiveDayPrize，Gold=CrazyDigService#receiveDayPrize
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/crazydig/CrazyDigService.java` (def=True, cls=CrazyDigService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/CrazyDigMoaServiceImplV2.java` (def=False, cls=CrazyDigMoaServiceImplV2, n=1)

### fengshen-code-332  [SYMBOL]  Gold排名=1
- 解析：方法符号=receiveTaskRewardCrazyDig，Gold=CrazyDigMoaServiceImpl#receiveTaskRewardCrazyDig
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/CrazyDigMoaServiceImpl.java` (def=True, cls=CrazyDigMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/activity/ICrazyDigMoaService.java` (def=False, cls=ICrazyDigMoaService, n=1)

### fengshen-code-333  [SYMBOL]  Gold排名=1
- 解析：方法符号=clearMineData，Gold=MinePluginCommon#clearMineData
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/mine/MinePluginCommon.java` (def=True, cls=MinePluginCommon, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/bean/stage/StageWorldRoutine.java` (def=False, cls=StageWorldRoutine, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/bean/stage/StageWorldWar.java` (def=False, cls=StageWorldWar, n=1)
  4. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/mine/IMinePlugin.java` (def=False, cls=IMinePlugin, n=1)

### fengshen-code-334  [SYMBOL]  Gold排名=1
- 解析：方法符号=onTeamLeaveCollect，Gold=MinePluginCommon#onTeamLeaveCollect
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/mine/MinePluginCommon.java` (def=True, cls=MinePluginCommon, n=1) ★★符号+文件命中
  2. [4] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/march/MarchPluginCommon.java` (def=False, cls=MarchPluginCommon, n=4)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/enums/MineLeaveType.java` (def=False, cls=MineLeaveType, n=1)
  4. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/march/IMarchPlugin.java` (def=False, cls=IMarchPlugin, n=1)
  5. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/mine/IMinePlugin.java` (def=False, cls=IMinePlugin, n=1)

### fengshen-code-335  [SYMBOL]  Gold排名=1
- 解析：方法符号=rewardMine，Gold=MinePluginCommon#rewardMine
- Top-10：
  1. [203] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/mine/MinePluginCommon.java` (def=True, cls=MinePluginCommon, n=3) ★★符号+文件命中

### fengshen-code-336  [SYMBOL]  Gold排名=1
- 解析：方法符号=canWear，Gold=ArtifactService#canWear
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/artifact/ArtifactService.java` (def=True, cls=ArtifactService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/ArtifactMoaServiceImpl.java` (def=False, cls=ArtifactMoaServiceImpl, n=1)

### fengshen-code-337  [SYMBOL]  Gold排名=1
- 解析：方法符号=starIndex，Gold=ArtifactService#starIndex
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/artifact/ArtifactService.java` (def=True, cls=ArtifactService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/ArtifactMoaServiceImpl.java` (def=False, cls=ArtifactMoaServiceImpl, n=1)

### fengshen-code-338  [SYMBOL]  Gold排名=1
- 解析：方法符号=artifactReminderCancel，Gold=ArtifactMoaServiceImpl#artifactReminderCancel
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/ArtifactMoaServiceImpl.java` (def=True, cls=ArtifactMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IArtifactMoaService.java` (def=False, cls=IArtifactMoaService, n=1)

### fengshen-code-339  [SYMBOL]  Gold排名=2
- 解析：方法符号=queryChatIndex，Gold=ChatMoaServiceImpl#queryChatIndex
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/chat/ChatModel.java` (def=True, cls=ChatModel, n=2)
  2. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/impl/ChatMoaServiceImpl.java` (def=True, cls=ChatMoaServiceImpl, n=2) ★★符号+文件命中
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/chat/base/IChatHandler.java` (def=True, cls=IChatHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/chat/base/handlers/WorldChatHandler.java` (def=True, cls=WorldChatHandler, n=1)
  5. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/service/IChatMoaService.java` (def=False, cls=IChatMoaService, n=1)

### fengshen-code-340  [SYMBOL]  Gold排名=1
- 解析：方法符号=linkageZhuGongReceive，Gold=LinkageMoaServiceImpl#linkageZhuGongReceive
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/LinkageMoaServiceImpl.java` (def=True, cls=LinkageMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/activity/ILinkageMoaService.java` (def=False, cls=ILinkageMoaService, n=1)

### fengshen-code-341  [SYMBOL]  Gold排名=1
- 解析：方法符号=commandUnionApplyDirect，Gold=UnionMoaServiceImpl#commandUnionApplyDirect
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/UnionMoaServiceImpl.java` (def=True, cls=UnionMoaServiceImpl, n=2) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IUnionMoaService.java` (def=False, cls=IUnionMoaService, n=1)

### fengshen-code-342  [SYMBOL]  Gold排名=1
- 解析：方法符号=commandUnionModifyAnnc，Gold=UnionMoaServiceImpl#commandUnionModifyAnnc
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/UnionMoaServiceImpl.java` (def=True, cls=UnionMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IUnionMoaService.java` (def=False, cls=IUnionMoaService, n=1)

### fengshen-code-343  [SYMBOL]  Gold排名=2
- 解析：方法符号=queryMomoGroupInfo，Gold=UnionMoaServiceImpl#queryMomoGroupInfo
- Top-10：
  1. [203] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/union/UnionModifyModel.java` (def=True, cls=UnionModifyModel, n=3)
  2. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/UnionMoaServiceImpl.java` (def=True, cls=UnionMoaServiceImpl, n=2) ★★符号+文件命中
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IUnionMoaService.java` (def=False, cls=IUnionMoaService, n=1)

### fengshen-code-344  [SYMBOL]  Gold排名=1
- 解析：方法符号=queryUnionTaskList，Gold=UnionMoaServiceImpl#queryUnionTaskList
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/UnionMoaServiceImpl.java` (def=True, cls=UnionMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IUnionMoaService.java` (def=False, cls=IUnionMoaService, n=1)

### fengshen-code-345  [SYMBOL]  Gold排名=3
- 解析：方法符号=batchQueryUnionUserList，Gold=UnionService#batchQueryUnionUserList
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/dao/redis/UnionDao.java` (def=True, cls=UnionDao, n=2)
  2. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/union/UnionUserModel.java` (def=True, cls=UnionUserModel, n=2)
  3. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/union/UnionService.java` (def=True, cls=UnionService, n=2) ★★符号+文件命中
  4. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/union/UnionPluginCommon.java` (def=False, cls=UnionPluginCommon, n=1)

### fengshen-code-346  [SYMBOL]  Gold排名=1
- 解析：方法符号=unionAstrolabeDonate，Gold=UnionAstrolabeMoaServiceImpl#unionAstrolabeDonate
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/UnionAstrolabeMoaServiceImpl.java` (def=True, cls=UnionAstrolabeMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IUnionAstrolabeMoaService.java` (def=False, cls=IUnionAstrolabeMoaService, n=1)

### fengshen-code-347  [SYMBOL]  Gold排名=—
- 解析：方法符号=handle，Gold=UnionDisbandHandler#handle
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/HeroResonanceIndexHandler.java` (def=True, cls=HeroResonanceIndexHandler, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainHandler.java` (def=True, cls=QuickTrainHandler, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainIndexHandler.java` (def=True, cls=QuickTrainIndexHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=True, cls=SoldierStandbyBuyMomoHandler, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyExchangeHandler.java` (def=True, cls=SoldierStandbyExchangeHandler, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamIndexHandler.java` (def=True, cls=TeamIndexHandler, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksIndexHandler.java` (def=True, cls=TeamLooksIndexHandler, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksStarUpgradeHandler.java` (def=True, cls=TeamLooksStarUpgradeHandler, n=1)
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUnlockHandler.java` (def=True, cls=TeamLooksUnlockHandler, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUseHandler.java` (def=True, cls=TeamLooksUseHandler, n=1)

### fengshen-code-348  [SYMBOL]  Gold排名=1
- 解析：方法符号=boxAdminIndex，Gold=UnionWarehouseService#boxAdminIndex
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/union/UnionWarehouseService.java` (def=True, cls=UnionWarehouseService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/UnionMoaServiceImpl.java` (def=False, cls=UnionMoaServiceImpl, n=1)

### fengshen-code-349  [SYMBOL]  Gold排名=—
- 解析：方法符号=index，Gold=UnionTitleService#index
- Top-10：
  1. [203] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/activityMaze/ActivityMazeService.java` (def=True, cls=ActivityMazeService, n=3)
  2. [203] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/spirit/SpiritService.java` (def=True, cls=SpiritService, n=3)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/union/UnionWarehouseModel.java` (def=True, cls=UnionWarehouseModel, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/activityLimitPack/ActivityLimitPackService.java` (def=True, cls=ActivityLimitPackService, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/announce/AnnounceService.java` (def=True, cls=AnnounceService, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/artifact/ArtifactService.java` (def=True, cls=ArtifactService, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/compact/WorldCompactService.java` (def=True, cls=WorldCompactService, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/destiny/DestinyService.java` (def=True, cls=DestinyService, n=1)
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/growFund/GrowFundService.java` (def=True, cls=GrowFundService, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/linkage/LinkageService.java` (def=True, cls=LinkageService, n=1)

### fengshen-code-350  [SYMBOL]  Gold排名=1
- 解析：方法符号=querySingleUnionJobInfo，Gold=UnionPluginCommon#querySingleUnionJobInfo
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/union/UnionPluginCommon.java` (def=True, cls=UnionPluginCommon, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/union/UnionService.java` (def=True, cls=UnionService, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/lottery/LotteryModel.java` (def=False, cls=LotteryModel, n=1)
  4. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/union/IUnionPlugin.java` (def=False, cls=IUnionPlugin, n=1)

### fengshen-code-351  [SYMBOL]  Gold排名=1
- 解析：方法符号=buildGuildHeroLotteryShowInfos，Gold=HeroService#buildGuildHeroLotteryShowInfos
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/hero/HeroService.java` (def=True, cls=HeroService, n=1) ★★符号+文件命中
  2. [2] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/pve/PveFightModel.java` (def=False, cls=PveFightModel, n=2)
  3. [2] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/farm/FarmService.java` (def=False, cls=FarmService, n=2)
  4. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/FightMoaServiceImpl.java` (def=False, cls=FightMoaServiceImpl, n=1)
  5. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/ItemMoaServiceImpl.java` (def=False, cls=ItemMoaServiceImpl, n=1)

### fengshen-code-352  [SYMBOL]  Gold排名=2
- 解析：方法符号=canReceivePoint，Gold=HeroService#canReceivePoint
- Top-10：
  1. [206] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/hero/HeroModel.java` (def=True, cls=HeroModel, n=6)
  2. [205] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/hero/HeroService.java` (def=True, cls=HeroService, n=5) ★★符号+文件命中
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/response/hero/ResHeroCataloguePoint.java` (def=False, cls=ResHeroCataloguePoint, n=1)
  4. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/response/hero/node/HeroCatalogueNode.java` (def=False, cls=HeroCatalogueNode, n=1)
  5. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/HeroMoaServiceImpl.java` (def=False, cls=HeroMoaServiceImpl, n=1)

### fengshen-code-353  [SYMBOL]  Gold排名=2
- 解析：方法符号=canUpgradeEquip，Gold=HeroService#canUpgradeEquip
- Top-10：
  1. [205] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/hero/HeroEquipModel.java` (def=True, cls=HeroEquipModel, n=5)
  2. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/hero/HeroService.java` (def=True, cls=HeroService, n=2) ★★符号+文件命中
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/HeroMoaServiceImpl.java` (def=False, cls=HeroMoaServiceImpl, n=1)

### fengshen-code-354  [SYMBOL]  Gold排名=1
- 解析：方法符号=equipMergePreview，Gold=HeroService#equipMergePreview
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/hero/HeroService.java` (def=True, cls=HeroService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/HeroMoaServiceImpl.java` (def=False, cls=HeroMoaServiceImpl, n=1)

### fengshen-code-355  [SYMBOL]  Gold排名=1
- 解析：方法符号=putOn，Gold=HeroService#putOn
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/hero/HeroService.java` (def=True, cls=HeroService, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/hero/HeroModel.java` (def=True, cls=HeroModel, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/pet/PetService.java` (def=True, cls=PetService, n=1)
  4. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/request/artifact/ArtifactWearParam.java` (def=False, cls=ArtifactWearParam, n=1)
  5. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/HeroMoaServiceImpl.java` (def=False, cls=HeroMoaServiceImpl, n=1)
  6. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/PetMoaServiceImpl.java` (def=False, cls=PetMoaServiceImpl, n=1)

### fengshen-code-356  [SYMBOL]  Gold排名=1
- 解析：方法符号=takeOff，Gold=HeroService#takeOff
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/hero/HeroService.java` (def=True, cls=HeroService, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/hero/HeroModel.java` (def=True, cls=HeroModel, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/pet/PetService.java` (def=True, cls=PetService, n=1)
  4. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/request/artifact/ArtifactWearParam.java` (def=False, cls=ArtifactWearParam, n=1)
  5. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/HeroMoaServiceImpl.java` (def=False, cls=HeroMoaServiceImpl, n=1)
  6. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/PetMoaServiceImpl.java` (def=False, cls=PetMoaServiceImpl, n=1)

### fengshen-code-357  [SYMBOL]  Gold排名=1
- 解析：方法符号=upgradeEquipAll，Gold=HeroService#upgradeEquipAll
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/hero/HeroService.java` (def=True, cls=HeroService, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/hero/HeroEquipModel.java` (def=True, cls=HeroEquipModel, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/HeroMoaServiceImpl.java` (def=False, cls=HeroMoaServiceImpl, n=1)

### fengshen-code-358  [SYMBOL]  Gold排名=1
- 解析：方法符号=heroAwakeIndex，Gold=HeroMoaServiceImpl#heroAwakeIndex
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/HeroMoaServiceImpl.java` (def=True, cls=HeroMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IHeroMoaService.java` (def=False, cls=IHeroMoaService, n=1)

### fengshen-code-359  [SYMBOL]  Gold排名=1
- 解析：方法符号=heroCultureIndex，Gold=HeroMoaServiceImpl#heroCultureIndex
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/HeroMoaServiceImpl.java` (def=True, cls=HeroMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IHeroMoaService.java` (def=False, cls=IHeroMoaService, n=1)

### fengshen-code-360  [SYMBOL]  Gold排名=1
- 解析：方法符号=heroEquipMergeIndex，Gold=HeroMoaServiceImpl#heroEquipMergeIndex
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/HeroMoaServiceImpl.java` (def=True, cls=HeroMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IHeroMoaService.java` (def=False, cls=IHeroMoaService, n=1)

### fengshen-code-361  [SYMBOL]  Gold排名=1
- 解析：方法符号=heroEquipUpgradeAll，Gold=HeroMoaServiceImpl#heroEquipUpgradeAll
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/HeroMoaServiceImpl.java` (def=True, cls=HeroMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IHeroMoaService.java` (def=False, cls=IHeroMoaService, n=1)

### fengshen-code-362  [SYMBOL]  Gold排名=1
- 解析：方法符号=heroRealmBreakthrough，Gold=HeroMoaServiceImpl#heroRealmBreakthrough
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/HeroMoaServiceImpl.java` (def=True, cls=HeroMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IHeroMoaService.java` (def=False, cls=IHeroMoaService, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/event/EventKey.java` (def=False, cls=EventKey, n=1)

### fengshen-code-363  [SYMBOL]  Gold排名=1
- 解析：方法符号=heroStarUpgrade，Gold=HeroMoaServiceImpl#heroStarUpgrade
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/HeroMoaServiceImpl.java` (def=True, cls=HeroMoaServiceImpl, n=2) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IHeroMoaService.java` (def=False, cls=IHeroMoaService, n=1)

### fengshen-code-364  [SYMBOL]  Gold排名=2
- 解析：方法符号=heroTeamRecommendUse，Gold=HeroMoaServiceImpl#heroTeamRecommendUse
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/hero/HeroService.java` (def=True, cls=HeroService, n=2)
  2. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/HeroMoaServiceImpl.java` (def=True, cls=HeroMoaServiceImpl, n=2) ★★符号+文件命中
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/hero/HeroModel.java` (def=True, cls=HeroModel, n=1)
  4. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IHeroMoaService.java` (def=False, cls=IHeroMoaService, n=1)

### fengshen-code-365  [SYMBOL]  Gold排名=1
- 解析：方法符号=canBreakthrough，Gold=HeroRealmService#canBreakthrough
- Top-10：
  1. [205] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/hero/HeroRealmService.java` (def=True, cls=HeroRealmService, n=5) ★★符号+文件命中
  2. [204] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/hero/HeroRealmModel.java` (def=True, cls=HeroRealmModel, n=4)
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/response/hero/ResHeroRealmIndex.java` (def=False, cls=ResHeroRealmIndex, n=1)
  4. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/HeroMoaServiceImpl.java` (def=False, cls=HeroMoaServiceImpl, n=1)

### fengshen-code-366  [SYMBOL]  Gold排名=1
- 解析：方法符号=smelt，Gold=HeroSmeltService#smelt
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/hero/HeroSmeltService.java` (def=True, cls=HeroSmeltService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/HeroMoaServiceImpl.java` (def=False, cls=HeroMoaServiceImpl, n=1)

### fengshen-code-367  [SYMBOL]  Gold排名=1
- 解析：方法符号=unlink，Gold=HeroLinkService#unlink
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/hero/HeroLinkService.java` (def=True, cls=HeroLinkService, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/hero/HeroLinkModel.java` (def=True, cls=HeroLinkModel, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/HeroMoaServiceImpl.java` (def=False, cls=HeroMoaServiceImpl, n=1)

### fengshen-code-368  [SYMBOL]  Gold排名=1
- 解析：方法符号=canGuardMarch，Gold=MarchPluginCommon#canGuardMarch
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/march/MarchPluginCommon.java` (def=True, cls=MarchPluginCommon, n=2) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/march/IMarchPlugin.java` (def=False, cls=IMarchPlugin, n=1)

### fengshen-code-369  [SYMBOL]  Gold排名=1
- 解析：方法符号=collectMarch，Gold=MarchPluginCommon#collectMarch
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/march/MarchPluginCommon.java` (def=True, cls=MarchPluginCommon, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/march/IMarchPlugin.java` (def=False, cls=IMarchPlugin, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/march/TeamCollectMarchHandler.java` (def=False, cls=TeamCollectMarchHandler, n=1)

### fengshen-code-370  [SYMBOL]  Gold排名=1
- 解析：方法符号=repatriateDeadToHomeBuild，Gold=MarchPluginCommon#repatriateDeadToHomeBuild
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/march/MarchPluginCommon.java` (def=True, cls=MarchPluginCommon, n=2) ★★符号+文件命中
  2. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/march/MarchPluginWar.java` (def=True, cls=MarchPluginWar, n=2)
  3. [11] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/fight/FightPluginCommon.java` (def=False, cls=FightPluginCommon, n=11)
  4. [3] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/fight/FightPluginWar.java` (def=False, cls=FightPluginWar, n=3)
  5. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/march/IMarchPlugin.java` (def=False, cls=IMarchPlugin, n=1)

### fengshen-code-371  [SYMBOL]  Gold排名=1
- 解析：方法符号=buyShop，Gold=StandardService#buyShop
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/standard/StandardService.java` (def=True, cls=StandardService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/StandardMoaServiceImpl.java` (def=False, cls=StandardMoaServiceImpl, n=1)

### fengshen-code-372  [SYMBOL]  Gold排名=1
- 解析：方法符号=itemUseBatch，Gold=ItemMoaServiceImpl#itemUseBatch
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/ItemMoaServiceImpl.java` (def=True, cls=ItemMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IItemMoaService.java` (def=False, cls=IItemMoaService, n=1)

### fengshen-code-373  [SYMBOL]  Gold排名=1
- 解析：方法符号=canAdd，Gold=ItemService#canAdd
- Top-10：
  1. [207] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/item/ItemService.java` (def=True, cls=ItemService, n=7) ★★符号+文件命中
  2. [205] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/item/ItemManger.java` (def=True, cls=ItemManger, n=5)
  3. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/item/ItemFarmChestBagModel.java` (def=True, cls=ItemFarmChestBagModel, n=2)
  4. [8] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/tower/TowerModel.java` (def=False, cls=TowerModel, n=8)
  5. [4] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/pass/PassModel.java` (def=False, cls=PassModel, n=4)
  6. [4] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/ShopBlackModel.java` (def=False, cls=ShopBlackModel, n=4)
  7. [4] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/AbstractShopHandler.java` (def=False, cls=AbstractShopHandler, n=4)
  8. [4] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/handlers/FarmShopHandler.java` (def=False, cls=FarmShopHandler, n=4)
  9. [4] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/handlers/SevenDayTaskExchangeShopHandler.java` (def=False, cls=SevenDayTaskExchangeShopHandler, n=4)
  10. [3] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/draw/DrawModel.java` (def=False, cls=DrawModel, n=3)

### fengshen-code-374  [SYMBOL]  Gold排名=1
- 解析：方法符号=createItemNodeWithoutBaseInfo，Gold=ItemService#createItemNodeWithoutBaseInfo
- Top-10：
  1. [204] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/item/ItemService.java` (def=True, cls=ItemService, n=4) ★★符号+文件命中
  2. [6] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/draw/DrawModel.java` (def=False, cls=DrawModel, n=6)
  3. [4] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/base/AbstractShopHandler.java` (def=False, cls=AbstractShopHandler, n=4)
  4. [4] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/artifact/ArtifactService.java` (def=False, cls=ArtifactService, n=4)
  5. [3] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/union/UnionModifyModel.java` (def=False, cls=UnionModifyModel, n=3)
  6. [3] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/union/UnionRcmdModel.java` (def=False, cls=UnionRcmdModel, n=3)
  7. [3] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/farm/FarmService.java` (def=False, cls=FarmService, n=3)
  8. [2] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/bean/union/UnionHallContext.java` (def=False, cls=UnionHallContext, n=2)
  9. [2] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/maze/ActivityMazeModel.java` (def=False, cls=ActivityMazeModel, n=2)
  10. [2] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/shop/ShopBlackModel.java` (def=False, cls=ShopBlackModel, n=2)

### fengshen-code-375  [SYMBOL]  Gold排名=1
- 解析：方法符号=useItemsByConfigId，Gold=ItemService#useItemsByConfigId
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/item/ItemService.java` (def=True, cls=ItemService, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/item/ItemManger.java` (def=True, cls=ItemManger, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/ItemMoaServiceImpl.java` (def=False, cls=ItemMoaServiceImpl, n=1)

### fengshen-code-376  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=queryVipShopIndex，Gold=VipMoaServiceImpl#queryVipShopIndex
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/VipMoaServiceImpl.java` (def=True, cls=VipMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IVipMoaService.java` (def=False, cls=IVipMoaService, n=1)

### fengshen-code-377  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=vipUsePrivilege，Gold=VipMoaServiceImpl#vipUsePrivilege
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/VipMoaServiceImpl.java` (def=True, cls=VipMoaServiceImpl, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/vip/VipService.java` (def=True, cls=VipService, n=1)
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IVipMoaService.java` (def=False, cls=IVipMoaService, n=1)

### fengshen-code-378  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-379  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=arenaMatch，Gold=ArenaMoaServiceImpl#arenaMatch
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/ArenaMoaServiceImpl.java` (def=True, cls=ArenaMoaServiceImpl, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/arena/ArenaModel.java` (def=True, cls=ArenaModel, n=1)
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IArenaMoaService.java` (def=False, cls=IArenaMoaService, n=1)

### fengshen-code-380  [BEHAVIOR]  Gold排名=—
- 解析：方法符号=handle，Gold=BuffActivateHandler#handle
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/HeroResonanceIndexHandler.java` (def=True, cls=HeroResonanceIndexHandler, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainHandler.java` (def=True, cls=QuickTrainHandler, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainIndexHandler.java` (def=True, cls=QuickTrainIndexHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=True, cls=SoldierStandbyBuyMomoHandler, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyExchangeHandler.java` (def=True, cls=SoldierStandbyExchangeHandler, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamIndexHandler.java` (def=True, cls=TeamIndexHandler, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksIndexHandler.java` (def=True, cls=TeamLooksIndexHandler, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksStarUpgradeHandler.java` (def=True, cls=TeamLooksStarUpgradeHandler, n=1)
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUnlockHandler.java` (def=True, cls=TeamLooksUnlockHandler, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUseHandler.java` (def=True, cls=TeamLooksUseHandler, n=1)

### fengshen-code-381  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=messageDispatchProcess，Gold=C2SMessageService#messageDispatchProcess
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/C2SMessageService.java` (def=True, cls=C2SMessageService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/impl/WorldMessageMoaServiceImpl.java` (def=False, cls=WorldMessageMoaServiceImpl, n=1)

### fengshen-code-382  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=canChestExchange，Gold=FarmService#canChestExchange
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/farm/FarmService.java` (def=True, cls=FarmService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/FarmMoaServiceImpl.java` (def=False, cls=FarmMoaServiceImpl, n=1)

### fengshen-code-383  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-384  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-385  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-386  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-387  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=farmChestBatchReceive，Gold=FarmMoaServiceImpl#farmChestBatchReceive
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/FarmMoaServiceImpl.java` (def=True, cls=FarmMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IFarmMoaService.java` (def=False, cls=IFarmMoaService, n=1)

### fengshen-code-388  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=farmChestStealFirst，Gold=FarmMoaServiceImpl#farmChestStealFirst
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/FarmMoaServiceImpl.java` (def=True, cls=FarmMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IFarmMoaService.java` (def=False, cls=IFarmMoaService, n=1)

### fengshen-code-389  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=farmDigV2，Gold=FarmMoaServiceImpl#farmDigV2
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/FarmMoaServiceImpl.java` (def=True, cls=FarmMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IFarmMoaService.java` (def=False, cls=IFarmMoaService, n=1)

### fengshen-code-390  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=farmLevelUpgrade，Gold=FarmMoaServiceImpl#farmLevelUpgrade
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/FarmMoaServiceImpl.java` (def=True, cls=FarmMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IFarmMoaService.java` (def=False, cls=IFarmMoaService, n=1)

### fengshen-code-391  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-392  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-393  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=handleSkillDestroyCity，Gold=HistoryPluginCommon#handleSkillDestroyCity
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/history/HistoryPluginCommon.java` (def=True, cls=HistoryPluginCommon, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/history/HistoryPluginRoutine.java` (def=False, cls=HistoryPluginRoutine, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/history/HistoryPluginWar.java` (def=False, cls=HistoryPluginWar, n=1)

### fengshen-code-394  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=receiveBoardReward，Gold=MainIndexMoaServiceImpl#receiveBoardReward
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/MainIndexMoaServiceImpl.java` (def=True, cls=MainIndexMoaServiceImpl, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/mainidex/MainIndexModel.java` (def=True, cls=MainIndexModel, n=1)
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IMainIndexMoaService.java` (def=False, cls=IMainIndexMoaService, n=1)

### fengshen-code-395  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=commandOfflineQuick，Gold=OfflineMoaServiceImpl#commandOfflineQuick
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/OfflineMoaServiceImpl.java` (def=True, cls=OfflineMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IOfflineMoaService.java` (def=False, cls=IOfflineMoaService, n=1)

### fengshen-code-396  [BEHAVIOR]  Gold排名=—
- 解析：方法符号=handle，Gold=PongHandler#handle
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/HeroResonanceIndexHandler.java` (def=True, cls=HeroResonanceIndexHandler, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainHandler.java` (def=True, cls=QuickTrainHandler, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainIndexHandler.java` (def=True, cls=QuickTrainIndexHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=True, cls=SoldierStandbyBuyMomoHandler, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyExchangeHandler.java` (def=True, cls=SoldierStandbyExchangeHandler, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamIndexHandler.java` (def=True, cls=TeamIndexHandler, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksIndexHandler.java` (def=True, cls=TeamLooksIndexHandler, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksStarUpgradeHandler.java` (def=True, cls=TeamLooksStarUpgradeHandler, n=1)
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUnlockHandler.java` (def=True, cls=TeamLooksUnlockHandler, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUseHandler.java` (def=True, cls=TeamLooksUseHandler, n=1)

### fengshen-code-397  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-398  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=reGenGet，Gold=ReGenMoaServiceImpl#reGenGet
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/ReGenMoaServiceImpl.java` (def=True, cls=ReGenMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IReGenMoaService.java` (def=False, cls=IReGenMoaService, n=1)

### fengshen-code-399  [BEHAVIOR]  Gold排名=—
- 解析：方法符号=handle，Gold=RetryHandler#handle
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/HeroResonanceIndexHandler.java` (def=True, cls=HeroResonanceIndexHandler, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainHandler.java` (def=True, cls=QuickTrainHandler, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainIndexHandler.java` (def=True, cls=QuickTrainIndexHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=True, cls=SoldierStandbyBuyMomoHandler, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyExchangeHandler.java` (def=True, cls=SoldierStandbyExchangeHandler, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamIndexHandler.java` (def=True, cls=TeamIndexHandler, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksIndexHandler.java` (def=True, cls=TeamLooksIndexHandler, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksStarUpgradeHandler.java` (def=True, cls=TeamLooksStarUpgradeHandler, n=1)
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUnlockHandler.java` (def=True, cls=TeamLooksUnlockHandler, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUseHandler.java` (def=True, cls=TeamLooksUseHandler, n=1)

### fengshen-code-400  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=handleBuildOccupy，Gold=SkillPluginCommon#handleBuildOccupy
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/skill/SkillPluginCommon.java` (def=True, cls=SkillPluginCommon, n=2) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/skill/SkillPluginRoutine.java` (def=False, cls=SkillPluginRoutine, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/skill/SkillPluginWar.java` (def=False, cls=SkillPluginWar, n=1)

### fengshen-code-401  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-402  [BEHAVIOR]  Gold排名=—
- 解析：方法符号=handle，Gold=StageCreateHandler#handle
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/HeroResonanceIndexHandler.java` (def=True, cls=HeroResonanceIndexHandler, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainHandler.java` (def=True, cls=QuickTrainHandler, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainIndexHandler.java` (def=True, cls=QuickTrainIndexHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=True, cls=SoldierStandbyBuyMomoHandler, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyExchangeHandler.java` (def=True, cls=SoldierStandbyExchangeHandler, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamIndexHandler.java` (def=True, cls=TeamIndexHandler, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksIndexHandler.java` (def=True, cls=TeamLooksIndexHandler, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksStarUpgradeHandler.java` (def=True, cls=TeamLooksStarUpgradeHandler, n=1)
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUnlockHandler.java` (def=True, cls=TeamLooksUnlockHandler, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUseHandler.java` (def=True, cls=TeamLooksUseHandler, n=1)

### fengshen-code-403  [BEHAVIOR]  Gold排名=2
- 解析：方法符号=init，Gold=StageGlobalService#init
- Top-10：
  1. [208] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/build/BuildPluginWar.java` (def=True, cls=BuildPluginWar, n=8)
  2. [204] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/stage/StageGlobalService.java` (def=True, cls=StageGlobalService, n=4) ★★符号+文件命中
  3. [203] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/bean/stage/StageWorldConfig.java` (def=True, cls=StageWorldConfig, n=3)
  4. [203] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/build/BuildPluginCommon.java` (def=True, cls=BuildPluginCommon, n=3)
  5. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/bean/limitPack/LimitPackContext.java` (def=True, cls=LimitPackContext, n=2)
  6. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/bean/vip/CommonRechargeContext.java` (def=True, cls=CommonRechargeContext, n=2)
  7. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/moa/DelayQueueMoaService.java` (def=True, cls=DelayQueueMoaService, n=2)
  8. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/hunyuan/HunyuanPackModel.java` (def=True, cls=HunyuanPackModel, n=2)
  9. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/growDiscount/GrowDiscountModel.java` (def=True, cls=GrowDiscountModel, n=2)
  10. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/bean/stage/StageWorld.java` (def=True, cls=StageWorld, n=2)

### fengshen-code-404  [BEHAVIOR]  Gold排名=8
- 解析：方法符号=handle，Gold=TeamLooksStarUpgradeHandler#handle
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/HeroResonanceIndexHandler.java` (def=True, cls=HeroResonanceIndexHandler, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainHandler.java` (def=True, cls=QuickTrainHandler, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainIndexHandler.java` (def=True, cls=QuickTrainIndexHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=True, cls=SoldierStandbyBuyMomoHandler, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyExchangeHandler.java` (def=True, cls=SoldierStandbyExchangeHandler, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamIndexHandler.java` (def=True, cls=TeamIndexHandler, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksIndexHandler.java` (def=True, cls=TeamLooksIndexHandler, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksStarUpgradeHandler.java` (def=True, cls=TeamLooksStarUpgradeHandler, n=1) ★★符号+文件命中
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUnlockHandler.java` (def=True, cls=TeamLooksUnlockHandler, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUseHandler.java` (def=True, cls=TeamLooksUseHandler, n=1)

### fengshen-code-405  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-406  [BEHAVIOR]  Gold排名=—
- 解析：方法符号=handle，Gold=TeamRaidMarchHandler#handle
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/HeroResonanceIndexHandler.java` (def=True, cls=HeroResonanceIndexHandler, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainHandler.java` (def=True, cls=QuickTrainHandler, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainIndexHandler.java` (def=True, cls=QuickTrainIndexHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=True, cls=SoldierStandbyBuyMomoHandler, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyExchangeHandler.java` (def=True, cls=SoldierStandbyExchangeHandler, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamIndexHandler.java` (def=True, cls=TeamIndexHandler, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksIndexHandler.java` (def=True, cls=TeamLooksIndexHandler, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksStarUpgradeHandler.java` (def=True, cls=TeamLooksStarUpgradeHandler, n=1)
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUnlockHandler.java` (def=True, cls=TeamLooksUnlockHandler, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUseHandler.java` (def=True, cls=TeamLooksUseHandler, n=1)

### fengshen-code-407  [BEHAVIOR]  Gold排名=—
- 解析：方法符号=handle，Gold=TeamMarchSpeedHandler#handle
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/HeroResonanceIndexHandler.java` (def=True, cls=HeroResonanceIndexHandler, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainHandler.java` (def=True, cls=QuickTrainHandler, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainIndexHandler.java` (def=True, cls=QuickTrainIndexHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=True, cls=SoldierStandbyBuyMomoHandler, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyExchangeHandler.java` (def=True, cls=SoldierStandbyExchangeHandler, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamIndexHandler.java` (def=True, cls=TeamIndexHandler, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksIndexHandler.java` (def=True, cls=TeamLooksIndexHandler, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksStarUpgradeHandler.java` (def=True, cls=TeamLooksStarUpgradeHandler, n=1)
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUnlockHandler.java` (def=True, cls=TeamLooksUnlockHandler, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUseHandler.java` (def=True, cls=TeamLooksUseHandler, n=1)

### fengshen-code-408  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=initWorldTeams，Gold=TeamPluginCommon#initWorldTeams
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/team/TeamPluginCommon.java` (def=True, cls=TeamPluginCommon, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/bean/stage/StageWorld.java` (def=False, cls=StageWorld, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/team/ITeamPlugin.java` (def=False, cls=ITeamPlugin, n=1)

### fengshen-code-409  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=teamRemovedAutoSave，Gold=TeamPluginCommon#teamRemovedAutoSave
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/team/TeamPluginCommon.java` (def=True, cls=TeamPluginCommon, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/team/ITeamPlugin.java` (def=False, cls=ITeamPlugin, n=1)

### fengshen-code-410  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=testClearUnionInfo，Gold=TestMoaServiceImpl#testClearUnionInfo
- Top-10：
  1. [203] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/TestMoaServiceImpl.java` (def=True, cls=TestMoaServiceImpl, n=3) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/union/UnionQuitModel.java` (def=True, cls=UnionQuitModel, n=1)
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/ITestMoaService.java` (def=False, cls=ITestMoaService, n=1)

### fengshen-code-411  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=tickSendFarmChestOpenFinishMsg，Gold=TickMoaServiceImpl#tickSendFarmChestOpenFinishMsg
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/TickMoaServiceImpl.java` (def=True, cls=TickMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/ITickMoaService.java` (def=False, cls=ITickMoaService, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/enums/CallBackMethod.java` (def=False, cls=CallBackMethod, n=1)

### fengshen-code-412  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=commandTowerFight，Gold=TowerMoaServiceImpl#commandTowerFight
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/TowerMoaServiceImpl.java` (def=True, cls=TowerMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/ITowerMoaService.java` (def=False, cls=ITowerMoaService, n=1)

### fengshen-code-413  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=turntableLottery，Gold=TurntableMoaServiceImpl#turntableLottery
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/TurntableMoaServiceImpl.java` (def=True, cls=TurntableMoaServiceImpl, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/turntable/TurntableModel.java` (def=True, cls=TurntableModel, n=1)
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/activity/ITurntableMoaService.java` (def=False, cls=ITurntableMoaService, n=1)

### fengshen-code-414  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=turntableTaskList，Gold=TurntableMoaServiceImpl#turntableTaskList
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/TurntableMoaServiceImpl.java` (def=True, cls=TurntableMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/activity/ITurntableMoaService.java` (def=False, cls=ITurntableMoaService, n=1)

### fengshen-code-415  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=breakRole，Gold=RoleMoaServiceImpl#breakRole
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/RoleMoaServiceImpl.java` (def=True, cls=RoleMoaServiceImpl, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/role/RoleModel.java` (def=True, cls=RoleModel, n=1)
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IRoleMoaService.java` (def=False, cls=IRoleMoaService, n=1)

### fengshen-code-416  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=roleLooksUse，Gold=RoleLooksMoaServiceImpl#roleLooksUse
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/RoleLooksMoaServiceImpl.java` (def=True, cls=RoleLooksMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IRoleLooksService.java` (def=False, cls=IRoleLooksService, n=1)

### fengshen-code-417  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=petFusionChoose，Gold=PetMoaServiceImpl#petFusionChoose
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/PetMoaServiceImpl.java` (def=True, cls=PetMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IPetMoaService.java` (def=False, cls=IPetMoaService, n=1)

### fengshen-code-418  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=petPutOn，Gold=PetMoaServiceImpl#petPutOn
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/PetMoaServiceImpl.java` (def=True, cls=PetMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IPetMoaService.java` (def=False, cls=IPetMoaService, n=1)

### fengshen-code-419  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=petSummonRefresh，Gold=PetMoaServiceImpl#petSummonRefresh
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/PetMoaServiceImpl.java` (def=True, cls=PetMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IPetMoaService.java` (def=False, cls=IPetMoaService, n=1)

### fengshen-code-420  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-421  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-422  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=refresh，Gold=PetService#refresh
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/pet/PetService.java` (def=True, cls=PetService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/PetMoaServiceImpl.java` (def=False, cls=PetMoaServiceImpl, n=1)

### fengshen-code-423  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=chooseKeep，Gold=PetFusionService#chooseKeep
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/pet/PetFusionService.java` (def=True, cls=PetFusionService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/PetMoaServiceImpl.java` (def=False, cls=PetMoaServiceImpl, n=1)

### fengshen-code-424  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=spiritMark，Gold=SpiritMoaServiceImpl#spiritMark
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/SpiritMoaServiceImpl.java` (def=True, cls=SpiritMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/ISpiritMoaService.java` (def=False, cls=ISpiritMoaService, n=1)

### fengshen-code-425  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=canAffixUnlock，Gold=SpiritService#canAffixUnlock
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/spirit/SpiritService.java` (def=True, cls=SpiritService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/SpiritMoaServiceImpl.java` (def=False, cls=SpiritMoaServiceImpl, n=1)

### fengshen-code-426  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-427  [BEHAVIOR]  Gold排名=2
- 解析：方法符号=receiveTaskActivePrize，Gold=TaskMoaServiceImpl#receiveTaskActivePrize
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/crazydig/CrazyDigService.java` (def=True, cls=CrazyDigService, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/TaskMoaServiceImpl.java` (def=True, cls=TaskMoaServiceImpl, n=1) ★★符号+文件命中
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/ITaskMoaService.java` (def=False, cls=ITaskMoaService, n=1)
  4. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/CrazyDigMoaServiceImpl.java` (def=False, cls=CrazyDigMoaServiceImpl, n=1)
  5. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/CrazyDigMoaServiceImplV2.java` (def=False, cls=CrazyDigMoaServiceImplV2, n=1)

### fengshen-code-428  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-429  [BEHAVIOR]  Gold排名=—
- 解析：方法符号=handle，Gold=CompactRefuseInviteHandler#handle
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/HeroResonanceIndexHandler.java` (def=True, cls=HeroResonanceIndexHandler, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainHandler.java` (def=True, cls=QuickTrainHandler, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainIndexHandler.java` (def=True, cls=QuickTrainIndexHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=True, cls=SoldierStandbyBuyMomoHandler, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyExchangeHandler.java` (def=True, cls=SoldierStandbyExchangeHandler, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamIndexHandler.java` (def=True, cls=TeamIndexHandler, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksIndexHandler.java` (def=True, cls=TeamLooksIndexHandler, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksStarUpgradeHandler.java` (def=True, cls=TeamLooksStarUpgradeHandler, n=1)
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUnlockHandler.java` (def=True, cls=TeamLooksUnlockHandler, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUseHandler.java` (def=True, cls=TeamLooksUseHandler, n=1)

### fengshen-code-430  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-431  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=inviteList，Gold=CompactPluginCommon#inviteList
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/compact/CompactPluginCommon.java` (def=True, cls=CompactPluginCommon, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/compact/ICompactPlugin.java` (def=False, cls=ICompactPlugin, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/compact/CompactInviteListHandler.java` (def=False, cls=CompactInviteListHandler, n=1)

### fengshen-code-432  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-433  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=queryWorldShopIndex，Gold=ShopMoaServiceImpl#queryWorldShopIndex
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/ShopMoaServiceImpl.java` (def=True, cls=ShopMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IShopMoaService.java` (def=False, cls=IShopMoaService, n=1)

### fengshen-code-434  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=testLotteryScripte，Gold=WorldLotteryMoaServiceImpl#testLotteryScripte
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/impl/WorldLotteryMoaServiceImpl.java` (def=True, cls=WorldLotteryMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/service/IWorldLotteryMoaService.java` (def=False, cls=IWorldLotteryMoaService, n=1)

### fengshen-code-435  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-436  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=receiveTaskRewardAll，Gold=WorldTaskMoaServiceImpl#receiveTaskRewardAll
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/impl/WorldTaskMoaServiceImpl.java` (def=True, cls=WorldTaskMoaServiceImpl, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/lottery/WorldTaskModel.java` (def=True, cls=WorldTaskModel, n=1)
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/service/IWorldTaskMoaService.java` (def=False, cls=IWorldTaskMoaService, n=1)

### fengshen-code-437  [BEHAVIOR]  Gold排名=—
- 解析：方法符号=handle，Gold=WorldFightRewardHandler#handle
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/HeroResonanceIndexHandler.java` (def=True, cls=HeroResonanceIndexHandler, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainHandler.java` (def=True, cls=QuickTrainHandler, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainIndexHandler.java` (def=True, cls=QuickTrainIndexHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=True, cls=SoldierStandbyBuyMomoHandler, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyExchangeHandler.java` (def=True, cls=SoldierStandbyExchangeHandler, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamIndexHandler.java` (def=True, cls=TeamIndexHandler, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksIndexHandler.java` (def=True, cls=TeamLooksIndexHandler, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksStarUpgradeHandler.java` (def=True, cls=TeamLooksStarUpgradeHandler, n=1)
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUnlockHandler.java` (def=True, cls=TeamLooksUnlockHandler, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUseHandler.java` (def=True, cls=TeamLooksUseHandler, n=1)

### fengshen-code-438  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-439  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-440  [BEHAVIOR]  Gold排名=—
- 解析：方法符号=handle，Gold=BuildKillRankHandler#handle
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/HeroResonanceIndexHandler.java` (def=True, cls=HeroResonanceIndexHandler, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainHandler.java` (def=True, cls=QuickTrainHandler, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainIndexHandler.java` (def=True, cls=QuickTrainIndexHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=True, cls=SoldierStandbyBuyMomoHandler, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyExchangeHandler.java` (def=True, cls=SoldierStandbyExchangeHandler, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamIndexHandler.java` (def=True, cls=TeamIndexHandler, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksIndexHandler.java` (def=True, cls=TeamLooksIndexHandler, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksStarUpgradeHandler.java` (def=True, cls=TeamLooksStarUpgradeHandler, n=1)
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUnlockHandler.java` (def=True, cls=TeamLooksUnlockHandler, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUseHandler.java` (def=True, cls=TeamLooksUseHandler, n=1)

### fengshen-code-441  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=doExecuteCancelFocusFire，Gold=BuildPluginCommon#doExecuteCancelFocusFire
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/build/BuildPluginCommon.java` (def=True, cls=BuildPluginCommon, n=2) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/build/IBuildPlugin.java` (def=False, cls=IBuildPlugin, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/compact/CompactPluginWar.java` (def=False, cls=CompactPluginWar, n=1)

### fengshen-code-442  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-443  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-444  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-445  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=receivePveReward，Gold=FightMoaServiceImpl#receivePveReward
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/FightMoaServiceImpl.java` (def=True, cls=FightMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IFightMoaService.java` (def=False, cls=IFightMoaService, n=1)

### fengshen-code-446  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=clearFightUser，Gold=FightPluginCommon#clearFightUser
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/fight/FightPluginCommon.java` (def=True, cls=FightPluginCommon, n=2) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/fight/IFightPlugin.java` (def=False, cls=IFightPlugin, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/user/UserPluginCommon.java` (def=False, cls=UserPluginCommon, n=1)

### fengshen-code-447  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=fightKillUserEvent，Gold=FightPluginCommon#fightKillUserEvent
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/fight/FightPluginCommon.java` (def=True, cls=FightPluginCommon, n=2) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/fight/FightPluginWar.java` (def=False, cls=FightPluginWar, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/fight/IFightPlugin.java` (def=False, cls=IFightPlugin, n=1)

### fengshen-code-448  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=addTestRankData，Gold=RankMoaServiceImpl#addTestRankData
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/RankMoaServiceImpl.java` (def=True, cls=RankMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IRankMoaService.java` (def=False, cls=IRankMoaService, n=1)

### fengshen-code-449  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-450  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=packBuy，Gold=ActivityCommonPackService#packBuy
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/activity/ActivityCommonPackService.java` (def=True, cls=ActivityCommonPackService, n=1) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/destiny/DestinyService.java` (def=True, cls=DestinyService, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/ActivityCommonMoaServiceImpl.java` (def=False, cls=ActivityCommonMoaServiceImpl, n=1)
  4. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/DestinyMoaServiceImpl.java` (def=False, cls=DestinyMoaServiceImpl, n=1)

### fengshen-code-451  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-452  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=canBuy，Gold=ActivityLimitPackService#canBuy
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/activityLimitPack/ActivityLimitPackService.java` (def=True, cls=ActivityLimitPackService, n=2) ★★符号+文件命中
  2. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/growFund/GrowFundService.java` (def=True, cls=GrowFundService, n=2)
  3. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/regen/ReGenPluginCommon.java` (def=True, cls=ReGenPluginCommon, n=2)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/destiny/DestinyPackModel.java` (def=True, cls=DestinyPackModel, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/growFund/GrowFundModel.java` (def=True, cls=GrowFundModel, n=1)
  6. [3] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/reGen/ReGenModel.java` (def=False, cls=ReGenModel, n=3)
  7. [2] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/GrowFundMoaServiceImpl.java` (def=False, cls=GrowFundMoaServiceImpl, n=2)
  8. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/response/reGen/ReGenNode.java` (def=False, cls=ReGenNode, n=1)
  9. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/destiny/DestinyService.java` (def=False, cls=DestinyService, n=1)
  10. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/ActivityLimitPackMoaServiceImpl.java` (def=False, cls=ActivityLimitPackMoaServiceImpl, n=1)

### fengshen-code-453  [BEHAVIOR]  Gold排名=2
- 解析：方法符号=hunyuanRankList，Gold=HunyuanService#hunyuanRankList
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/HunyuanMoaServiceImpl.java` (def=True, cls=HunyuanMoaServiceImpl, n=2)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/hunyuan/HunyuanService.java` (def=True, cls=HunyuanService, n=1) ★★符号+文件命中
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/activity/IHunyuanMoaService.java` (def=False, cls=IHunyuanMoaService, n=1)

### fengshen-code-454  [BEHAVIOR]  Gold排名=3
- 解析：方法符号=hunyuanPackIndex，Gold=HunyuanMoaServiceImpl#hunyuanPackIndex
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/activity/hunyuan/HunyuanPackModel.java` (def=True, cls=HunyuanPackModel, n=2)
  2. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/hunyuan/HunyuanService.java` (def=True, cls=HunyuanService, n=2)
  3. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/HunyuanMoaServiceImpl.java` (def=True, cls=HunyuanMoaServiceImpl, n=2) ★★符号+文件命中
  4. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/activity/IHunyuanMoaService.java` (def=False, cls=IHunyuanMoaService, n=1)

### fengshen-code-455  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-456  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-457  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=receiveTaskRewardCrazyDig，Gold=CrazyDigMoaServiceImpl#receiveTaskRewardCrazyDig
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/CrazyDigMoaServiceImpl.java` (def=True, cls=CrazyDigMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/activity/ICrazyDigMoaService.java` (def=False, cls=ICrazyDigMoaService, n=1)

### fengshen-code-458  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=clearMineData，Gold=MinePluginCommon#clearMineData
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/mine/MinePluginCommon.java` (def=True, cls=MinePluginCommon, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/bean/stage/StageWorldRoutine.java` (def=False, cls=StageWorldRoutine, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/bean/stage/StageWorldWar.java` (def=False, cls=StageWorldWar, n=1)
  4. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/mine/IMinePlugin.java` (def=False, cls=IMinePlugin, n=1)

### fengshen-code-459  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=onTeamLeaveCollect，Gold=MinePluginCommon#onTeamLeaveCollect
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/mine/MinePluginCommon.java` (def=True, cls=MinePluginCommon, n=1) ★★符号+文件命中
  2. [4] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/march/MarchPluginCommon.java` (def=False, cls=MarchPluginCommon, n=4)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/enums/MineLeaveType.java` (def=False, cls=MineLeaveType, n=1)
  4. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/march/IMarchPlugin.java` (def=False, cls=IMarchPlugin, n=1)
  5. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/mine/IMinePlugin.java` (def=False, cls=IMinePlugin, n=1)

### fengshen-code-460  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=rewardMine，Gold=MinePluginCommon#rewardMine
- Top-10：
  1. [203] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/mine/MinePluginCommon.java` (def=True, cls=MinePluginCommon, n=3) ★★符号+文件命中

### fengshen-code-461  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-462  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-463  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-464  [BEHAVIOR]  Gold排名=2
- 解析：方法符号=queryChatIndex，Gold=ChatMoaServiceImpl#queryChatIndex
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/chat/ChatModel.java` (def=True, cls=ChatModel, n=2)
  2. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/impl/ChatMoaServiceImpl.java` (def=True, cls=ChatMoaServiceImpl, n=2) ★★符号+文件命中
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/chat/base/IChatHandler.java` (def=True, cls=IChatHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/chat/base/handlers/WorldChatHandler.java` (def=True, cls=WorldChatHandler, n=1)
  5. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/world/api/service/IChatMoaService.java` (def=False, cls=IChatMoaService, n=1)

### fengshen-code-465  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=linkageZhuGongReceive，Gold=LinkageMoaServiceImpl#linkageZhuGongReceive
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/LinkageMoaServiceImpl.java` (def=True, cls=LinkageMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/activity/ILinkageMoaService.java` (def=False, cls=ILinkageMoaService, n=1)

### fengshen-code-466  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=commandUnionApplyDirect，Gold=UnionMoaServiceImpl#commandUnionApplyDirect
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/UnionMoaServiceImpl.java` (def=True, cls=UnionMoaServiceImpl, n=2) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IUnionMoaService.java` (def=False, cls=IUnionMoaService, n=1)

### fengshen-code-467  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=commandUnionModifyAnnc，Gold=UnionMoaServiceImpl#commandUnionModifyAnnc
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/UnionMoaServiceImpl.java` (def=True, cls=UnionMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IUnionMoaService.java` (def=False, cls=IUnionMoaService, n=1)

### fengshen-code-468  [BEHAVIOR]  Gold排名=2
- 解析：方法符号=queryMomoGroupInfo，Gold=UnionMoaServiceImpl#queryMomoGroupInfo
- Top-10：
  1. [203] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/union/UnionModifyModel.java` (def=True, cls=UnionModifyModel, n=3)
  2. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/UnionMoaServiceImpl.java` (def=True, cls=UnionMoaServiceImpl, n=2) ★★符号+文件命中
  3. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IUnionMoaService.java` (def=False, cls=IUnionMoaService, n=1)

### fengshen-code-469  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=queryUnionTaskList，Gold=UnionMoaServiceImpl#queryUnionTaskList
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/UnionMoaServiceImpl.java` (def=True, cls=UnionMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IUnionMoaService.java` (def=False, cls=IUnionMoaService, n=1)

### fengshen-code-470  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-471  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=unionAstrolabeDonate，Gold=UnionAstrolabeMoaServiceImpl#unionAstrolabeDonate
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/UnionAstrolabeMoaServiceImpl.java` (def=True, cls=UnionAstrolabeMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IUnionAstrolabeMoaService.java` (def=False, cls=IUnionAstrolabeMoaService, n=1)

### fengshen-code-472  [BEHAVIOR]  Gold排名=—
- 解析：方法符号=handle，Gold=UnionDisbandHandler#handle
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/HeroResonanceIndexHandler.java` (def=True, cls=HeroResonanceIndexHandler, n=1)
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainHandler.java` (def=True, cls=QuickTrainHandler, n=1)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/QuickTrainIndexHandler.java` (def=True, cls=QuickTrainIndexHandler, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyBuyMomoHandler.java` (def=True, cls=SoldierStandbyBuyMomoHandler, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/SoldierStandbyExchangeHandler.java` (def=True, cls=SoldierStandbyExchangeHandler, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamIndexHandler.java` (def=True, cls=TeamIndexHandler, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksIndexHandler.java` (def=True, cls=TeamLooksIndexHandler, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksStarUpgradeHandler.java` (def=True, cls=TeamLooksStarUpgradeHandler, n=1)
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUnlockHandler.java` (def=True, cls=TeamLooksUnlockHandler, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/TeamLooksUseHandler.java` (def=True, cls=TeamLooksUseHandler, n=1)

### fengshen-code-473  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=boxAdminIndex，Gold=UnionWarehouseService#boxAdminIndex
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/union/UnionWarehouseService.java` (def=True, cls=UnionWarehouseService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/UnionMoaServiceImpl.java` (def=False, cls=UnionMoaServiceImpl, n=1)

### fengshen-code-474  [BEHAVIOR]  Gold排名=—
- 解析：方法符号=index，Gold=UnionTitleService#index
- Top-10：
  1. [203] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/activityMaze/ActivityMazeService.java` (def=True, cls=ActivityMazeService, n=3)
  2. [203] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/spirit/SpiritService.java` (def=True, cls=SpiritService, n=3)
  3. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/union/UnionWarehouseModel.java` (def=True, cls=UnionWarehouseModel, n=1)
  4. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/activityLimitPack/ActivityLimitPackService.java` (def=True, cls=ActivityLimitPackService, n=1)
  5. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/announce/AnnounceService.java` (def=True, cls=AnnounceService, n=1)
  6. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/artifact/ArtifactService.java` (def=True, cls=ArtifactService, n=1)
  7. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/compact/WorldCompactService.java` (def=True, cls=WorldCompactService, n=1)
  8. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/destiny/DestinyService.java` (def=True, cls=DestinyService, n=1)
  9. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/growFund/GrowFundService.java` (def=True, cls=GrowFundService, n=1)
  10. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/linkage/LinkageService.java` (def=True, cls=LinkageService, n=1)

### fengshen-code-475  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=querySingleUnionJobInfo，Gold=UnionPluginCommon#querySingleUnionJobInfo
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/union/UnionPluginCommon.java` (def=True, cls=UnionPluginCommon, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/union/UnionService.java` (def=True, cls=UnionService, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/model/lottery/LotteryModel.java` (def=False, cls=LotteryModel, n=1)
  4. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/union/IUnionPlugin.java` (def=False, cls=IUnionPlugin, n=1)

### fengshen-code-476  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-477  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-478  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-479  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-480  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-481  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-482  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-483  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=heroAwakeIndex，Gold=HeroMoaServiceImpl#heroAwakeIndex
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/HeroMoaServiceImpl.java` (def=True, cls=HeroMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IHeroMoaService.java` (def=False, cls=IHeroMoaService, n=1)

### fengshen-code-484  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-485  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=heroEquipMergeIndex，Gold=HeroMoaServiceImpl#heroEquipMergeIndex
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/HeroMoaServiceImpl.java` (def=True, cls=HeroMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IHeroMoaService.java` (def=False, cls=IHeroMoaService, n=1)

### fengshen-code-486  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-487  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=heroRealmBreakthrough，Gold=HeroMoaServiceImpl#heroRealmBreakthrough
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/HeroMoaServiceImpl.java` (def=True, cls=HeroMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IHeroMoaService.java` (def=False, cls=IHeroMoaService, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/event/EventKey.java` (def=False, cls=EventKey, n=1)

### fengshen-code-488  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=heroStarUpgrade，Gold=HeroMoaServiceImpl#heroStarUpgrade
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/HeroMoaServiceImpl.java` (def=True, cls=HeroMoaServiceImpl, n=2) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IHeroMoaService.java` (def=False, cls=IHeroMoaService, n=1)

### fengshen-code-489  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-490  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-491  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=smelt，Gold=HeroSmeltService#smelt
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/hero/HeroSmeltService.java` (def=True, cls=HeroSmeltService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/HeroMoaServiceImpl.java` (def=False, cls=HeroMoaServiceImpl, n=1)

### fengshen-code-492  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-493  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=canGuardMarch，Gold=MarchPluginCommon#canGuardMarch
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/march/MarchPluginCommon.java` (def=True, cls=MarchPluginCommon, n=2) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/march/IMarchPlugin.java` (def=False, cls=IMarchPlugin, n=1)

### fengshen-code-494  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=collectMarch，Gold=MarchPluginCommon#collectMarch
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/march/MarchPluginCommon.java` (def=True, cls=MarchPluginCommon, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/plugin/march/IMarchPlugin.java` (def=False, cls=IMarchPlugin, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/world/service/message/handler/c2s/march/TeamCollectMarchHandler.java` (def=False, cls=TeamCollectMarchHandler, n=1)

### fengshen-code-495  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-496  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=buyShop，Gold=StandardService#buyShop
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/standard/StandardService.java` (def=True, cls=StandardService, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/activity/StandardMoaServiceImpl.java` (def=False, cls=StandardMoaServiceImpl, n=1)

### fengshen-code-497  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=itemUseBatch，Gold=ItemMoaServiceImpl#itemUseBatch
- Top-10：
  1. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/ItemMoaServiceImpl.java` (def=True, cls=ItemMoaServiceImpl, n=1) ★★符号+文件命中
  2. [1] `immortal-game-service-api/src/main/java/com/immomo/bizgame/immortal/game/api/service/IItemMoaService.java` (def=False, cls=IItemMoaService, n=1)

### fengshen-code-498  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-499  [BEHAVIOR]  Gold排名=—
- 解析失败（纯中文描述，无方法符号）→ 符号+文件未命中
- Top-10：—（无候选）

### fengshen-code-500  [BEHAVIOR]  Gold排名=1
- 解析：方法符号=useItemsByConfigId，Gold=ItemService#useItemsByConfigId
- Top-10：
  1. [202] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/item/ItemService.java` (def=True, cls=ItemService, n=2) ★★符号+文件命中
  2. [201] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/model/item/ItemManger.java` (def=True, cls=ItemManger, n=1)
  3. [1] `immortal-game-service-impl/src/main/java/com/immomo/bizgame/immortal/game/service/impl/ItemMoaServiceImpl.java` (def=False, cls=ItemMoaServiceImpl, n=1)

---

## 七、结论

- **主口径（配置B 纯符号检索）**：Recall@1=63.2% → @5=72.4% → @10=73.2%，MRR@10=67.6%，nDCG@10=69.0%；曲线真实上升，瓶颈为高频方法名歧义。
- **上界（配置A 类名增强）**：Recall@10=82.0% 但曲线平（@1=@5=@10），因 query 自带类名使符号可解析题全部 rank1、纯中文题全部 miss。
- **90 题纯中文描述型**（解析失败率 18.0%）是两配置共同短板：词法检索无法把中文意图映射到方法符号，需语义/向量检索补齐。
- no-result=0；延迟极低（P50≈0.152ms）得益于内存词法索引。
