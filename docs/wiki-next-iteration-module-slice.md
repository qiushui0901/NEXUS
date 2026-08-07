# NEXUS Wiki 下一迭代：Module 页面纵向闭环

> 前置条件：第一轮已加入 `PageType`、声明级证据 `Claim`、项目概览页、模块页和只读过期检测。
>
> 本轮目标：不继续横向增加页面类型，先完整打通一个 Module 页面的事实抽取、声明证据、Agent 消费、过期传播和草稿重建闭环。

## 1. 当前基础与剩余问题

第一轮已经完成：

- `OVERVIEW / MODULE / FEATURE / API / DATA / VERSION` 页面类型；
- `FULL / PARTIAL / INFERRED / UNSUPPORTED / CONFLICT` 声明支持状态；
- 项目概览页与按源码顶层目录聚合的模块页；
- 功能条目跨文件合并；
- 需求证据内容哈希；
- Git commit、文件 diff 和需求哈希的只读过期检测；
- Wiki 草稿、审核、发布和回滚。

仍未形成完整闭环：

- 模块页主要按目录和现有代码候选聚合，还不是完整的模块事实编译；
- Claim 尚未成为生成和发布的强制质量约束；
- MCP Wiki 响应尚未完整暴露 `pageType`、Claims、支持状态和新鲜度；
- 过期检测只生成报告，不能显式创建更新草稿；
- 代码变化主要按文件命中，没有传播到符号、调用关系和 Claim；
- 审核人仍需阅读整页，缺少 Claim 级变化摘要。

本轮成功标准不是“再增加一种页面”，而是证明：

> 一个 Module 页面可以从真实研发事实中自动编译，每条关键声明有证据，变化后会失效，能够生成待审核更新草稿，并被 Agent 直接消费。

## 2. 本轮范围

### 2.1 包含

1. 构建 `ModuleFactBundle`；
2. 为模块事实注册稳定证据；
3. 生成有 Claim 的 Module 草稿；
4. 增加 Claim 发布质量门；
5. 扩展 REST/MCP Wiki 交付；
6. 从文件级过期升级到符号和 Claim 级传播；
7. 从过期报告显式生成新草稿；
8. 展示 Claim 级差异；
9. 保持人工审核后发布。

### 2.2 不包含

- 不同时完善 Overview、API、Data、Version 全部页面；
- 不重写 Wiki 前端；
- 不引入图数据库；
- 不建设通用企业搜索；
- 不自动发布模型内容；
- 不做无限 Agent 探索；
- 不在本轮全面接入所有语言的 LSP。

## 3. 目标流程

```text
目标项目、模块与 commit
  -> 确定性抽取 ModuleFactBundle
  -> 注册 Evidence Registry
  -> 按 Schema 生成 Claims 和页面草稿
  -> Claim / Evidence 质量校验
  -> 保存草稿
  -> 人工审核
  -> 发布 Module 页面
  -> REST / MCP 消费

代码或需求变化
  -> 过期检测
  -> 找到受影响符号和 Claims
  -> 显式创建更新草稿
  -> 审核后重新发布
```

## 4. ModuleFactBundle

ModuleFactBundle 是页面生成的唯一事实输入。模型不能绕过 Bundle 自由扫描仓库并创造事实。

建议结构：

```json
{
  "projectId": "identity-service",
  "commitSha": "abc123",
  "moduleId": "auth",
  "title": "认证模块",
  "sourceRoots": ["src/main/java/com/example/auth"],
  "packages": [],
  "entryPoints": [],
  "publicSymbols": [],
  "callers": [],
  "callees": [],
  "routes": [],
  "rpcMethods": [],
  "messageEndpoints": [],
  "dataObjects": [],
  "configuration": [],
  "tests": [],
  "requirements": [],
  "evidence": [],
  "diagnostics": []
}
```

### 4.1 模块识别

第一版使用确定性规则：

1. 项目配置中的显式模块根路径；
2. Maven/Gradle module；
3. 顶层 package 或源码目录；
4. 约定目录；
5. 无法稳定识别时要求传入 `modulePath`。

第一版只处理一个明确目标模块，不做全仓自动聚类。

### 4.2 确定性事实来源

