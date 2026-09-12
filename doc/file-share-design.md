# GFS 文件分享机制设计

> 面向维护者：说明文件分享从创建、提取码校验、匿名浏览、下载/直链到访问记录的完整链路，以及鉴权边界与安全设计的缘由。
>
> 最后更新：2026-09-12 —— 移除预览/下载权限区分（scope 统一为 download），分享页对任意类型文件匿名可下载、可直链预览；媒体流接口全部纳入 previewToken 防盗链。

---

## 1. 设计定位

分享的用途是**把文件交给外部的人下载**，因此：

- 不区分「预览权限 / 下载权限」，统一都是下载能力；`scope` 字段保留但仅作兼容，不再作为界面选项，后端写入默认值 `download`。
- 分享的浏览与下载**不要求登录**：提取码（密码分享）是唯一的访问门槛，且是页面级校验。
- 分享内的媒体文件预览不走登录态，而是复用**直链**（`raw`）：白名单媒体类型内联展示，其余类型自动转为附件下载。

## 2. 组件全景

| 层 | 组件 | 位置 | 职责 |
| --- | --- | --- | --- |
| 入口 | `FileShareController` | `fs-modules/fs-file/.../controller/FileShareController.java` | `POST /create`、`DELETE /cancels`、`/clears`、`GET /{shareId}/info`、`/items`、`/download/{fileId}`、`/raw/{fileId}`、`/verify/code`、文件夹打包下载任务系列 |
| 编排 | `FileShareServiceImpl` | `fs-modules/fs-file/.../service/impl/FileShareServiceImpl.java` | 创建/取消分享、有效期计算、提取码校验、分享内文件可达性校验、下载流获取、访问日志事件 |
| 明细 | `FileShareItemServiceImpl` | 同上 service/impl | `file_share_items` 的写入与查询（分享 ↔ 文件的关联表） |
| 审计 | `FileShareEventListener` | `fs-modules/fs-file/.../listener/FileShareEventListener.java` | `@Async @EventListener` 异步落库访问记录（IP/归属地/浏览器/OS） |
| 操作日志 | `SysOperationLogService` | controller 内直接调用 | 创建/取消分享记录操作日志 |
| 鉴权链 | `WebMvcConfig` | `fs-admin/.../web/WebMvcConfig.java` | Sa-Token 登录拦截（order 1，excludes 见 §4）、防盗链拦截（order 3） |
| 防盗链 | `PreviewInterceptor` | `fs-admin/.../interceptor/PreviewInterceptor.java` | 校验媒体流接口的 `previewToken`（分享下载/直链不经此拦截器） |
| 前端-创建 | `ShareModal` | `fs-ui/src/pages/files/components/ShareModal.tsx` | 有效期/分享类型/次数表单，生成后展示分享链接与直链 |
| 前端-管理 | `MySharesView` | `fs-ui/src/pages/files/components/MySharesView.tsx` | 我的分享表格、详情弹窗、访问记录、取消/清空 |
| 前端-提取 | `pages/share/` | `fs-ui/src/pages/share/` | 匿名分享页：提取码校验、文件浏览、预览（新标签直链）、下载 |
| 前端-API | `api/share.ts` | `fs-ui/src/api/share.ts` | 全部分享接口的请求封装 |

## 3. 数据模型

| 表 | 说明 | 关键字段 |
| --- | --- | --- |
| `file_shares` | 分享主表，`id` 即分享 token（雪花 ID） | `user_id`（属主）、`share_name`、`share_code`（4 位提取码，可空=公开分享）、`expire_time`（可空=永久）、`scope`（历史遗留，现固定写 `download`）、`max_view_count` / `max_download_count`（预留，见 §8）、`view_count` / `download_count`（预留计数） |
| `file_share_items` | 分享明细，一行一个被分享文件/文件夹 | `share_id`、`file_id` |
| `file_share_access_record` | 访问记录 | `share_id`、`access_ip`、`access_address`、`browser`、`os`、`access_time` |

> 分享 token 直接使用主表雪花 ID。ID 可被枚举不是问题：所有分享内操作都要过 §6 的归属校验链，无提取码分享本来就是公开的。

## 4. 鉴权边界：哪些接口匿名、哪些要登录

`application.yml` 的 `security.excludes` 中与分享相关的匿名路径：

```yaml
- /apis/share/**/items        # 分享页文件列表
- /apis/share/verify/code     # 提取码校验
- /apis/share/**/info         # 分享页元信息（是否过期/是否需码）
- /apis/share/**/download/**  # 文件下载
- /apis/share/**/raw/**       # 直链访问
```

文件夹打包下载任务的四个接口（`/apis/share/{shareId}/folder-download/**`）**不在**匿名清单里，需登录态。

其余分享接口需要登录且**只作用于自己的分享**：

| 接口 | 说明 |
| --- | --- |
| `POST /apis/share/create` | 需要 `file:share` 权限码 |
| `DELETE /apis/share/cancels`、`/clears` | 需 `file:share` 权限码 |
| `GET /apis/share/pages`、`GET /apis/share/{shareId}`、`GET /apis/share/{shareId}/access/records` | 查询均带 `USER_ID = 当前登录人` 条件，查不到即报「分享不存在」 |

