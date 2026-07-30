# 拾光真实项目评测

本方案使用用户授权的 `qiushui-shiguang` 仓库验证 NEXUS 0.6 的真实 MCP 证据链和
0.8 的真实检索质量。源仓库全程只读；NEXUS 只提交重新表述的脱敏需求语料和稳定的
仓库相对代码标签。

## 安全边界

- 只索引配置中列出的八个业务模块。
- 排除所有 `src/main/resources`、`src/test/resources`、`.git`、IDE/构建目录和 `简历.md`。
- 不复制拾光仓库的配置文件、账号、密钥、手机号、简历或用户数据。
- 本地绝对路径和 `NEXUS_API_KEY` 仅通过环境变量传入，不写入 Git。
- 使用独立的 `requirements_shiguang_eval` 与 `code_shiguang_eval` collection，不污染其他项目。

## 1. 启动真实评测环境

准备 Qdrant、Embedding 与 BGE 服务后，在启动 NEXUS 的同一个 shell 设置。若启用 LLM 精排，
还必须提供可用的模型服务；校准环境不包含 LLM 时请显式设置 `LLM_RERANK_ENABLED=false`：

```bash
export SHIGUANG_REPOSITORY_PATH='/Users/user/Documents/qiushui-shiguang'
export AUTH_USER_1_KEY='replace-with-a-long-random-key'
export NEXUS_API_KEY="$AUTH_USER_1_KEY"
export SPRING_PROFILES_ACTIVE='shiguang-eval'
export LLM_RERANK_ENABLED='false'

./mvnw spring-boot:run
```

路径可以换成该仓库的其他授权副本。`application-shiguang-eval.yml` 不包含机器路径或密钥。

## 2. 上传脱敏需求并建立代码索引

另开一个保留上述环境变量的终端：

```bash
python3 tools/shiguang-eval.py prepare
```

命令会上传 `evaluation/shiguang/shiguang-eval-requirements.md`，再以前台任务建立代码索引。
索引可能需要数分钟；失败会返回非零状态，不会打印 API Key。

## 3. 验证 0.6 MCP 真实证据链

```bash
python3 tools/shiguang-eval.py smoke
```

脚本执行 MCP 初始化，然后依次调用：

1. `nexus_search_requirements`
2. `nexus_search_code`
3. `nexus_get_source`

只有需求响应包含 `requirement:*`、代码检索和源码响应包含 `code:*`，且源码路径仍为仓库相对
路径时才通过。脚本还会把脱敏摘要写入 `target/retrieval-evaluation/mcp-smoke.json`；摘要只包含
项目 ID、证据 ID、仓库相对路径和 warning code，不保存 API Key、服务地址或源码正文。准备和冒烟也可以合并执行：

```bash
python3 tools/shiguang-eval.py all
```

## 4. 运行 0.8 真实检索评测

首次运行是校准模式，不与虚构基线比较：

```bash
scripts/run-shiguang-eval.sh
```

报告写入 `target/retrieval-evaluation/report.md` 与 `report.json`。12 条黄金用例覆盖
`DEVELOPMENT_PLAN`、`REQUIREMENT_REVIEW`、`CODE_SEARCH`，其中开发计划和需求评审走
0.8 统一检索管线，代码搜索走正式代码检索服务。

校准环境固定后，把实测指标保存成版本化基线资源，再启用回归门禁：

```bash
export SHIGUANG_EVAL_BASELINE_RESOURCE='evaluation/retrieval-baseline-shiguang-v1.json'
scripts/run-shiguang-eval.sh
```

只有在同一份代码快照、同一份需求语料、同一 Embedding/BGE 配置下重复测量，才可以比较
0.7 与 0.8 Recall@10/MRR/P95。校准报告本身不代表验收完成，也不会自动勾选路线图。


## 5. 2026-07-28 实测记录

本次校准使用只读的授权仓库、独立 collection、同一份 12 条黄金数据集和同一套外部 Embedding
配置。Ollama 的 `bge-m3` Embedding 当时正常，但 NEXUS 配置指向的独立 `/rerank` endpoint
尚未提供；Ollama `/api/embed` 返回向量，不满足 NEXUS 需要的 `index`/`score` 重排契约。
因此所有需求检索用例均如实保留 `BGE_RERANK_UNAVAILABLE`，没有通过修改黄金标签或放宽成功条件
掩盖基础设施失败。

