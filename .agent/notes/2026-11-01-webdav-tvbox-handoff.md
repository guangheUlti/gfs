# 2026-11-01 会话交接：GFS WebDAV / 影视仓接入排查与修复

> 归档说明（2026-11-14）：原文件位于 `.freebuff/`，该目录已按要求清理。文中提到的 `.freebuff/...` 临时脚本与产物已不存在，保留本文仅用于记录当时的结论与排查过程。

> 交接对象：接手本任务的 agent。本文记录当天所有结论、证据、已改动代码、产物位置与未完成事项。
> 环境：Windows 本地开发机，仓库 `D:\workspace\gfs`；生产 `http://smartqq.fzdzyun.com`（账号 `guanghe` / `guanghe`）。

---

## 0. 一句话总结

- 生产 WebDAV 侧的「一段时间内所有请求 500」已定位并修复（sa-token 缓存 token 失效），已提交、已打进 5.0.12 发行包，**但生产仍在跑旧 jar，未部署**。
- 影视仓（TVBox 内核）通过 WebDAV **列不出目录、也放不出视频**，经实测确认是**客户端缺陷**（不渲染目录项；播放器请求不带 Basic 认证 → 401），不是 GFS 服务端问题。
- 影视仓的正解是走项目已实现的 **AList v2 兼容入口**（游客只读 + 签名直链），但**线上 AList 服务当前是关闭状态**，且本地验证时游客链路返回 401，需要继续排查。

---

## 1. 已完成的工作（按时间顺序）

### 1.1 生产数据整理：课程目录批量改名
- 生产 `主力行为第0期` 下 99 个文件由 `1~2 位数字 + 名称` 统一改为 `001_xxx.mp4` 三位数字前缀。
- 走 Web 登录 API + 文件管理 API（`/apis/file/list`、重命名接口）完成，未走 WebDAV（当时 WebDAV 为只读模式）。
- 校验：该目录 133 个文件全部匹配 `^\d{3}_`，排序首条 `001_黄金分割.mp4`、末条 `133_第一节-自己的风格定位.mp4`。
- 临时脚本：`.freebuff/rename-web.mjs`（dry-run / `--apply`）。

### 1.2 前端默认排序改为按名称升序
- 文件页默认排序由「修改时间倒序」改为「名称升序」。
- 改动：
  - `fs-ui/src/pages/files/hooks/useFileList.ts`（`orderBy` / `orderDirection` 默认值）
  - `fs-ui/src/pages/files/components/FileListView.tsx`（同名 prop 默认值）
- 后端名称排序已有自然数字比较逻辑（`FileInfoServiceImpl` 中 `display_name` 分支），无需改动。
- 提交：`9b3f61b feat(ui): 文件页默认按名称升序排序`。

### 1.3 生产 WebDAV 全站 500 的定位与修复（核心）
**现象**：生产 `/dav` 上所有带认证的请求（PROPFIND/GET）返回 500，网页端一切正常；约 30 分钟后自行恢复。

**定位过程**
1. 本机 curl 复测：某个时间窗内 `/dav/` 全部 500，而 `/apis/auth/login`、首页都 200；OPTIONS 200、错误密码 401 → 故障发生在「认证通过之后」。
2. 生产日志（用户提供 `C:\Users\Administrator\Downloads\gfs.2026-11-01.log`）中有 46 次 `NotLoginException: token 无效`，栈为：
   ```
   StpUtil.getLoginIdAsString
   ExternalStorageScopeService.apply(ExternalStorageScopeService.java:68)
   DavAuthFilter.doFilter(DavAuthFilter.java:129)
   ```
   时间跨度 10:11:53 → 10:31:24（约 20 分钟后自行消失）。
3. 本地复现（dev profile + 本地 MySQL/Redis）：PROPFIND 正常 207 → 用 redis-cli 清空 `Authorization:*`（sa-token 会话键）→ 立刻 500，日志同样是该 `NotLoginException`。