> 注意 `GET /{shareId}`（属主详情）与 `GET /{shareId}/info`（匿名提取页元信息）是两个接口：前者返回完整 VO 给分享管理者，后者只返回 `FileShareThinVO`（名称、是否需码、是否过期、文件数），不泄露属主信息。

## 5. 创建分享时序

```
浏览器                         后端
  │ ShareModal「生成分享链接」  │
  │ POST /apis/share/create    │
  ├───────────────────────────►│ @SaCheckPermission("file:share")
  │ { fileIds[], expireType,   │ FileShareServiceImpl.createShare（事务）
  │   needShareCode,           │  ├─ fileIds 去重，逐个 getAuthorizedFile（只能分享自己的文件）
  │   maxViewCount?,           │  ├─ shareName 未填 → 取第一个文件名；多个文件则 "xxx等N个文件"
  │   maxDownloadCount? }      │  ├─ expireType: 1→+7天  2→+30天  3→自定义  4→永久(null)
  │                            │  ├─ needShareCode → RandomUtil.randomString(4) 生成提取码
  │                            │  ├─ scope 空则写 "download"（列非空无默认，见 §1）
  │                            │  ├─ 保存 file_shares + 逐条保存 file_share_items
  │                            │  └─ 更新被分享文件的 last_access_time
  │  ◄── { id(=分享token), shareCode, expireTime, ... }
  │ 前端拼装：分享链接 {origin}/s/{id}
  │           直链(仅单文件) {origin}/apis/share/{id}/raw/{fileId}
```

**要点**

- 提取码在服务端生成（4 位随机串），响应体明文返回给分享者展示/复制；匿名用户只能通过页面校验。
- `expireType=4` 时 `expire_time` 落 `NULL`，表示永久有效。
- 直链只对「单文件分享」展示（文件夹分享没有单一文件可指）。

## 6. 匿名提取与下载链路

### 6.1 分享页访问时序

```
匿名浏览器                         后端
  │ GET /s/{shareToken}（前端路由） │
  │ GET /apis/share/{token}/info   │
  ├───────────────────────────────►│ getFileShareThinVO：是否存在 / 是否需码 / 是否过期
  │  ◄── { hasCheckCode, isExpire }│
  │                                │
  │ [需码] POST /apis/share/verify/code
  ├───────────────────────────────►│ verifyShareCode：故意 sleep 200ms 增加爆破成本
  │                                │  └─ 比对 share_code，不符 → "提取码错误"
  │ 通过后 shareCode 写入 URL query │
  │ GET /apis/share/{token}/items[?parentId=xxx]
  ├───────────────────────────────►│ getShareFileItems：
  │                                │  ├─ getValidShare（存在 + 未过期）
  │                                │  ├─ 根级：列出 items 关联的文件
  │                                │  ├─ 有 parentId：先校验该目录可达（见下），再列其子项
  │                                │  └─ recordShareAccessLog → 异步落访问记录
  │  ◄── FileVO[]                  │
```

**分享内文件可达性校验**（`getShareAccessibleFile`）：从目标文件沿 `parent_id` 向上回溯，任一祖先命中 `file_share_items` 即放行；同时要求文件属主等于分享属主且未删除。因此：

- 把分享内文件夹里的**新文件**暴露给访客（属主分享文件夹后往里放文件，访客能看到）；
- 文件被删除/移出分享目录后，链路断裂，返回「文件不在分享范围内」。

### 6.2 单文件下载与直链

```
GET /apis/share/{shareId}/download/{fileId}   → 恒为 attachment + application/octet-stream
GET /apis/share/{shareId}/raw/{fileId}        → 白名单媒体 inline，其余 attachment
```

两者都走同一条校验链 `downloadFiles`：

1. `getValidShare`：分享存在且未过期；
2. `getShareAccessibleFile`：目标文件在分享范围内（祖先回溯）；
3. 按文件所属存储平台取 `IStorageOperationService`，`isFileExist` 校验后取流。

`raw` 的内联白名单（`isSafeInlineType`）：

| 允许 inline | 说明 |
| --- | --- |
| `image/*` 但**不含 svg** | svg 可内嵌脚本，杜绝存储型 XSS |
| `video/*`、`audio/*` | 原生标签播放 |
| `application/pdf` | 内嵌预览 |
| `text/plain`（强制 UTF-8） | 文本直读 |
| `application/json` | 结构化文本 |

**白名单之外一律 `attachment` + `application/octet-stream`**，并统一加 `X-Content-Type-Options: nosniff`——html/xml 等可执行内容永远不以内联方式回给浏览器。

### 6.3 文件夹打包下载

`POST /apis/share/{shareId}/folder-download/tasks/{folderId}` 创建异步打包任务 → 轮询 `GET .../tasks/{taskId}` 查进度 → `GET .../tasks/{taskId}/file` 下载 zip → `DELETE .../tasks/{taskId}` 取消并清理。每个接口都重复 §6.1 的「分享有效 + 文件夹可达」校验（任务本身可复用，权限不随任务漂移）。

