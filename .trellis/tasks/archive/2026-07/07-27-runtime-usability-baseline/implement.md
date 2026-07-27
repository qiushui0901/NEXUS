# Implementation Plan

1. 增加 runtime 状态模型、服务和接口，并补单元/控制器测试。
2. 增加平台首页和路由，补静态资源契约测试。
3. 增加统一进程脚本，修正旧启动脚本为兼容入口。
4. 增加后台代码索引任务、状态接口和工作台轮询，限制大型符号并受控批量调用嵌入模型。
5. 更新 `.env.example`、README 和 CHANGELOG。
6. Java 21 执行 Maven verify、脚本语法检查、真实启动与 HTTP 冒烟测试。
7. 检查 Git 变更不含向量库、需求快照和凭据。

## Rollback

页面路由可恢复到 `/monitor.html`；runtime status 为新增接口；脚本不修改业务数据，停止后可恢复原手动启动方式。


## Verification record (2026-07-27)

- Java 21 `./mvnw -B verify`: 120 tests passed, 0 failures/errors.
- `bash -n` passed for the unified and compatibility scripts; `git diff --check` passed.
- Real `./scripts/nexus.sh restart` successfully stopped and restarted NEXUS and Qdrant without deleting local storage.
- Runtime status after restart: core ready; Qdrant and Ollama connected; BGE explicitly degraded as optional.
- Background code index returned HTTP 202 in about 0.05 seconds; a duplicate request reused the same task.
- Existing 3101-chunk index remained searchable during rebuild.
- Rebuild completed for commit `f7e0e22bec3068a45636ec2985e21abc1975c3e5`: 367 files and 2830 chunks.
- After a full restart, runtime status still reported 17 requirement chunks, 2830 code chunks and 64 Wiki versions; code search returned results.
