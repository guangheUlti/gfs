# GFS 一键复制直链（免登录临时下载链接）设计

> 面向维护者：说明"对单个文件创建或复用分享、一键复制免登录下载直链"的链路，以及为什么复用分享机制而非新建独立令牌体系。
>
> 最后更新：2026-09-17 —— 新增 `POST /apis/share/direct-link` 与登录端"复制直链"入口。

---

## 1. 设计定位

直链的能力底座在文件分享里早已具备：`/apis/share/{shareId}/raw/{fileId}` 是匿名访问点（无需登录、天然绕过提取码），配合 `file_shares` 的 `expire_time` 与 `max_download_count` 就是一套"临时、可过期、可限次"的下载链接。本功能要解决的是**入口缺失**：

- 改前：想拿单文件直链必须走 勾选文件 → 创建分享弹窗 → 分享结果 → 复制直链 的长链路；
- 改后：在"我的文件"对单个文件右键/更多菜单点"复制直链"，**创建或复用**一条分享，立即把该文件的 `raw` 直链写入剪贴板。

设计决策：**复用分享机制，不新建独立直链表/一次性签名**。理由：

- 直链即分享：过期、次数、取消、访问记录等能力全部已有，零重复建设；
- 生成的直链就是一条分享，可在"我的分享"统一管理/取消；
- 访问基础规则为"纯免登录直链"（复制即用），与 alist 直链体验一致。

## 2. 组件全景

| 层 | 组件 | 位置 | 职责 |
| --- | --- | --- | --- |
| 后端-入口 | `FileShareController#createDirectLink` | `fs-modules/fs-file/.../controller/FileShareController.java` | `POST /apis/share/direct-link`，`@SaCheckPermission("file:share")` |
| 后端-编排 | `FileShareServiceImpl#createDirectLink` | `same/.../service/impl/FileShareServiceImpl.java` | 归属校验、祖先 id 收集、创建或复用分享、拼装直链 |
| 后端-复用 | `findReusableShareId` / `collectAncestorIds` | 同上（本类私有方法） | 查本人最近未过期且覆盖该文件的分享；沿 `parent_id` 上溯 |
| 前端-菜单 | `FileListView` / `FileGridView` | `fs-ui/src/pages/files/components/*.tsx` | 右键/更多菜单为**非目录文件**增加"复制直链"项 |
| 前端-编排 | `useFileOperations#copyDirectLink` | `fs-ui/src/pages/files/hooks/useFileOperations.ts` | 调接口 → 拼绝对地址 → 写剪贴板 → toast |
| 前端-API/类型 | `api/share.ts#createDirectLink` / `types/share.ts` | `fs-ui/src` | `POST /apis/share/direct-link` 封装与 `DirectLinkResponse` |
| i18n | `zh/en files.json` | `fs-ui/src/locales/*/files.json` | `rowMenu.copyDirectLink`、`operations.copyDirectLinkOk/Fail` |

## 3. 数据模型

**不新增表**：完全复用 `file_shares`（分享主表）与 `file_share_items`（分享↔文件明细）。直链就是一条含 `fileId` 明细的分享，其 `directUrl = /apis/share/{shareId}/raw/{fileId}`。

## 4. 鉴权边界

| 接口 | 鉴权 |
| --- | --- |
| `POST /apis/share/direct-link` | **需登录** + `file:share` 权限码（创建分享的能力） |
| `GET /apis/share/{shareId}/raw/{fileId}`（直链目标） | **匿名**（已在 `security.excludes`，复用分享的匿名下载链路） |

因此：生成直链要求登录（只能给自己的文件生链），但生成的链接对外匿名可下载。新端点**不加入**匿名 Excludes，避免未登录即可创建分享。

## 5. 生成直链时序

```
登录用户「我的文件」右键/更多 → 复制直链
浏览器                           后端
  │ POST /apis/share/direct-link │
  │ { fileId, expireType?,       │
  │   maxDownloadCount? }        ├────────────────────────────────►│ @SaCheckPermission("file:share")
  │                              │  createDirectLink（事务）
  │                              │  ├─ getAuthorizedFile(fileId)    仅本人、非目录，否则"文件不存在/不是文件"
  │                              │  ├─ collectAncestorIds()          收集文件自身 + 全部祖先 id
  │                              │  ├─ findReusableShareId()         查本人未过期分享，其
  │                              │  │   item.file_id 命中祖先集合 → 取最近创建一条
  │                              │  ├─ [未命中] createShare()        复用现有创建逻辑，建最小分享：
  │                              │  │   name=displayName, scope=download,
  │                              │  │   无提取码, expireType默认1=7天, maxDownloadCount
  │  ◄─ { shareId, fileId,       │  └─ 返回 directUrl=/apis/share/{id}/raw/{fileId}
  │    directUrl }               │
  │ 前端：window.location.origin + directUrl → 写剪贴板 → toast"直链已复制"
```

## 6. 创建或复用（核心逻辑）

`findReusableShareId(userId, candidateIds)`：

1. 先做一次 `file_share_items` 反查：`file_id IN (文件自身 ∪ 祖先 ids)`，命中则把这些 `share_id` 收进候选集合；
2. 再 `getOne`（按 `created_at` 倒序取最近一条）本人未过期的分享：
   `user_id = 当前人 AND expire_time IS NULL OR expire_time > now()`
3. 未命中则调用现有 `createShare` 内部口径建最小分享。

如此覆盖两种"复用"：文件**直接被分享**过、或文件**位于被分享的文件夹内**（祖先属于某分享）。好处：

- 对同一文件重复点"复制直链"，始终返回**同一条** `shareId`，不在"我的分享"产生重复条目；
- 直链为 validations 有效期/次数沿用该分享设置，可统一在"我的分享"管理/取消。

## 7. 边界与安全说明

- **仅支持单文件**：`collectAncestorIds` 对文件夹无意义（文件夹没有单一 `raw` 文件可指），且菜单项对文件夹不展示；后端 `isDir` 直接拒绝。
- **绕过提取码是有意行为**：`raw` 下载链路只校验分享有效性与文件归属，不比对 `share_code`，与分享设计 §7 的"页面级软门槛"一致。直链目标可不是密码分享，而是直接可下的公开链接——这是产品定位。
- **直链复用分享的计数/过期**：若该分享设了 `max_download_count`，直链下载同样在约束范围内（下载复用 `downloadFiles` 链路）。
- **权限边界**：只能给自己未删除的文件生链（`getAuthorizedFile`），杜绝越权。

## 8. 接口清单（本功能新增）

| 方法 | 路径 | 鉴权 | 请求体 | 响应 data |
| --- | --- | --- | --- | --- |
| POST | `/apis/share/direct-link` | 登录 + `file:share` | `CreateDirectLinkCmd{ fileId, expireType?, maxDownloadCount? }` | `DirectLinkVO{ shareId, fileId, directUrl }` |

走链（现有，无需改动）：`GET /apis/share/{shareId}/raw/{fileId}`（匿名）。

## 9. 前端交互

- 菜单项仅对**非目录**文件展示（`!file.isDir && canShare`），列表视图的行更多菜单与右键菜单、网格视图的卡片菜单三处各加一项；
- 点击后 `copyDirectLink(file)`：`createDirectLink({ fileId })` → `window.location.origin + directUrl` → `navigator.clipboard.writeText` → `toast.success`（复用现有 toast 体系与 i18n）。