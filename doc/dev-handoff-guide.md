# GFS 开发交接实施指南

> 目标读者：负责接手 GFS 后续开发的 agent：开工前通读本文，按第 2、6 节环境与验证流程操作，遇到异常先查第 5、9 节的坑与遗留清单
> 代码基线：2026-09-13，commit `23af20d`（v3.1.0）+ **工作区大量未提交改动（属正常状态，见第 0.1 节）**；`文件:行号` 锚点漂移以符号搜索为准
> 执行约束：未经用户明确要求不得 git commit / push；不得回退或"整理"工作区未提交改动
> 最后更新：2026-09-13 —— 首版：多存储支持、直链移除、头像 data URI、工具栏合并等改动的交接

---

## 0. 项目约定与协作方式

### 0.1 工作区有大量未提交改动是故意的

- 用户明确要求：**所有改动保持未提交，直到用户说"提交"才提交**。当前工作区约 36 个修改文件 + 4 个新增目录（见第 7 节图谱），全部是本阶段已完成并实测的功能，**不要**以"工作区脏"为由提交、stash、回退或部分还原
- `git commit` 会因 Qoder post-commit 钩子挂起 2 分钟以上：提交时用长超时（≥300s），提交后用 `git log` 复核是否真的成功
- 提交时排除 `.freebuff/`；`D:\workspace\lab` 下的 alist/ffs/gsite/seafile 是对标参考项目，不属于 gfs，绝不纳入提交
- 工作区中来历不明的改动可能是用户用 FreeBuff agent 做的半成品，勿擅自提交或回退

### 0.2 行为偏好

- UI 视觉调整幅度必须**肉眼可辨**（±4px 级会被评价"没变"），改完实测并报出前后 px 值
- 各页面样式/交互必须跨页面一致；媒体预览点开即自动播放；发现不一致应主动统一
- 改动完成后必须实测验证（编译 + 接口/浏览器），不能只凭编译通过就报完成
- 未必要留注释；只在反直觉处写一行说明"为什么"

## 1. 项目全景

| 模块 | 说明 |
| --- | --- |
| `fs-admin` | 主应用： fat jar 入口、Web 配置（`web/WebMvcConfig.java`）、i18n、各 profile yml |
| `fs-modules/fs-system` | 用户/认证/登录管理/头像（`util/AvatarUtils.java`） |
| `fs-modules/fs-file` | 文件、回收站、收藏、传输、分享、预览 token |
| `fs-modules/fs-storage` | 存储平台/配置管理（`StorageSettingServiceImpl.java` 是核心） |
| `fs-framework/fs-storage-plugin/*` | 存储插件：core（接口/StorageUtils）、boot（Registry/Manager/InstanceFactory）、local/localmount/webdav/sftp/ftp/smb/rustfs/minio/aliyunoss |
| `fs-ui` | React 19 + Vite 前端（src-pages 按页面组织，locales 双语） |
| `release/deploy-package` | Windows 部署包（bin 脚本 + 外部 conf + 自带 mysql/redis） |
| `doc/` | 机制设计与实施指南文档（索引与写作规范在 `doc/README.md`） |

## 2. 开发环境与常用命令（Windows + Git Bash）

### 2.1 启动/停止

