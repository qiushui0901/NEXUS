# Phase 1 跨版本实体基础 PRD

## Goal

把 `BusinessConcept` 收敛为**跨版本、跨来源的稳定实体**：同一业务实体在任何业务版本、任何来源类型下
都解析到同一 `entityId`；参数/需求/测试/代码成员可共存于同一实体；历史成员不因导入最新版本而消失。

## Requirements

1. 去除 `conceptFor` 中 `param:/req:/test:/obs:/doubt:` 前缀导致的错误拆分，canonicalKey 改为
   `<module>.<subject>` 的规范化键（来源无关、版本无关）。
2. 新增 `buildProject(projectId)`：枚举全部业务版本 + 代码符号，跨版本合并实体；**不删除未被本次
   输入覆盖的历史成员**。
3. 保留 `build(projectId, version)` 版本级构建语义（仅替换该版本成员），作为增量 `buildVersion`。
4. 为实体成员补齐 Claim 校验：claimId 必须真实存在且属于同项目。
5. 补齐 dev md §5.2 五个索引（alias 查找 / member(version) / member(claim) / claim(fact,subject,predicate) /
   document_version(business,status)）。
6. 历史 Claim 不删除；实体演进由成员时间轴表达。

## Acceptance

- 同一实体在两个业务版本下返回同一 `entityId`。
- 参数、需求、测试、代码成员可以同时挂到同一实体（无来源前缀拆分）。
- 重新导入/重建最新版本后，其他版本的历史成员不消失。
- 同 subject 不同 module（如“攻击力上限”vs“攻击力成长公式”）不错误合并为同一实体。
- `./mvnw test` 全绿（含新增跨版本/历史保留/防误合并测试）。