**根因**
`DavUserAuthenticator.bridgeSaToken` 有一份 30 分钟的内存 token 缓存，只判断「是否过期」，**不校验该 token 对应的服务端会话是否还存在**。退出登录、管理员踢下线、Redis 重启/清空都会让会话消失，缓存中的旧 token 随即失效；后续 `ExternalStorageScopeService.apply()` 调 `StpUtil.getLoginIdAsString()` 抛异常 → 整个 `/dav` 链路 500，直到缓存过期才自愈。

**修复**
`fs-modules/fs-service/.../webdav/DavUserAuthenticator.java`：复用缓存前用 `StpUtil.getLoginIdByToken(token)` 探测会话是否仍有效（且登录 ID 与缓存一致），失效则移除缓存并重新 `login`，请求无感恢复。该方法被 WebDAV / SFTP / FTP 共用，三者一并修好。
- 提交：`6fceb2b fix(webdav): 会话失效后校验缓存 token，避免整条协议链路 500`。
- 验证：本地清空全部 sa-token 会话后连续两次 PROPFIND 仍返回 207，日志中 `NotLoginException` 为 0；生产在缓存到期时刻（10:31 复测）自动恢复 207，与 TTL 吻合。

### 1.4 打包与提交
- 提交并推送：`ea12022..f52e67c main`（3 个 commit，含 `f52e67c chore(release): 同步 5.0.12 前端构建产物`）。
- 打包命令：
  ```powershell
  powershell -ExecutionPolicy Bypass -File script\package-release.ps1 -Version 5.0.12 -MavenCmd D:\devTools\maven\bin\mvn.cmd
  ```
- 产物：`release\gfs-5.0.12-windows-x64.zip`（347.8 MB，sha256 `747E7638CCE75341F25B1E5DAD0C662B1D7F9815D194DC674885BF8FB9AD24F3`），覆盖当天 09:54 的旧包。
- 校验：反编译内嵌 jar 确认 `DavUserAuthenticator` 含 `isTokenAlive`；内嵌前端与 `fs-ui/dist` 资源哈希一致；zip 中 `data/mysql`、`logs`、`storage` 只有空目录。

---

## 2. 影视仓（雷电模拟器）实测结论

**环境**：雷电模拟器 `emulator-5554`（Android 9），adb 路径 `D:\software\leidian\LDPlayer9\adb.exe`，影视仓包名 `com.huawei.himovceie`（TVBox 内核，`com.github.tvbox.osc`）。

### 2.1 服务端侧全部正常
- 模拟器内直接 `curl -X PROPFIND http://smartqq.fzdzyun.com/dav/` → **207，54ms**，返回 `主力行为第0期`、`MV`。
- 带影视仓式 XML 请求体（`<D:propfind><D:allprop/></D:propfind>`）同样 207。
- 根目录 XML 结构、`href` 前缀 `/dav/`、集合 href 结尾斜杠都符合 RFC 4918。

### 2.2 客户端缺陷一：不渲染目录项（导致「根目录什么都没有」）
用本地反向代理 `.freebuff/dav-proxy.mjs`（监听 8099，转发到生产）做对照实验：

| 实验 | 响应内容 | 影视仓表现 |
|---|---|---|
| A. 原样转发 `/dav/` | 2 个集合（目录） | **空列表** |
| B. 把根请求改写到 `主力行为第0期` | 133 个 mp4 文件 | **全部正常列出** |
| C. 给集合条目补 `getcontenttype: httpd/unix-directory` + `getcontentlength: 0` | 2 个集合 | 仍空 |
| D. 在文件列表里注入一个 ASCII 集合项 `zzz-fake-dir.mp4/` | 文件 + 1 集合 | **整页变空**（连文件也不显示） |

结论：这个版本的影视仓 WebDAV 浏览器**遇到子目录项会导致整份列表解析为空**，只能展示纯文件列表。
（用户之前看到的「进入文件夹后出现同名文件夹」，其实也是同一解析缺陷的表现。）

