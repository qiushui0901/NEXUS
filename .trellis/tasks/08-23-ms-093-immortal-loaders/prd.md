# 0.9.3 Immortal 知识导入加载器

## Goal

让 `document/immortal` 下的 PRD / DATA / QA / CASE 四类资料可被现有多源知识链路解析：

- CASE（分组/模块/操作步骤/预期结果 XLSX）→ `TestCaseClaim`
- DATA（配置表 XLSX）→ `ParameterClaim`
- QA 存疑 XLSX 兼容“跟进人 / 产品答疑”列名

## Requirements

- 新增通用 XLSX 读取器：按 sheet 解析表头与按列索引的行数据。
- `XlsxTestCaseLoader`：把每个 sheet 的 `分组/模块/操作步骤/预期结果` 映射为 `TestCaseClaim`，保留文件/sheet/行号 Evidence。
- `ConfigTableLoader`：把每个 sheet 的第一行作为列名，把每一行每一列生成 `ParameterClaim`（subject=列名，object=单元格值，module=sheet 名），保留行列定位。
- `DoubtClaimParser`：补 `跟进人 → owner`、`产品答疑 → answer` 别名。
- 全部确定性解析，不依赖 LLM。

## Acceptance Criteria

- [ ] `XlsxTestCaseLoader` 能从 sheet 数据生成带稳定 claimId/evidenceLocation 的 `TestCaseClaim`，跳过空行。
- [ ] `ConfigTableLoader` 能从通用配置表生成 `ParameterClaim`，每条记录/列可定位到原始 sheet/行列。
- [ ] `DoubtClaimParser` 识别“跟进人/产品答疑”列。
- [ ] 全量测试通过。