```bash
# 后端（dev profile，端口 2000；fat jar 直启，CWD 必须在仓库根——相对路径 ./storage 以此为基准）
'/d/devTools/jdk/jdk21.0.12.1/bin/java.exe' -Dfile.encoding=UTF-8 -jar fs-admin/target/fs-admin.jar \
  > /d/tmp/gfs-backend-run.log 2>&1 &      # 用 run_in_background 更好
grep -c "Started FsAdminApplication" /d/tmp/gfs-backend-run.log   # 就绪探针

# 停止：jar 被 java 进程锁住，重打包前必须先停
pid=$(netstat -ano | grep ':2000 ' | grep LISTEN | head -1 | awk '{print $NF}')
powershell -Command "Stop-Process -Id $pid -Force"
# 注意：`taskkill //PID` 在本机会被判为 UNC 路径而失败，一律用 Stop-Process
```

- 前端 dev：端口 8000，vite 代理 `/apis` → 2000；通常已在运行（`netstat -ano | grep ':8000 ' | grep LISTEN`），没有则 `cd fs-ui && npm run dev`
- 本机 3306（MySQL）/6379（Redis）被 gfs 长期占用；验证 release 自带 mysql/redis 的部署包时，复制到 `D:\tmp` 并改备用端口

### 2.2 构建/检查

```bash
cd /d/workspace/lab/gfs                                  # mvn 必须在仓库根跑（CWD 易漂移，命令前显式 cd）
'/d/devTools/maven/bin/mvn' -q -DskipTests package       # mvn 不在 PATH，用全路径；不存在 /d/devTools/apache-maven-*
cd fs-ui && ./node_modules/.bin/tsc --noEmit             # 前端类型检查必须在 fs-ui 内执行
```

## 3. 认证与 API 调用配方

- dev 账号 `admin/admin`；登录：`POST /apis/auth/login`，body `{"loginType":"password","account":"admin","password":"admin"}`，**token 在 `data.accessToken`**（不是 `data.token`，取错会得到莫名其妙的 401）
- 后续请求带 `Authorization: Bearer <accessToken>`；切换存储的请求再带 `X-Storage-Platform-Config-Id`（前端 `fs-ui/src/api/request.ts` 拦截器自动注入）
- sa-token 配置（application.yml）：`is-read-cookie: false`（Bearer 前缀与裸 cookie 冲突，**cookie 通道已关**）、`is-read-body: true`（下载/SSE 用 query 传 token）。因此 `<img>` 标签无法通过鉴权——任何"浏览器直开"的资源只能走 data URI 或 previewToken
- 高频端点：文件列表 `GET /apis/file/list?pageNum=1&pageSize=10`（**不是** `GET /apis/file`，后者返回空）；用户信息 `GET /apis/user/info`（`/apis/user/getUserInfo` 不存在，返回业务 404）；存储三件套 `GET /apis/storage/platforms`（平台表）、`/apis/storage/platform/settings`（配置列表，需 storage:manage）、`/apis/storage/active-platforms`（已激活，匿名可用）
- 上传 API：`POST /apis/transfer/init`（totalChunks/chunkSize/mimeType 必填，**返回 data 直接是 taskId**）→ `check`（fileMd5/fileName）→ chunk FormData → 轮询 `/apis/transfer/files`
- 验证脚本习惯放 `/d/tmp/*.mjs`（node 24 有 fetch）；本机无 Office/Python/zip

## 4. 架构关键点（易踩错的部分）

### 4.1 存储体系

- 插件以 `@StoragePlugin` 注解声明（identifier/name/description/icon/schemaResource），`StoragePluginRegistry` 经 ServiceLoader 加载；前端动态表单字段由 `resources/schema/*-schema.json` 驱动（只支持 label/dataType/identifier/validation.required）
- **数据库是存储配置的唯一生效来源**：yml（含 jar 内 dev/prod/docker 与 deploy 包 conf）不再有 `fs.storage.local` 配置块；`LocalStorageProperties` 代码默认值（basePath=`./storage`）仅作全新库首次 seed
- 内置本地存储：`STORAGE_SETTING` 表固定行 `id="Local"`，`StorageSettingServiceImpl.initBuiltinLocalSetting()`（ApplicationReadyEvent）懒建 + 把 DB 行注入 `LocalStorageManager` 作为属性覆盖；`LocalStorageManager.reset()` 热重建实例
- 多激活：多个 enabled 行可同时生效；每请求头 `X-Storage-Platform-Config-Id` 决定当前实例；`StorageUtils.normalizeConfigId("Local")→null`，`FILE_INFO.STORAGE_PLATFORM_SETTING_ID` 为 NULL 即内置 Local
- 前端当前存储：`localStorage['current-storage-platform']`（JSON `{settingId,platformName}`）；无该键 = 内置 Local；登录时 `auth-context` 只保留仍有效的已选
- 展示顺序：`StorageUtils.PLATFORM_DISPLAY_ORDER`（Local→LocalMount→WebDAV→SFTP→FTP→Smb→RustFS→AliyunOSS→Minio，未收录排最后），后端三处统一排序（平台列表/激活列表/配置列表），前端不再排
- 文件列表来自 DB 索引，与物理根目录无关——改根目录只影响新上传文件（编辑弹窗有提示文案）
- 删除保护：内置 Local 不可删/禁；有文件的配置不可删（`FileInfoService.countByStorageSettingId` 回调注入，fs-file 不反向依赖 fs-storage）

### 4.2 安全模型（2026-09-13 重构后）

- **匿名文件直链已彻底移除**：`/files/**` 静态映射不存在了（`LocalStaticFileMapping` 已删），未命中路径走 SPA 兜底返回 index.html；任何需要"浏览器直接打开"的资源要么 data URI 要么 previewToken
- `getFileUrl` 对 Local 返回 `"/"+objectKey`（不可直接打开的相对 key）；`GET /apis/file/url/{fileId}` 前端从未调用
- 头像：库内存 objectKey（旧数据可能是旧版完整 URL，`AvatarUtils.resolve` 自动提取兼容），输出给前端时转 data URI（本人信息 `getDetail`、在线会话、待审批三处）；头像文件恒存内置 Local 的 `avatar/<userId>/` 下
- 预览/防盗链：`/preview/**`、`/archive/preview/**` 走 PreviewInterceptor；媒体流接口一律 previewToken，**新增流接口必须签发 token**（详见 doc/file-share-design.md）
- 路径穿越被容器/过滤器层拦截（响应文本"非法请求：…"），`/files` 时代既有防线仍有效

### 4.3 Web 层

- `WebMvcConfig`：SPA 兜底 `/**` → classpath:/static/ 的 index.html（`apis/`、`api/`、`preview/`、`archive/` 前缀不回退）；安全拦截 path-pattern 只有 `/apis/**`（excludes 见 application.yml）
- 文件页工具栏：无独立搜索按钮（回车即搜）；"上传文件+▾"分裂按钮（菜单含新建文件夹/TXT/JSON/Markdown 与刷新）；搜索框宽屏 `sm:w-44`

## 5. Redis 缓存陷阱（最高频的"灵异问题"来源）

- RedisCacheManager 默认 **TTL 1h**，且 Redis 跨重启存活：换包重启后，首个读请求可能命中**旧包写入的缓存**，症状是"代码明明改了，接口还回旧数据"
- 自愈途径：对应写操作的 `@CacheEvict`；不等自愈则手动 `'/d/devTools/redis/redis8.0/redis-cli.exe' -p 6379 del <key>`（redis-cli 在 `/d/devTools/redis/redis{5.0,8.0}/`）
- 已知缓存：`storageSettings:global`、`storageActivePlatforms:global`（**启动时已自动 clear**，见 initBuiltinLocalSetting）、`user:<userId>`（getDetail 的 data URI 转换结果也缓存在此；改 VO 结构/头像逻辑后需手动 DEL）、`storagePlatform:<identifier>`、`userTransferSetting:<userId>`
- 排查口诀：接口返回"不可能的旧结构"时，先 DEL 相关缓存再怀疑代码

## 6. 验证流程（改动的标准收尾）

1. 后端改动：停进程 → 根目录 `mvn -q -DskipTests package` → 后台启动 → 探针日志 + 针对性 curl/node 验证
2. 前端改动：fs-ui 内 `tsc --noEmit` → vite dev 热更 → 浏览器实测（golden path + 边缘）
3. 浏览器用 browser-use MCP，已知约束：
   - `fill` 参数必须是 `{uid, value}`；`wait_for` 的 `text` 是**数组**；`navigate_page` 用 `{type:'url', url}`；`evaluate_script` 会被 "Possible side-effect in debug-evaluate" 拒绝，点击一律用 `click` + `take_snapshot` 取 uid
   - 视口约 626px（窄屏布局）；sm+ 宽屏效果无法直接截图，用 Tailwind 断点确定性说明
   - 会话过期会跳登录页，重新 admin/admin 登录即可；登录后如仍见旧数据，回第 5 节查缓存

## 7. 当前未提交改动图谱（commit 23af20d 之后）

| 主题 | 内容 | 主要文件 |
| --- | --- | --- |
| 多存储支持 | 内置 Local 根目录可配置（热重建）、多激活、同类多实例、删除保护、文件页存储切换器、登录保留已选 | StorageSettingServiceImpl、LocalStorageManager、StorageSwitcher.tsx、auth-context.tsx、storage-plugin-local |
| yml 移除 | 存储配置一律由数据库存储；代码默认 `./storage` 兜底；deploy 包 conf 仅留 `GFS_STORAGE_DIR` seed | application-{dev,prod,docker}.yml、LocalStorageProperties、deploy-package/conf |
| 直链移除 | 删 `/files/**` 静态映射（含临时的 LocalStaticFileMapping）；getFileUrl 返回相对 key；baseUrl 概念全链路下线 | WebMvcConfig、LocalStorageOperationService、local-schema.json |
| 头像 data URI | 库存 objectKey、三处输出转 data URI、旧完整 URL 兼容 | AvatarUtils.java（新增）、SysUserServiceImpl、SessionAdminServiceImpl |
| 平台展示顺序 | StorageUtils.PLATFORM_DISPLAY_ORDER + 三处 service 排序 | StorageUtils、StoragePlatformServiceImpl、StorageSettingServiceImpl |
| 启动清缓存 | storageSettings/storageActivePlatforms 启动 clear（升级窗口根治） | StorageSettingServiceImpl |
| 工具栏合并 | 回车即搜、上传+▾分裂按钮（菜单含刷新）、搜索框 w-64→w-44 | fs-ui Toolbar.tsx |
| i18n/预览 | 存储文案、音频/视频预览与传输页若干既有改动（v3.1.0 后延续） | locales/*、preview 组件、transfer 页 |

## 8. 已知遗留与未决事项

- `MinioStorageServiceImpl` 的 description/link 是 RustFS 文案复制错误（用户已知，未让修；注意 DB 平台表可能没有 Minio 行）
- HEVC/mkv 播放：服务端转码 / 本地转码 / 下载播放三个方向用户尚未表态，不要擅自实现
- 存储配置页动态表单不支持字段级描述文案（schema 无 description 渲染机制）；如需给字段加说明需扩展表单组件
- release 发版权限改动要同时改根 pom 与 fs-dependencies BOM 两处 revision，漏改 BOM 会报 Non-resolvable import POM

## 9. 关键文件索引

| 文件 | 内容 |
| --- | --- |
| `fs-modules/fs-storage/.../impl/StorageSettingServiceImpl.java` | 存储配置核心：内置 Local 装配/编辑/删除保护/多激活/排序/启动清缓存 |
| `fs-framework/.../storage-plugin-boot/LocalStorageManager.java` | 内置 Local 单例 + DB 属性覆盖 + reset 热重建 |
| `fs-framework/.../storage-plugin-core/.../utils/StorageUtils.java` | Local 标识、configId 规范化、平台展示顺序权重 |
| `fs-framework/.../storage-plugin-local/.../LocalStorageOperationService.java` | 本地存储实现（basePath 解析、相对路径按 CWD） |
| `fs-admin/.../web/WebMvcConfig.java` | SPA 兜底与拦截器链（安全 excludes 参考 application.yml） |
| `fs-modules/fs-system/.../util/AvatarUtils.java` | 头像 objectKey/旧URL → data URI |
| `fs-ui/src/api/request.ts` | token/存储切换头注入、401 跳登录 |
| `fs-ui/src/pages/files/components/Toolbar.tsx`、`StorageSwitcher.tsx` | 文件页工具栏、存储切换器 |
| `doc/README.md` 及 doc/ 各设计文档 | 机制细节：登录认证、文件分享、存储插件扩展、WebDAV/SFTP |
