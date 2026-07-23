# 导览导航与内容改进设计

**Date**: 2026-07-15
**Status**: Proposed

## Problem

1. **上一步/下一步按钮不可见**：`.tour-active` 使用 `min-height:100%`，当内容（描述 + 引用组件）过多时，footer 被推出可视区域。
2. **导览描述太简短**：每步只有一句硬编码模板文本（如"先确定从哪个接口进入"），没有引用实际组件名称，缺乏可操作性。

## Solution: CSS Fixed Footer + Enriched Descriptions (Approach A)

### Part 1: CSS Layout Fix

将 `.tour-active` 区域改为固定高度 flex 容器：
- head、progress、footer 固定不动（`flex:0 0 auto`）
- content 区域独立滚动（`flex:1; min-height:0; overflow-y:auto`）

**CSS Changes:**

| Selector | Old | New |
|----------|-----|-----|
| `.tour-active` | `min-height:100%; display:flex; flex-direction:column` | `height:100%; display:flex; flex-direction:column; overflow:hidden` |
| `.tour-active-head` | (no flex rule) | add `flex:0 0 auto` |
| `.tour-progress` | (no flex rule) | add `flex:0 0 auto` |
| `.tour-content` | `flex:1; padding:22px 18px` | `flex:1; min-height:0; overflow-y:auto; padding:22px 18px` |
| `.tour-footer` | (no flex rule) | add `flex:0 0 auto` |

### Part 2: Description Enrichment

**Format (medium detail):**

Each step's `description` contains three sections separated by `\n\n`:

1. **Role explanation** (1-2 sentences): What this layer does in the system
2. **Key components**: "相关组件：ComponentA、ComponentB" (derived from actual node labels)
3. **Reading suggestion**: "建议：focus on XXX"

**Title improvement:**
- Step 1: "先看：{layer.name}"
- Middle steps: "接着看：{layer.name}"
- Last step: "最后看：{layer.name}"

**Frontend rendering:**
- Add `white-space:pre-line` to the `<p>` tag to render `\n` as line breaks.

## Files Changed

| File | Action | Description |
|------|--------|-------------|
| `static/monitor.html` | Modify | 5 CSS flex properties + 1 white-space |
| `CodeKnowledgeService.java` | Modify | Enhanced `tour()` + new `tourTitle()` / `tourDescription()` |

## Not in Scope

- Redesigning tour layout (drawer, modal approaches)
- Adding code snippet preview in tour steps
- Tour data persistence or bookmarking