### 2.3 客户端缺陷二：播放请求不带认证（导致「播放失败」）
- 点开视频后 logcat：
  ```
  E/ExoPlayerImplInternal: androidx.media3.datasource.HttpDataSource$InvalidResponseCodeException: Response code: 401
  ```
- 匿名 GET 线上文件复测同样 401 → 播放器的 OkHttpDataSource **不做 Basic 挑战应答**；它只有列目录的 WebDAV 客户端会先 401 再带 Basic 重试。
- 结论：**WebDAV 这条路在这台设备上无法播放**，只能看到文件列表。

### 2.4 其他观察
- 影视仓点「齿轮」编辑网盘配置时会自身崩溃：`BadTokenException: Unable to add window -- token null is not valid`（应用问题，与 GFS 无关）。
- 模拟器里原有的 `GFS-WD` 盘连的是**开发环境**（列出 `.flattened-pom.xml`、`测试视频.mp4`、`单分片-*.bin`），不是线上。
- 影视仓的入口结构：`本地` → 存储空间列表 → `+` 可选 `本地目录 / Webdav / Alist网页`；Webdav 表单含「空间名称 / WebDAV地址 / 初始路径 / 用户名 / 密码」。
- 「初始路径」输入框对 `/` 触发系统路径补全，会把值改成 `C:/Users/...` 之类脏值，填表时注意清空。

---

## 3. 当前未解决 / 待继续

### 3.1 部署（最高优先级）
生产仍运行旧 jar，WebDAV 会话失效问题会复现（表现：整站 `/dav` 500 持续到缓存过期，最长 30 分钟）。
部署步骤：服务器 `bin\stop.bat` → 用 `release\gfs-5.0.12-windows-x64.zip` 中的 `lib\fs-admin.jar` 覆盖、`frontend\` 一并覆盖 → `bin\start.bat` → `bin\status.bat` 确认。

### 3.2 影视仓接入方案（需要决策 / 二选一）
- **方案 A（推荐）**：用「Alist网页」入口。项目已实现 AList v2 只读兼容：`POST /api/public/path`、`POST /api/public/search`、签名直链 `/d/{path}?sign=...`，播放器无需认证即可播放。
  - 但线上当前：`POST /api/public/path` → `{"code":500,"message":"AList service is disabled"}`，即 AList 服务未启用。
  - 需要：GFS 网页 → 服务 → AList → 启用 + 打开「游客访问」+ 绑定一个公开账号（只读身份），然后用其地址 `http://smartqq.fzdzyun.com` 在影视仓新增「Alist网页」盘。
- **方案 B**：保留 WebDAV，但换支持媒体请求带认证的客户端（VLC / Kodi / Infuse），或在影视仓侧等其修复。

### 3.3 本地 AList 游客链路的疑点（未查完）
在本地 dev 实例上把 `service_settings` 中 alist 行改为 `enabled=1, allow_guest=1, guest_user_id=<davfix 的 id>` 并重启后端后，`POST /api/public/path` 仍返回 `{"code":401,"message":"token is invalidated"}`。
排查线索：
- `AlistAuthFilter.doFilter`：游客放行条件为 `guestUserId != null && 无 Authorization 头 && isGuestReadOp(path)`；不满足则走 `AlistResponse.unauthorized`，即该 401 文案。
- 需确认 `AlistGuestResolver.resolveUser` → `ServiceSettingService.isGuestAllowed(setting)` 读的是哪一列/哪段配置，以及 `isGuestReadOp` 是否包含 `/api/public/path`。
- 也可能是直接改库与实体字段映射不一致（该环境原本缺列，见 3.5）。

### 3.4 可选加固（未实现）
若坚持 WebDAV 给播放器用，可考虑给 `/dav` 的 GET 增加「短期签名 URL / query token」放行方式；但影视仓不会自行附加这些参数，故对当前客户端无效，仅对其他可控客户端有意义。