**代码结构**

- 文件、package、类、接口、方法和函数；
- 公开符号；
- 定义、引用和实现关系；
- 调用关系；
- 模块外部调用者和模块调用的外部符号。

**对外入口**

- HTTP Route；
- RPC 方法；
- 消息消费者和生产者；
- 定时任务；
- CLI 或启动入口。

**数据与配置**

- Repository/DAO；
- 数据库表或集合；
- 缓存访问；
- 消息 Topic；
- 配置 key 和环境变量；
- 外部服务地址。

**测试与需求**

- 测试类和方法；
- 最近 CI 状态和报告来源；
- 关联需求证据和内容哈希；
- 产品规则和验收标准。

### 4.3 诊断

无法确认的信息必须保留为诊断：

```json
{
  "code": "UNRESOLVED_DYNAMIC_CALL",
  "message": "消息消费者的下游实现无法静态解析",
  "source": "AuthEventConsumer.handle"
}
```

诊断不能被模型改写为确定事实。

## 5. Evidence Registry

模块事实注册为稳定证据：

```text
requirement:auth:1
code:auth:1
code-graph:auth:1
route:auth:1
config:auth:1
test:auth:1
```

证据至少包含：

```json
{
  "evidenceId": "code:auth:1",
  "type": "CODE",
  "projectId": "identity-service",
  "version": "5.1",
  "commitSha": "abc123",
  "source": "src/main/java/com/example/auth/AuthService.java",
  "symbol": "AuthService.revoke",
  "startLine": 80,
  "endLine": 124,
  "contentHash": "..."
}
```

约束：

- 证据属于目标项目；
- 需求属于目标业务版本；
- 代码属于目标 commit；
- 文件和行号可读取；
- 关系携带解析置信度；
- 测试携带真实报告来源；
- 页面 Claim 只能引用本 Registry 的 ID。

## 6. Module 页面与 Claims

页面至少包含：

```text
模块摘要
职责边界
对外入口
核心流程
主要符号
上游与下游依赖
数据、缓存与消息
配置
关联需求
关联测试
风险与知识缺口
声明与证据
```

核心流程应表达有序关系，而不是只列符号：

```text
AuthController.revoke
  -> AuthService.revoke
  -> RevocationRepository.save
  -> RevocationCache.evict
  -> AuthEventPublisher.publish
```

每个页面至少生成以下 Claims：

1. 模块职责；
2. 对外入口；
3. 核心处理流程；
4. 上游和下游依赖；
5. 数据与配置影响；
6. 关联测试；
7. 已知知识缺口。

Claim 示例：

```json
{
  "claimId": "auth-responsibility",
  "section": "responsibility",
  "text": "认证模块负责登录、令牌校验和令牌撤销",
  "support": "FULL",
  "evidenceIds": ["requirement:auth:1", "code:auth:1"]
}
```

生成约束：

- 只使用 Fact Bundle；
- 只引用 Registry 中的证据；
- 不生成不存在的文件、符号、配置和测试；
- 缺失信息输出 `UNSUPPORTED` 或知识缺口；
- 启发式关系只能生成 `INFERRED`；
- 来源冲突生成 `CONFLICT`；
- 输出必须通过 JSON Schema。

## 7. 发布质量门

### 7.1 阻止发布

```text
MODULE 页面没有代码证据                 -> 禁止发布
FULL Claim 没有 evidenceId              -> 禁止发布
Claim 引用 Registry 外的 evidenceId      -> 禁止发布
代码证据文件或行号失效                  -> 禁止发布
Claim 证据跨项目或跨 commit             -> 禁止发布
存在未处理的 CONFLICT                   -> 禁止发布
```

### 7.2 允许发布但显示缺口

```text
没有真实测试报告                        -> 显示缺口
只有 INFERRED 调用关系                  -> 显示置信度
没有关联需求                            -> 标记未文档化实现
需求存在但没有代码证据                  -> 标记缺少实现
```

### 7.3 指标

- Claim 总数；
- 各支持状态数量；
- Claim 证据覆盖率；
- 真实测试证据比例；
- 入口覆盖率；
- STALE 页面数；
- 失效证据数。

## 8. REST 与 MCP 交付

