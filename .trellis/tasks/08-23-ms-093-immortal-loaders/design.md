# 0.9.3 Immortal 知识导入加载器 — Design

## XlsxTableReader

复用 `ExcelKnowledgeLoader` 的 ZIP+DOM 读取方式，提供：

```java
public record XlsxSheet(String sheetName, List<String> headers, List<Map<String, String>> rows) {}
public final class XlsxTableReader {
    public List<XlsxSheet> read(Path xlsxPath); // 所有 sheet，rows keyed by "0","1",...
}
```

- 通过 `xl/workbook.xml` + `xl/_rels/workbook.xml.rels` 解析 sheet 名称顺序。
- 第一行非空行作为 `headers`；其余行作为 `rows`（列键为序号字符串，与 `ParameterTableLoader` 一致）。

## XlsxTestCaseLoader

| 列 | 角色 |
|---|---|
| 分组 | group（用于 testCaseId 前缀） |
| 模块 | module |
| 操作步骤 | steps |
| 预期结果 | expectedResult |

- `testCaseId` = `safe(group 或 module) + "-" + sheetName + "-" + (rowNumber+1)`
- `evidenceLocation` = `filePath#sheetName!rowNumber`
- `claimId` = `tc:` + sha256(projectId|version|file|sheet|row)
- `framework = XLSX`，`preconditions = ""`，`coveredRequirementId = ""`
- 空行（steps 与 expectedResult 都空）跳过。

## ConfigTableLoader

- `module` = sheetName。
- 对每个数据行 × 每个列（headers）：
  - `parameter` = 列名，`rawValue` = 单元格值，`valueType` 由值推导（复用 ParameterTableLoader 的类型逻辑）。
  - `factKey` = `projectId|version|module|parameter`
  - `claimId` = `param:` + sha256(projectId|version|file|sheet|row|col)
  - `evidenceLocation` = `filePath#sheetName!row:COL`
- 空单元格跳过；保留原始行列。

## DoubtClaimParser 别名

- owner keys 增加 `跟进人`
- answer keys 增加 `产品答疑`

## 测试策略

- 用构造的 `XlsxSheet` 直接测两个 Loader（不依赖真实文件）。
- DoubtClaimParser 别名断言。