### 3.5 我对本地开发环境的改动（交接时注意）
- 启动了 `D:\devTools` 的 MySQL（3306）与 Redis（6379）。
- `gfs` 库 `service_settings` 表原本缺列，已补：`read_only`、`allow_guest`、`guest_user_id`、`storage_setting_id`。
- 通过注册接口创建了本地用户 `davfix`（密码 `guanghe`），并把 `status` 手工改为 0 以便 WebDAV 认证。
- `service_settings` 中 `webdav` 行 `enabled=1`，`alist` 行 `enabled=1, allow_guest=1, guest_user_id=davfix`。
- 本地后端以 `mvn spring-boot:run -Dspring-boot.run.profiles=dev` 运行在 **2000 端口**（可能仍在运行）。
- 这些只影响本地开发库，不属于仓库改动，未提交。

### 3.6 模拟器里我创建的测试盘
`GFS-PROD`（线上 `/dav`）、`T1`（走本机代理，代理已停止）、`T2`（线上 `/dav/<URL 编码的 主力行为第0期>`）。可让用户在「存储空间」里删除。

---

## 4. 关键文件与命令索引

| 用途 | 位置 |
|---|---|
| WebDAV 认证/会话桥接 | `fs-modules/fs-service/src/main/java/com/guanghe/fs/service/webdav/DavUserAuthenticator.java` |
| WebDAV 过滤器（Basic、只读、存储作用域） | `.../webdav/DavAuthFilter.java`、`.../manager/ExternalStorageScopeService.java` |
| WebDAV 控制器 | `.../webdav/DavController.java`、`.../webdav/PropfindXmlWriter.java` |
| AList 兼容入口 | `.../alist/AlistAuthFilter.java`、`AlistV2Controller.java`、`AlistFsController.java`、`AlistGuestResolver.java` |
| 设计文档 | `doc/11-对外文件服务设计.md` |
| 改名脚本（临时） | `.freebuff/rename-web.mjs` |
| 抓包代理（临时） | `.freebuff/dav-proxy.mjs`（支持 `REWRITE_ROOT` / `REWRITE_SUB` / `INJECT_FILEDIR` / `COLLECTION_HINTS` 开关） |
| 模拟器辅助脚本（临时） | `.freebuff/ui.sh`（按文本查找控件并点击）、`.freebuff/shot.sh`（截图转 jpg） |
| 发行包 | `release/gfs-5.0.12-windows-x64.zip` |

常用命令：
```bash
# 生产 WebDAV 冒烟
curl -s -o /dev/null -w "%{http_code}\n" -u guanghe:guanghe -X PROPFIND -H "Depth: 1" http://smartqq.fzdzyun.com/dav/

# 影视仓相关 adb
ADB="D:/software/leidian/LDPlayer9/adb.exe"
"$ADB" shell uiautomator dump //sdcard/ui.xml
"$ADB" shell cat //sdcard/ui.xml | grep -oE 'text="[^"]+"'
"$ADB" logcat -d -v time | grep -E "ExoPlayerImplInternal|AndroidRuntime" | tail -20
```

---

## 5. 给接手 agent 的建议动作顺序

1. 先确认生产是否已部署 5.0.12（对比 `lib\fs-admin.jar` 里 `DavUserAuthenticator` 是否含 `isTokenAlive`），没有就推动部署。
2. 打开线上 AList 服务 + 游客访问 + 绑定公开账号（网页端「服务」页面），然后：
   - `curl -s -X POST -H "Content-Type: application/json" -d '{"path":"/"}' http://smartqq.fzdzyun.com/api/public/path` 应返回目录列表；
   - 再在模拟器里新增「Alist网页」盘验证能否列目录并播放（播放依赖 `/d/{path}` 直链）。
3. 若仍 401，按 3.3 的线索排查 `isGuestAllowed` / `isGuestReadOp`（必要时先看本地 dev 实例，成本更低）。
4. 明确告知用户：WebDAV 在影视仓上只能列文件、不能播放（客户端缺陷），不要再在 WebDAV 上花时间。