| 指标 | 修复前 0.8 | 修复后 0.8 | 变化 |
|---|---:|---:|---:|
| Document Recall@10 | 0.900 (9/10) | 1.000 (10/10) | +0.100 |
| Code Recall@10 | 0.500 (5/10) | 1.000 (10/10) | +0.500 |
| MRR@10 | 0.516 | 0.863 | +0.347 |
| Mixed both-hit | 0.500 (4/8) | 1.000 (8/8) | +0.500 |
| P50 | 3777 ms | 2933 ms | -844 ms (-22.3%) |
| P95 | 8617 ms | 5888 ms | -2729 ms (-31.7%) |
| 基础设施失败 | 10 | 10 | 独立 reranker endpoint 当时不可用 |

修复后 10 条代码黄金标签全部进入 Top 10；代码 rank 依次为
`1, 1, 10, 1, 2, 1, 1, 3, 1, 3`。报告仍显示 10/12 用例失败，是因为这 10 条都包含
BGE 基础设施 warning，而不是召回遗漏。权威产物为
`target/retrieval-evaluation/report.md` 和 `report.json`。

真实 MCP smoke 同日通过，脱敏证据为：

- requirement evidence：`requirement:808b8e9c-f3a6-382a-a3d5-8916006a2348`
- code evidence：`code:ded17030-1875-3e64-b29a-9519ef5d7171`
- source evidence：`code:887d37e343ad1b269fd0cd1f1b8b05e6`
- source path：`shiguang-auth/src/main/java/com/quanshiguang/shiguang/auth/service/impl/AuthServiceImpl.java`
- warning：`BGE_RERANK_UNAVAILABLE`

### 2026-07-29 独立 reranker 修复方案

项目新增 `tools/bge-reranker-service.py`，使用 Python 3.11、PyTorch 与 Transformers 加载
`BAAI/bge-reranker-v2-m3`，并在 `127.0.0.1:8081/rerank` 提供与现有 Java 客户端兼容的
顶层数组响应。`tools/start-bge-reranker.sh` 负责创建隔离虚拟环境和安装依赖，
`tools/check-bge-reranker.py` 负责验证 `/health`、响应结构和基本排序。

Ollama 中已安装的 `hans-tech/bge-reranker-v2-m3:260522` 不能直接复用为此 endpoint：当前
Ollama 未提供 `/api/rerank`，其 `/api/embed` 只产生向量，模型 blob 也不是 Transformers
`from_pretrained` 直接消费的目录格式。因此该方案会单独下载 Hugging Face 权重。2026-07-28 的
历史评测结果保持不变；只有在服务真实启动并重新运行同一评测后，才能更新依赖状态和指标。

2026-07-29 已完成真实服务验证：模型在 CPU 上成功加载，`/health` 返回 `UP`，并通过
NEXUS 顶层数组契约及匹配段落优先排序检查。该检查只证明 reranker endpoint 可用，不会追溯修改
2026-07-28 的评测产物；拾光指标仍需重新执行同一评测后另行记录。

### 0.7 对照限制

历史提交 `97cbf42` 有 0.7 的通用评测执行器和通用数据集，但没有 `shiguang-eval` profile、
本次脱敏需求语料或拾光黄金数据集。后续提交中的 `retrieval-baseline-v0.7.json` 只是通用门禁阈值，
不是拾光仓库的真实测量。因此目前不能诚实地产出“同仓库、同语料、同 Embedding/BGE 配置”的
0.7→0.8 对照；不得用该阈值文件冒充真实基线。待能构建固定的 0.7 运行镜像并恢复相同依赖后，
再提交版本化实测基线并启用 live CI gate。

## 6. Cursor 与 Codex

NEXUS 服务通过本评测后，Cursor 和 Codex 仍使用
[MCP 快速入门](./mcp-quickstart.md)中的同一个共享 HTTP 端点。客户端只需获得组内分发的
URL 与项目受限 API Key，不需要访问拾光仓库或本机路径。
