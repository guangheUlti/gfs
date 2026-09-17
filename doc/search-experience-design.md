# GFS 站内搜索体验增强设计

> 面向维护者：说明"已存在的站内搜索"之上补的四项体验（结果高亮、搜索空态、类型筛选下拉、搜索提示条），以及为什么全部落在前端、不改后端查询语义。
>
> 最后更新：2026-09-17 —— 抽象 `Highlight` 组件、Toolbar 类型下拉、URL `type` 同步、空态与提示条。

---

## 1. 设计定位

搜索的**能力底座早已存在**，本功能不新增查询能力，只补体验入口：

- 后端 `FileInfoServiceImpl.getList(FileQry)` 已支持 `keyword`（`ORIGINAL_NAME/DISPLAY_NAME` 模糊匹配，`parentId == null` 时跨目录全局搜索）与 `fileType`（`FileTypeEnum.FileCategory` 大类过滤），见 `applyFileTypeFilter`；
- 前端 `useToolbarSearch` 已把关键词同步进 URL `keyword`，`useFileList#buildQuery` 已把 `keyword` 传给 `getFileList`。

因此现状缺口是**体验层**：结果无高亮、无空态引导、类型筛选只能靠 URL 手输、搜索态下工具栏仍显示面包屑。

**目标（四项，均为前端）**：

1. 关键词结果**高亮**——命中片段用 `<mark>` 标出；
2. 搜索**空态**——无结果时给出明确提示与"清空搜索"出口；
3. **类型筛选下拉**——并入 URL `type`，复用后端 `fileType` 过滤；
4. **搜索提示条**——搜索态用一条提示条替换面包屑，带一键清空。

设计决策：**不改后端**。理由：`getList` 已同时支持 `keyword + fileType`（可叠加），且 `parentId == null` 即全局搜索——查询语义已够用，只缺呈现。

## 2. 组件全景

| 层 | 组件 | 位置 | 职责 |
| --- | --- | --- | --- |
| 前端-通用 | `Highlight`（新建） | `fs-ui/src/components/Highlight.tsx` | 按关键词大小写不敏感分解文本，用 `<mark>` 包裹命中片段 |
| 前端-工具栏 | `Toolbar` | `fs-ui/src/pages/files/components/Toolbar.tsx` | 新增类型筛选 `DropdownMenu`（全部/图片/视频/音频/文档/其它），透传 `fileType`/`onTypeChange` |
| 前端-编排 | `FilesPage#handleTypeChange` | `fs-ui/src/pages/files/index.tsx` | 写/删 URL `type`（删除时回全部文件视图），驱动 `useFileList` 重查 |
| 前端-列表/网格 | `FileListView` / `FileGridView` | `fs-ui/src/pages/files/components/*.tsx` | 透传 `searchKeyword`，名称渲染改用 `<Highlight>` |
| 前端-状态 | `useFileList` / `useToolbarSearch` | `fs-ui/src/pages/files/hooks/*.ts`、`fs-ui/src/hooks/useToolbarSearch.ts` | 源：URL `keyword`/`type` → `searchKeyword`/`fileType` → `buildQuery` |
| 前端-i18n | `zh/en files.json` | `fs-ui/src/locales/*/files.json` | `toolbar.type*`、`index.searchBanner/searchEmptyTitle/searchEmptyDesc/clearSearch` |

## 3. 数据与状态流

**不新增表、不新增后端接口、不改 DTO**。状态全部来自 URL，单一事实源：

```
URL query
  keyword ──► useToolbarSearch ──► searchKeyword ──┐
  type    ──► useSearchParams  ─► fileType ────────┼─► buildQuery ──► getFileList
                                                     └──► fileType: fileType || undefined
```

- 搜索框输入 → `commitSearch` 写 URL `keyword` → `useFileList` 监听重查；
- 类型下拉选中 → `handleTypeChange` 写 URL `type`（空串则删 `type` 并删 `view`）→ 同源驱动重查；
- `searchKeyword` 同时供两个视图做 `<Highlight>` 高亮、供 `index.tsx` 做"是否搜索态"的判断。

## 4. 搜索态 UI 结构

`FilesPage` 的渲染分支（`index.tsx`）：

1. **顶部提示条**：`searchKeyword` 非空时，面包屑区替换为 `Search 图标 + 正在搜索「keyword」+ 清空按钮`；否则显示原有 `FileBreadcrumb`。
2. **列表空态**：加载完成且列表为空时——
   - `searchKeyword` 非空 → 搜索空态（`Search 图标 + 未找到… + 清空搜索`，按钮调 `commitSearch('')`）；
   - 否则 → 原有默认空态（"暂无文件，上传或新建文件夹"）。
3. **结果高亮**：`FileListView` 名称列、`FileGridView` 卡片标题在 `searchKeyword` 非空时用 `<Highlight text={displayName} keyword={searchKeyword} />`。

## 5. 高亮实现细节（`Highlight`）

- 输入：`text`、`keyword`；
- 用 `String.prototype.split` 按关键词（正则已 `escape`）做大小写不敏感分段，`flag = 'i'`；对含非 ASCII 的扩展采用 `'iu'` 降级 `'i'`；
- 命中片段用 `<mark>` 包裹，其余输出原文；`keyword` 为空时直接返回 `text`，零渲染开销。

## 6. 边界与既有能力

- **全局 vs 目录内搜索**：`parentId` 由当前视图上下文决定——`parentId == null` 时后端已做全局搜索；在具体目录内输入关键词行为不变（仅当前层级）。本功能不改此语义。
- **类型筛选叠加关键词**：`keyword + fileType` 二者由后端同时生效，可组合搜索（如"视频类型下搜 '测试'"）。
- **类型各值对应**：`image/video/audio/document/other` 与后端 `FileTypeEnum.FileCategory` 大类码一致；UI 未单独列"压缩包/archive"，保持与现有特殊视图标题（`index.viewDocument/Image/...`）对齐。
- **不改后端**：`FileInfoServiceImpl.getList`、`applyFileTypeFilter` 均不动。

## 7. 不做的范围

- 不做"文件夹内递归子目录搜索"（当前仅当前层级）；
- 不做搜索历史、热词、搜索建议；
- 不做"定位到所在目录"（从搜索结果跳到文件真实位置）；
- 不改后端接口语义。

## 8. 验证清单（端到端）

1. `tsc --noEmit` 类型检查通过。
2. 登录 `admin/admin` → 输入命中关键词检索：列表与网格中命中的文件名片段**高亮**。
3. 类型下拉选"视频"：URL 变 `type=video`，列表仅显示视频；与关键词可叠加。
4. 输入必然无结果的词：出现**搜索空态**与"清空搜索"。
5. 清空搜索 / 清除类型：回到正常面包屑与完整列表，无回归。
6. 具体目录内输入关键词：行为与现状一致（仅当前目录）。