# 0.9.3 Immortal 知识导入加载器 — Implement

> 每步完成后运行对应验证命令再继续。

- [x] 1. 新增 `XlsxTableReader`（ZIP+DOM 读多 sheet，headers + rows）。
- [x] 2. 新增 `XlsxTestCaseLoader`（分组/模块/操作步骤/预期结果 → TestCaseClaim）。
- [x] 3. 新增 `ConfigTableLoader`（通用配置表 → ParameterClaim）。
- [x] 4. `DoubtClaimParser` 补 `跟进人/产品答疑` 别名。
- [x] 5. 新增 `ImmortalLoadersTest`：XlsxTestCaseLoader、ConfigTableLoader、DoubtClaimParser 别名。
- [x] 6. 新增 `ImmortalKnowledgeImporter` + 内容 hash 缓存 + `ImmortalImportIT` 导入入口；完成首次导入（参数 779,129 / 存疑 2,100 / 用例 26,168 / 需求 123）。
- [x] 7. 更新 CHANGELOG 0.9.3 与 Trellis implement.md，运行全量测试，提交推送。

## 验证命令

```bash
./mvnw -B test -Dtest='ImmortalLoadersTest,MultiSourceKnowledgeLoaderTest'
./mvnw -B test -Dtest=ImmortalImportIT -Dimmortal.import=true   # 本地导入/缓存校验
git diff --check
./mvnw -B test
```