# GFS 文件交付管道（FileServingPipeline）改造交接文档

> 归档说明（2026-11-14）：原文件位于 `.freebuff/`，该目录已按要求清理。文中提到的 `.freebuff/...` 临时脚本与产物已不存在，保留本文仅用于记录当时的结论与排查过程。

> 交接时间：2026-09-19
> 交接背景：用户认可「前端文件服务统一交付管道」方案（见本会话分析），已实施约 50% 后交接。
> 项目路径：`D:/workspace/lab/gfs`（Windows，Git Bash 环境，JDK21 在 `/d/devTools/jdk/jdk21.0.12.1/bin/java`，Maven 在 `/d/devTools/maven/bin/mvn`）
> 开发约定：简体中文回复；临时/测试脚本放 `.agent/tmp/`（已 gitignore）；提交信息 Conventional Commits + 中文，**禁止任何 AI 署名尾注**（.git/hooks/commit-msg 有物理拦截钩子）；不要主动 commit/push。

---

## 1. 方案定位（先读这个，避免走偏）

**不要**套用 `ExternalFileService` 协议生命周期 SPI（那是给 WebDAV/SFTP/FTP 独立 socket 服务的，HTTP 流出口与主服务同端口、无启停语义，套上去是空壳）。

真正做的是：**内容交付管道统一**。四个流出口的共性是同一条管道：
`鉴权（留在控制器）→ Range 解析 → 开源流 → 响应头构建 → 流式输出`。
管道只统一「鉴权之后的交付段」。

### 四个流出口现状

| 出口 | 端点 | 接入状态 |
|---|---|---|
| FileStreamController | `/api/file/stream/preview/{fileId}` + `/preview/archive/inner/{tempId}` | ✅ 已接入（编译通过） |
| FileShareController | `/apis/share/{shareId}/raw/{fileId}`（直链） | ⏳ 待接入（本次核心目标：修复直链不支持 Range） |
| FileTransferController | `/apis/transfer/folder-download/tasks/{taskId}/file`（zip 包下载） | ⏳ 待接入 |
| ArchivePreviewController | 仅确认过端点归属，流逻辑实际在 FileStreamController 内，已随其接入 | ✅ 无需单独处理 |

## 2. 已完成内容（两个新/改文件，均已编译通过）

### 2.1 新建 `fs-modules/fs-file/src/main/java/com/guanghe/fs/file/serving/FileServingPipeline.java`

`@Component`，核心 API：

```java
public ResponseEntity<StreamingResponseBody> serve(Request req, String rangeHeader)
```

- `Request` record：`fileName / size(-1 表示未知长度，不设 Content-Length) / streamOpener(start,end 闭区间) / disposition(INLINE_SAFE|ATTACHMENT) / rangeAllowed / maxRangeSize(<=0 用默认10MB) / responseExtensionOverride / cacheControl`
- 流式构建器方法：`withDisposition / withRangeAllowed / withMaxRangeSize / withResponseExtension / withCacheControl`，静态便捷 `Request.inline(...)` / `Request.attachment(...)`
- 统一能力：Range 解析（`N-M / N- / -N` 三形态、越界→416+`bytes */size`、maxRangeSize 截断）、206+Content-Range、nosniff、inline 安全类型白名单（图片排除 svg、视频/音频/PDF/文本/JSON，文本补 UTF-8 charset）、Accept-Ranges、Cache-Control 默认策略（inline 媒体 `public, max-age=604800`，附件/转换流 `no-cache`）、`filename*=UTF-8''` 编码
- 注意：管道用 `System.out.println("[serving] ...")` 打流中断日志是过渡写法（fs-file 模块的 Slf4j 可用，接手时建议改成 `@Slf4j` + `log.debug`）

### 2.2 重写 `fs-modules/fs-file/src/main/java/com/guanghe/fs/file/controller/FileStreamController.java`

- 依赖新增 `FileServingPipeline servingPipeline`，删除了原有的 `RANGE_PATTERN / handleFullRequest / handleRangeRequest / buildHeaders / buildArchiveInnerStreamHeaders / copyStream / copyStreamLimited / changeExtension / encodeFileName` 全部手写交付代码
- `preview/{fileId}`：策略分发、`strategy.supportRange() && !strategy.needConvert()` 判定 Range 允许性、转换流 `responseExtensionOverride`、`maxRangeSize=previewConfig.getMaxRangeSize()` 均保留；`openStream` 私有方法：Range 走 `storage.downloadFileRange(objectKey,start,end)`（存储侧裁剪，不过策略加工），全量走 `getFileStream` + `strategy.processStream`
- `preview/archive/inner/{tempId}`：转换流 `size=-1` 传导「不设 Content-Length」语义；字节数组源不支持 Range（`withRangeAllowed(false)`）

## 3. 待办事项（按优先级）

### 3.1 FileShareController raw 端点接入（核心目标：修复直链视频不能拖进度条）

文件：`fs-modules/fs-file/src/main/java/com/guanghe/fs/file/controller/FileShareController.java`