现有 Wiki 页面响应应增加：

- `pageType`；
- `claims`；
- Claim support；
- Claim evidence IDs；
- 是否过期及原因；
- 知识缺口。

建议新增：

```text
nexus_wiki_index
```

参数：

```text
projectId
version
pageType?
moduleId?
status?
stale?
limit?
```

返回页面 ID、类型、标题、摘要、状态、Claim 覆盖率、新鲜度和主要入口。

Agent 使用流程：

```text
nexus_wiki_index(pageType=MODULE)
  -> 选择 auth 模块
  -> nexus_wiki_page(module-auth)
  -> 查看 Claims
  -> 根据 evidenceId 下钻源码或需求
```

## 9. 过期传播与草稿重建

保持当前只读检测边界，不直接修改正式 Wiki。

过期传播升级为：

```text
Git diff
  -> 变更文件
  -> 变更符号
  -> inbound / outbound 关系
  -> 引用这些符号或关系的 Claims
  -> 受影响 Module 页面
```

增加显式 stale-to-draft 操作：

```text
读取 StaleReport
  -> 选择页面
  -> 重建 ModuleFactBundle
  -> 对比旧 Claims
  -> 生成新草稿
  -> 保留正式页面
  -> 人工审核后发布
```

新草稿标记：

- 新增、修改和删除 Claim；
- 支持状态变化；
- 证据变化；
- 未解决知识缺口。

审核人优先检查变化 Claims，不必重新阅读整页。

## 10. 实施步骤

### Step 1：Module Fact Bundle

- 新增 `ModuleFactBundle`；
- 支持显式 `modulePath`；
- 抽取代码、入口、关系、配置和测试；
- 注册 Evidence Registry；
- 添加聚焦测试。

### Step 2：Module 草稿生成

- 增加 Module Page Planner；
- 按严格 Schema 生成 Claims；
- 将 Claims 写入 Wiki Source；
- 使用现有草稿生命周期保存；
- 不自动发布。

### Step 3：质量门

- 校验 Claim 和 Evidence；
- 校验文件、符号和行号；
- 计算 Claim 覆盖率；
- 阻止不合格 Module 页发布。

### Step 4：REST/MCP

- 扩展 Wiki 页面响应；
- 新增 `nexus_wiki_index`；
- 返回 staleness 和缺口；
- 添加截断和契约测试。

### Step 5：过期重建

- staleness 从文件传播到 symbol/Claim；
- 增加 stale-to-draft 服务；
- 生成 Claim diff；
- 人工审核后发布。

## 11. MVP 验收标准

选择一个真实仓库和一个模块进行验收。

- [ ] 通过配置或路径稳定识别目标模块。
- [ ] 自动生成 `ModuleFactBundle`，不依赖手写 Wiki Source。
- [ ] Bundle 包含模块入口、核心符号、调用关系、配置和测试。
- [ ] 自动生成 Module Wiki 草稿。
- [ ] 页面包含职责、入口、流程、依赖、数据配置、测试和缺口 Claims。
- [ ] 每个 `FULL` Claim 至少绑定一个有效证据。
- [ ] `INFERRED` Claim 显示推断性质和置信度。
- [ ] Claim 可以跳转到需求或仓库相对源码位置。
- [ ] MCP 可以列出 Module 页面并读取 Claims。
- [ ] 修改核心符号后，页面出现在 StaleReport。
- [ ] StaleReport 可以显式生成新草稿。
- [ ] 新草稿显示 Claim 级差异。
- [ ] 未审核草稿不能覆盖正式 Wiki。
- [ ] 无代码证据、无效引用或跨 commit 证据会阻止发布。
- [ ] 已发布页面在模型和向量服务不可用时仍可读取。

## 12. 后续扩展

Module 闭环通过后，按同一机制扩展：

```text
MODULE
  -> OVERVIEW
  -> API
  -> DATA
  -> VERSION
```

Feature 页面也应迁移到相同 Fact Bundle 和 Claim 机制，不再保留独立的摘录拼装逻辑。

最终判定：

> **一个模块页面能够从研发事实中自动编译，每条关键声明有证据，代码变化后会失效，能够生成待审核更新草稿，并被 Agent 直接消费。**