## 7. 提取码（密码分享）的设计边界

- 分享类型只区分「公开分享 / 密码分享」，即有无 `share_code`。
- 提取码校验是**页面级软门槛**：`/info` 告诉前端是否需要输码，前端输码通过后才渲染文件列表；但 `items`、`download`、`raw` 后端**不再校验提取码**。这是有意的取舍——分享的目标是匿名可下载，提取码挡的是「打开分享页」这一步。
- 服务端仍对提取码做了两个加固：校验接口在匿名清单内但故意延迟 200ms；比对失败抛业务异常统一文案。
- 若未来需要强校验，可把 `shareCode` 作为 query 参数传入 `items/download/raw` 并在 `getValidShare` 中比对，改动集中在一个私有方法。

## 8. 计数与访问记录

| 机制 | 现状 |
| --- | --- |
| 访问记录 | **已启用**：`getShareFileItems` 每次列目录时发布事件，`@Async` 落库 `file_share_access_record`，在「我的分享 → 访问记录」弹窗展示 |
| `view_count` / `download_count` | **预留未启用**：创建时置 0，递增逻辑（Redis 自增 + 与分享有效期同步过期）代码已注释保留 |
| `max_view_count` / `max_download_count` | **预留**：表单可填、落库保存，后端校验链未消费这两个值 |

> 若要启用次数限制：在 `downloadFiles`（下载）与 `getShareFileItems`（查看）中原子递增并比对 max 值即可，注意直链下载同样要计数。

## 9. 与媒体流防盗链的关系

分享的下载/直链**不经过** previewToken 防盗链（它们本身就是匿名开放接口）。防盗链针对的是**登录用户网盘内的媒体流接口** `/api/file/stream/preview/{fileId}`：

- 该接口已纳入 `PreviewInterceptor`（order 3，覆盖 `/preview/**`、`/archive/preview/**`、`/api/file/stream/preview/**`），无有效 `previewToken` 一律 403；
- `previewToken` 由登录接口 `POST /preview/token/{fileId}` 签发（先 `getAuthorizedFile` 校验归属），Redis 键 `fs:preview:token:{token}` → fileId，TTL 5 分钟；
- 分享页**不使用**该流接口：分享内媒体预览直接新标签打开 `raw` 直链，匿名可达且无 token 需求；网盘内缩略图/预览弹窗的 token 签发细节见 `FileInfoServiceImpl#fillThumbnailUrl`、`ArchiveFilePreviewService` 与 `fs-ui/src/utils/preview-types.ts`。

## 10. 接口清单

| 方法 | 路径 | 鉴权 | 说明 |
| --- | --- | --- | --- |
| POST | `/apis/share/create` | 登录 + `file:share` | 创建分享 |
| DELETE | `/apis/share/cancels` | 登录 + `file:share` | 批量取消 |
| DELETE | `/apis/share/clears` | 登录 + `file:share` | 清空全部分享 |
| GET | `/apis/share/pages` | 登录 | 我的分享分页（仅本人） |
| GET | `/apis/share/{shareId}` | 登录（仅本人） | 分享完整详情 |
| GET | `/apis/share/{shareId}/access/records` | 登录（仅本人） | 访问记录 |
| GET | `/apis/share/{shareId}/info` | 匿名 | 提取页元信息（ThinVO） |
| POST | `/apis/share/verify/code` | 匿名 | 提取码校验（200ms 延迟） |
| GET | `/apis/share/{shareId}/items` | 匿名 | 分享内文件列表（`parentId` 逐级浏览） |
| GET | `/apis/share/{shareId}/download/{fileId}` | 匿名 | 单文件下载（恒 attachment） |
| GET | `/apis/share/{shareId}/raw/{fileId}` | 匿名 | 直链（白名单 inline，其余 attachment） |
| POST/GET/DELETE | `/apis/share/{shareId}/folder-download/tasks/...` | 登录 | 文件夹打包下载任务族 |

前端路由：`/s/:shareToken`（`fs-ui/src/pages/share/index.tsx`），状态机为 加载中 → 错误 / 已过期 / 需提取码 / 文件浏览 四态；文件浏览态提供列表/网格视图、面包屑逐级浏览、行内「预览」（`window.open` 直链）与「下载」（构造 `<a download>` 点击，不在 URL 携带任何凭证）。

## 11. 取消与失效

| 场景 | 行为 |
| --- | --- |
| 主动取消（单个/批量/清空） | 删除 `file_shares` 行 + 关联 `file_share_items`；`info` 接口即报「分享不存在」 |
| 到期 | 分享行保留；`getValidShare` 校验 `expire_time` 后拒绝，分享页展示「已过期」 |
| 文件被删除/移出 | 分享行仍在，但祖先回溯断裂，下载/浏览报「文件不在分享范围内」 |
| 文件被移动到分享目录内 | 祖先回溯命中后自动可见（无需更新 items） |