- 现状：`@GetMapping("/{shareId}/raw/{fileId}")` 返回 `ResponseEntity<Resource>`，service 层 `fileShareService.downloadFiles(shareId, fileId)` 返回 `FileDownloadVO{fileName,fileSize,resource(InputStreamResource)}`，**无 Range 支持**——直链视频 seek 时只能整段重下
- 接入要点：
  1. 直链是公开访问端点（无登录态），**鉴权语义（shareId 有效 + fileId 在分享内）在 service 层，保留**；管道只接交付段
  2. Range 开流需要 objectKey 维度：service 现在只给了 InputStreamResource。两种改法（推荐 a）：
     a. 在 `FileShareServiceImpl.downloadFiles` 旁边加一个新方法（如 `openShareFileRange(shareId, fileId, start, end)`）：内部复用 `getValidShare` + `getShareAccessibleFile` 拿到 `fileInfo`，然后 `storageServiceFacade.getStorageService(fileInfo.getStoragePlatformSettingId()).downloadFileRange(fileInfo.getObjectKey(), start, end)`（全量时用 `downloadFile`）；控制器 Request.streamOpener 调它
     b. 或给 FileDownloadVO 加 storagePlatformSettingId+objectKey 字段由控制器开流（侵入更小但泄漏存储细节到控制器，不推荐）
  3. `FileServingPipeline.Request.inline(fileName, fileSize, opener)` + `withMaxRangeSize(previewConfig.getMaxRangeSize())`；Disposition 语义保持原样：`isSafeInlineType` 判断已内置在管道的 INLINE_SAFE 里（非白名单自动回退 attachment），控制器里旧的 `isSafeInlineType` 私有方法与手工 header 构建删除
  4. 验证点：直链视频带 `Range: bytes=N-` 应返回 206 + Content-Range 且可 seek；`Accept-Ranges: bytes` 出现在响应头；svg/html 分享仍走 attachment
- 相关文件：`FileShareServiceImpl.downloadFiles`（约在 grep "downloadFiles" 可定位）、`FileDownloadVO`（`fs-modules/fs-file/.../domain/vo/FileDownloadVO.java`）

### 3.2 FileTransferController zip 下载接入

文件：`fs-modules/fs-file/src/main/java/com/guanghe/fs/file/controller/FileTransferController.java` 末尾 `downloadFolderTaskFile`

- 现状：手写 `Content-Disposition: attachment` + `application/zip` + contentLength，返回 Resource
- 接入：`Request.attachment(fileName, fileSize, opener)`（zip 是本地临时文件 `task.zipPath`，opener 可用 `Files.newInputStream(zipPath)`；如 service 只返回 FileDownloadVO，同 3.1 方式 a 加 `openFolderTaskFileRange` 或直接传 size + 全量 opener）
- zip 建议 `withRangeAllowed(false)`（无需断点）也可开（本地文件便宜）；`task.zipPath` 归属校验（`getAuthorizedFolderDownloadTask`）留在 service，勿动

### 3.3 回归测试（必做）

1. 编译：`/d/devTools/maven/bin/mvn -q compile -pl fs-modules/fs-file 2>&1 | grep ERROR`（或 `-pl fs-modules/fs-file -am` 连依赖）
2. 重启后端：杀掉 `:2000` 监听进程 → `(nohup /d/devTools/jdk/jdk21.0.12.1/bin/java -jar fs-admin/target/fs-admin.jar --spring.profiles.active=dev > logs/backend-restart.log 2>&1 &)`；需要先 `mvn package -DskipTests -pl fs-admin -am`（jar 被运行进程锁住，先 kill 再 package）
3. 登录取 token：`POST /apis/auth/login` body `{"loginType":"password","account":"admin","password":"admin"}`，请求头用 `Authorization: Bearer <token>`
4. 回归清单：
   - 主流媒体流：`GET /api/file/stream/preview/{fileId}`（视频文件 fileId 从 `/apis/file/list` 拿）无 Range → 200 全量；带 `Range: bytes=0-1023` → 206 + Content-Range；转换类文档（docx）无 Content-Length；越界 Range → 416
   - 直链：`GET /apis/share/{shareId}/raw/{fileId}` 带 Range → 206（本次修复点）；视频拖进度条在预览页人工点一下
   - zip 下载：创建文件夹下载任务后 `GET .../tasks/{taskId}/file` → 200 + attachment + application/zip
   - 压缩包内预览流：`/api/file/stream/preview/archive/inner/{tempId}` 仍正常
5. 前端无需改动（端点与响应语义兼容）

### 3.4 收尾

- 管道日志改 Slf4j（见 2.1 注意）
- doc/06-下载与媒体预览设计.md 建议补一段「FileServingPipeline 交付管道」说明（四个出口统一、直链新增 Range）
- 改动文件清单（本任务）：新增 `serving/FileServingPipeline.java`、改 `FileStreamController.java`、待改 `FileShareController.java` / `FileTransferController.java` / 可能 `FileShareServiceImpl.java`
- 未做提交；工作区还有本轮更早的未提交改动（对外服务 SPI 化 + FTP：`fs-service/.../spi`、`ftp` 包、`ProtocolServiceManager`、前端 services 页、doc/11 等），提交时建议分开两个 commit（`feat(service): 对外文件服务 SPI 化并新增 FTP` 与 `refactor(file): 统一 HTTP 文件交付管道`）

## 4. 环境现状（交接时刻）

- 后端运行中（:2000），MySQL(3306)/Redis(6379) 由部署包组件拉起
- 开发库 gfs 有测试残留：`file_info` 里 `direct-test.txt`（可用作流端点测试素材）；分享直链测试需先在前端或 API 建一个分享
- FTP/SFTP 对外服务处于停止状态；SMB 配置（test-smb）在库中
