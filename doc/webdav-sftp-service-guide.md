# GFS 对外文件服务实施指南（WebDAV + SFTP）

> 目标读者：负责实施编码的 agent。本文档自包含，所有决策已定，按本文执行即可，不要扩大范围。
>
> 代码基线：2026-09-09，main 分支写定时状态（所有 `文件:行号` 锚点若有漂移，以符号搜索为准）。
>
> 最后更新：2026-09-11 —— 服务端与前端管理页已随 commit `70f9fab` 落地，本文转为实施记录与维护参考。

---

## 1. 背景与已定决策

GFS 目前只有 Web 端文件管理；本任务让系统**对外充当文件服务端**，允许用户用标准协议把网盘挂载/访问为本地磁盘。

**范围（只做这两个）：**

| 协议 | 接入方式 | 认证 |
| --- | --- | --- |
| WebDAV | 复用现有 HTTP 服务（Tomcat 80 端口），路径 `/dav/**` | HTTP Basic（现有账号 + BCrypt 校验） |
| SFTP | Apache MINA SSHD 独立端口（默认 9022） | 密码认证（现有账号 + BCrypt 校验） |

**明确不做（已评估否决，勿实施）：**
- SMB：纯 Java 无成熟服务端库；NTLM 认证无法从 BCrypt 密码推导 NT 哈希。
- FTP/FTPS：本期不做。
- FUSE：FUSE 是客户端挂载技术，不是服务端协议；客户端经 WebDAV（rclone/davfs2）或 SFTP（sshfs）即可挂载，服务端无需实现。

**WebDAV 走同端口的原因**：Windows 自带的 WebDAV 重定向器（`net use` 映射网络驱动器）只支持标准 80/443 端口，独立端口会导致 Windows 原生挂载不可用。

## 2. 已核对的代码事实（实施前不再需要重复探索）

### 2.1 模块结构
- 根 pom：`D:\workspace\lab\gfs\pom.xml`，`fs-modules/pom.xml` 聚合 `fs-file`、`fs-log`、`fs-storage`、`fs-system`
- BOM：`fs-dependencies/pom.xml`（`<properties>` 管版本，第 48 行起 `<dependencyManagement>`；spring-boot 4.0.3、sa-token 1.45.0、revision 3.0.0）
- 启动模块：`fs-admin`，`fs-admin/pom.xml` 显式依赖 fs-system / fs-storage / fs-file
- 框架件在 `fs-framework/`（子模块含 fs-common-core、fs-orm、fs-security 等）

### 2.2 关键类（实施时要用的入口）
- 统一返回：`com.guanghe.fs.framework.common.domain.Result`（`Result.ok(...)`）、`PageResult<T>`
- 控制器风格参照 `fs-modules/fs-file/.../controller/FileController.java`（`@RestController @RequestMapping("/apis/...")` + swagger 注解）
- 权限唯一事实源：`fs-modules/fs-system/.../constant/UserPermissions.java`（BASE_PERMISSIONS 登录用户全有；ADMIN_EXTRA_PERMISSIONS 仅管理员；`of(boolean)` 返回全量，**新增权限两处列表都要加**）
- 密码校验：`fs-modules/fs-system/.../auth/PasswordHashService.java`——`encode(raw)` / `matches(raw, encoded)`（BCrypt-12 + 旧 SHA-256 回退），直接复用
- 用户表实体：`fs-modules/fs-system/.../domain/SysUser.java`（字段 id/username/password/status/...）；账号状态常量 `constant/UserStatus.java`
- **当前用户获取约定**：文件服务层全部用 `StpUtil.getLoginIdAsString()` 取当前用户（见 `FileInfoServiceImpl` 多处）。协议线程必须先建立 Sa-Token 上下文并登录（见 §4.5），否则现有 service 全部不可用——**不要为此重构 service 签名**
- 文件操作接口：`fs-modules/fs-file/.../service/FileInfoService.java`
  - `getList(FileQry)`（`FileQry.parentId` 为空 = 用户根目录；含 keyword/fileType/isDir 过滤，返回 `PageResult<FileVO>`）
  - `getAuthorizedFile(fileId)`、`downloadFile(fileId)`、`createDirectory(CreateDirectoryCmd)`、`renameFile(fileId, RenameFileCmd)`、`moveFile(MoveFileCmd)`、`copyFiles(CopyFileCmd)`、`moveFilesToRecycleBin(fileIds)`、`generateUniqueName(...)`
- 上传收尾参照：`FileTransferTaskService#mergeChunks(taskId)`（`fs-modules/fs-file/.../service/impl/FileTransferTaskServiceImpl.java`）——物理对象写入 + `FileObjectReferenceService` 内容级去重（contentMd5 + `acquireContentLock` + `findReusableFile`）+ FileInfo 落库，是"流式直传"新方法的权威蓝本
- 存储层：`fs-framework/fs-storage-plugin/storage-plugin-core/.../IStorageOperationService.java`
  - `uploadFile(InputStream, objectKey)` / `downloadFile(objectKey)` / `downloadFileRange(objectKey, start, end)` / `deleteFile` / `rename` / `isFileExist`
  - 活跃平台配置：`StoragePlatformContextHolder.getConfigId()`
- Sa-Token 配置：`fs-admin/src/main/resources/application.yml`——`security.path-pattern: /apis/**` 只拦截 /apis/**，`excludes` 已预置 `/dav/**`（冗余但无害）；token 头 `Authorization: Bearer <token>`、`is-concurrent: true`、`active-timeout: 3600`
- 实体基类：`fs-framework/fs-orm/.../entity/BaseEntity.java`（createdAt/updatedAt）；主键全局雪花；逻辑删字段 del_flag（注意 storage_settings 用的是 `deleted` 列名，新表跟随 storage_settings 风格即可）
- 后端 i18n：`fs-admin/src/main/resources/i18n/messages.properties`（基础=中文）、`messages_zh_CN.properties`、`messages_en_US.properties`，**三个文件必须同步**；取值 `I18nUtils.getMessage(key)`
- 操作日志：`SysOperationLogService`（fs-log），FileController 有用法示例，管理 API 可选接入

### 2.3 前端事实（fs-ui，pnpm + Vite 5173）
- 路由：`src/router/index.tsx`（`ProtectedRoute requiredPermission` + `AppRoute` children）
- 侧栏数据：`src/components/layout/data/sidebar-data.ts`（`navGroups`：files 组 + system 组；item 字段 `titleKey/url/icon{line,fill}/permission`）；过滤逻辑在 `app-sidebar.tsx`，无需改动
- 权限码：`src/types/permission.ts`（`PermissionCode` + `PermissionCodeType`）
- i18n：`src/i18n/index.ts` 静态 import `src/locales/{zh,en}/*.json` 注册命名空间；现有命名空间 common/files/layout/login/settings/share/storage/transfer
- API 层：`src/api/*.ts`（基于 `src/api/request.ts`）；页面范式参照 `src/pages/storage/`（react-query + ConfirmDialog）
- dev 代理：`vite.config.ts` 目前只代理 `/apis`

## 3. 总体架构

```
新增 Maven 模块 fs-modules/fs-service（包名 com.guanghe.fs.service）
├── config/        ServiceSetting 实体 + Mapper + ServiceSettingService（DB 持久化，表 service_settings）
├── manager/       ProtocolServiceManager（生命周期：启动自启 enabled 服务、配置变更热生效、start/stop/restart）
├── webdav/        DavController（/dav/** 单控制器分发）+ BasicAuthFilter + DavPathResolver + PropfindXmlWriter
├── sftp/          SftpServerHolder（MINA SSHD）+ GfsPasswordAuthenticator + nio/（java.nio FileSystem 桥接）
└── controller/    ServiceSettingController（/apis/service/**，管理配置，管理员权限）

修改：
├── fs-modules/pom.xml            + <module>fs-service</module>
├── fs-admin/pom.xml              + fs-service 依赖
├── fs-dependencies/pom.xml       + sshd 版本与 dependencyManagement
├── fs-modules/fs-file            + FileInfoService 新增流式直传方法（见 §4.6）
├── UserPermissions.java          + SERVICE_MANAGE
├── 3 个 messages*.properties     + service.* 文案
├── sql/mysql/gfs.sql             + service_settings 表（MySQL）
├── sql/postgresql/gfs_pg.sql     + service_settings 表（PG）
└── fs-ui：permission.ts / sidebar-data.ts / router / api/service.ts / pages/services / locales / i18n/index.ts / vite.config.ts
```

### 3.1 数据模型：表 `service_settings`

跟随 `storage_settings` 的 DDL 风格（varchar(128) 主键、json 配置列、tinyint(1) 开关、created_at/updated_at/remark/deleted）。每协议一行，`service_type` 唯一：

```sql
-- MySQL（sql/mysql/gfs.sql）
CREATE TABLE `service_settings` (
  `id` varchar(128) NOT NULL COMMENT 'id',
  `service_type` varchar(32) NOT NULL COMMENT '服务类型：webdav / sftp',
  `enabled` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否启用 0：否 1：是',
  `port` int DEFAULT NULL COMMENT '监听端口（webdav 复用 HTTP 80 端口，此列为空）',
  `bind_address` varchar(64) DEFAULT NULL COMMENT '监听地址，默认 0.0.0.0',
  `config_data` json DEFAULT NULL COMMENT '扩展配置（如 sftp 主机密钥路径）',
  `created_at` datetime DEFAULT NULL COMMENT '创建时间',
  `updated_at` datetime DEFAULT NULL COMMENT '更新时间',
  `remark` varchar(255) DEFAULT NULL COMMENT '备注',
  `deleted` tinyint(1) DEFAULT '0' COMMENT '逻辑删除 0未删除 1已删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_service_type` (`service_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci ROW_FORMAT=DYNAMIC COMMENT='对外文件服务配置';

INSERT INTO `service_settings` (`id`,`service_type`,`enabled`,`port`,`bind_address`) VALUES
('svc-webdav','webdav',0,NULL,'0.0.0.0'),
('svc-sftp','sftp',0,9022,'0.0.0.0');
```

PostgreSQL 版（`sql/postgresql/gfs_pg.sql`）：id varchar(128)、service_type varchar(32) UNIQUE、enabled boolean、port int、bind_address varchar(64)、config_data jsonb、时间戳列与该文件内其他表风格保持一致，同样插入两行种子数据。

实体 `ServiceSetting extends BaseEntity`（@Table("service_settings")），**实施时需在开发库手工执行 DDL**（dev MySQL：127.0.0.1:3306/gfs，root/root，见 §7）。

### 3.2 管理端 API（挂 /apis/service/**，Sa-Token 正常拦截）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/apis/service/list` | 全部服务配置 + 实时运行状态（running/stopped/error + 错误信息） |
| PUT | `/apis/service/{type}/config` | body: `{enabled, port?, bindAddress?}`；保存后热生效（§4.7） |
| POST | `/apis/service/{type}/{action}` | action ∈ start / stop / restart（与 storage 的 `/{id}/{action}` 风格一致） |

- 控制器方法加 `@SaCheckPermission(UserPermissions.SERVICE_MANAGE)`
- `type` 不合法抛 `BusinessException(I18nUtils.getMessage("service.type.invalid"))`；端口校验 1024–65535（service.port.invalid）
- VO 字段：`serviceType, enabled, port, bindAddress, status, error, updatedAt`

## 4. 后端实施步骤

### 4.1 依赖与模块骨架

1. `fs-dependencies/pom.xml`：`<properties>` 加 `<sshd.version>2.13.2</sshd.version>`（实施时到 Maven Central 确认 2.x 最新稳定版），`<dependencyManagement>` 加 `org.apache.sshd:sshd-core` 与 `org.apache.sshd:sshd-sftp`
2. 新建 `fs-modules/fs-service/pom.xml`（parent=fs-modules），依赖：sshd-core、sshd-sftp、fs-file、fs-system、fs-framework 公共件（参照 `fs-modules/fs-storage/pom.xml` 的写法）
3. `fs-modules/pom.xml` 加 module；`fs-admin/pom.xml` 加 fs-service 依赖

### 4.2 WebDAV：路径映射与鉴权（filter）

- `DavAuthFilter`（servlet Filter，只拦 `/dav/*`，order 在最前）：
  1. 解析 `Authorization: Basic base64(user:pass)`；缺失/格式错 → 401 + `WWW-Authenticate: Basic realm="GFS"`
  2. 按 username 查 SysUser（fs-system 的 service/mapper），校验账号状态正常（UserStatus），`passwordHashService.matches(raw, user.getPassword())`；失败 → 401（响应体不必带 JSON，纯 401 即可）
  3. **桥接当前用户**：请求跑在 Tomcat 线程上，Sa-Token 的 Spring 请求上下文可用。执行 `StpUtil.login(userId)` 得到 token，再 `StpUtil.setTokenValue(token)`——此后 service 层的 `StpUtil.getLoginIdAsString()` 正常工作
  4. **必须加内存 token 缓存**：`ConcurrentHashMap<String userId, CachedToken(token, expireAt)>`，TTL 30 分钟。原因：Basic 认证每个请求都发生，`sa-token.is-concurrent=true` 下每请求 login 会堆积 Redis 会话；缓存后同用户复用 token，TTL 取 30 分钟（`active-timeout` 3600 秒，且每次请求读 token 会刷新活跃时间，30 分钟复用安全）
- 放行后请求进入 `DavController`

### 4.3 WebDAV：控制器（单控制器 + 方法分发）

`@RestController @RequestMapping("/dav/**")`，**不要用 @RequestMapping 的 method 属性按方法拆**（Spring 的 RequestMethod 枚举不含 PROPFIND），在入口按 `request.getMethod()` switch 分发到内部 handler：

- `OPTIONS`：响应头 `Allow: OPTIONS, GET, HEAD, PUT, PROPFIND, MKCOL, DELETE, MOVE, COPY`、`DAV: 1, 2`、`MS-Author-Via: DAV`
- `PROPFIND`（核心）：解析 `Depth`（0=自身、1=一层子项；`infinity` 一律按 1 处理并返回 207）；输出 207 Multi-Status XML，手写拼装（**不引入 Milton 等三方库**），每节点含：`href`（/dav 相对路径，URL 编码）、`displayname`、`resourcetype`（目录输出 `<D:collection/>`）、`getcontentlength`、`getlastmodified`（RFC 1123 格式）、`getcontenttype`。`PropfindXmlWriter` 单独成类
- `GET/HEAD`：`FileInfoService.downloadFile(fileId)` 流式输出，设置 Content-Type（FileInfo.mimeType）、Content-Length（size），HEAD 不写 body
- `PUT`：上传/覆盖，见 §4.6 直传方法；目标同名文件存在 = 覆盖语义
- `MKCOL`：`createDirectory(CreateDirectoryCmd{parentId=解析出的父目录ID, folderName=末段名})`
- `DELETE`：`moveFilesToRecycleBin([fileId])`（回收站语义与 Web 端一致，可恢复，比永久删除安全）
- `MOVE`：目标同目录不同名 → `renameFile`；跨目录 → `moveFile`（MoveFileCmd）
- `COPY`：`copyFiles(CopyFileCmd)`（只加引用不复制物理对象，天然快）
- `PROPPATCH`：返回 207 空 multistatus（Windows Explorer 兼容）
- `LOCK/UNLOCK`：返回 501（限制见 §6；Explorer 映射与 rclone/sshfs 不受影响）
- 其他方法：405

路径解析 `DavPathResolver`：
- `/dav/`（或空）= 用户根目录（parentId 为空）；其余按 `/` 逐段解析：从根开始，每段用「userId + parentId + displayName」查询 FileInfo（可复用 `getList(FileQry)` 或直接查 FileInfoService/IService 接口），逐级下钻；任一段不存在 → 404
- 解析结果缓存于 request 属性，勿做跨请求缓存（目录内容易变）
- 目录树中带 `/` 或 `\` 的名称本就被挂载校验禁止（见 messages 的 mount.invalid.name），路径按段切分是安全的

### 4.4 SFTP：MINA SSHD 服务端

`SftpServerHolder`：
```java
SshServer server = SshServer.setUpDefaultServer();
server.setPort(port); server.setBindAddress(addr);
server.setKeyPairProvider(hostKeyProvider);      // 见下
server.setPasswordAuthenticator(gfsAuthenticator);
server.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
server.setFileSystemFactory(new GfsFileSystemFactory()); // 见 §4.5
server.start() / server.stop()
```
- **主机密钥**：首次启动生成并落盘（路径存 `config_data.hostKeyPath`，默认 `<工作目录>/data/ssh-host-key`），用 MINA 的 `SimpleGeneratorHostKeyProvider`（OpenSSH 格式可用 `SecurityUtils` 相关类，以 jar 内 API 为准）。密钥文件不存在时自动生成，已存在时加载——**服务重启后指纹必须稳定**，否则客户端告警
- **认证**：实现 MINA `PasswordAuthenticator`（签名带 `ServerSession`）。校验逻辑同 §4.2 第 2 步；成功后把 userId 存入 session attribute（`AttributeKey`），供 FileSystemFactory 取用
- SFTP 会话操作跑在 MINA 的 NIO 线程上（**不是虚拟线程**，也**没有 HTTP 请求上下文**），所以每个文件操作入口都要先做 §4.5 的上下文桥接

### 4.5 Sa-Token 上下文桥接（SFTP 专用，WebDAV 走 §4.2）

SFTP 线程无请求上下文，需为「协议线程」临时建立 Sa-Token 环境：
1. 首选 sa-token-core 1.45.0 自带的 mock 上下文工具（jar 内找 `SaTokenContextMockUtil` 或等价物，以实际 API 为准）：`setMockContext()` → `StpUtil.setTokenValue(token)`（token 从 §4.2 同款内存缓存取，无则对该 userId `StpUtil.login()` 一次并缓存）→ 执行文件操作 → finally 清理上下文
2. 若 mock 工具在 1.45.0 中不存在/不可用：自实现一个 `ThreadLocal` 版 `SaTokenContext`（参考 sa-token-core 里 `SaTokenContextForThreadLocal` 相关源码）注册使用
3. **禁止**为绕开此问题修改 FileInfoService 等现有签名

建议封装成 `SaTokenBridge.runAs(userId, Supplier)` 一个工具类，WebDAV 分支也可复用（WebDAV 已有请求上下文，直接 login+setTokenValue 即可，不必强走 runAs）。

### 4.6 SFTP 的 java.nio FileSystem 桥接（GfsNio*）

MINA SFTP 子系统基于 java.nio 文件系统工作，实现自定义 SPI（约 8 个类）即可让官方 SftpSubsystem 全量可用：

- `GfsFileSystemProvider extends FileSystemProvider`：`newFileSystem`（每 SFTP 会话一个）、`getPath`、`newDirectoryStream`、`newByteChannel`、`readAttributes`、`checkAccess`、`createDirectory`、`delete`、`move`、`getFileStore` 等；未支持的方法抛 `UnsupportedOperationException`/`FileSystemException`
- `GfsPath`：绝对路径 `/a/b/c`，解析逻辑复用 `DavPathResolver` 的「逐段下钻」逻辑（抽公共方法放 fs-service 内共用）
- 属性：`GfsBasicFileAttributes`（size / isDirectory / lastModified=FileInfo.uploadTime / fileId）
- `GfsDirectoryStream`：`FileInfoService.getList(FileQry{parentId})` 取子项迭代
- `GfsSeekableByteChannel`：
  - **读**：open 时 `downloadFile(fileId)`；seek 用 skip 重开流（小文件足够）；后续优化可换存储层 `downloadFileRange`
  - **写**：本地临时文件 spool（`java.io.tmpdir`），仅支持顺序追加写（position ≤ 当前写入位置）；`close()` 时用 §4.6 直传方法提交，随后删临时文件。随机写（position 回退）抛 IOException
  - 大小保护：单文件 spool 上限（如 5 GB）可配置于 `config_data`

### 4.7 流式直传方法（fs-file 模块，WebDAV PUT 与 SFTP 写共用）

在 `FileInfoService` 新增（实现放 FileInfoServiceImpl）：

```java
/** 流式直传：目标存在同名文件时覆盖（写新物理对象→切换引用→清理旧对象），不存在则新建记录 */
FileInfo writeFileContent(String parentId, String displayName, InputStream in, long size);
```

实现蓝本 = `FileTransferTaskServiceImpl#mergeChunks` 的收尾逻辑 + `writeTextObject`（FileInfoServiceImpl 约 418 行）的去重套路：
1. 参数校验（displayName 非法字符、parentId 归属当前用户——`getAuthorizedFile`）
2. 查同目录同 displayName 的现有文件记录
3. `StoragePlatformContextHolder.getConfigId()` 取平台；objectKey 生成方式以 mergeChunks 现有实现为准
4. `IStorageOperationService.uploadFile(in, objectKey)` 写物理对象
5. 计算 md5，`FileObjectReferenceService.acquireContentLock` + `findReusableFile` 内容级去重（可复用则删刚传的对象、引用现有物理对象）
6. 已有记录：更新 size/suffix/mimeType/uploadTime/物理引用，旧对象 `deletePhysicalFileIfUnreferencedWithLock` 清理；无记录：insert FileInfo（字段赋值参照 writeTextObject）
7. 全程 `@Transactional`，异常回滚并删除已写的孤儿对象

### 4.8 生命周期与热生效 `ProtocolServiceManager`

- 实现 `SmartLifecycle`：应用启动时读取两行配置，`enabled=1` 的自动启动（WebDAV 无独立 socket，只是让 filter/controller 生效开关；SFTP 启动/停止 MINA server）
- `apply(type, 新配置)`：enabled 不变但端口/地址变 → 重启对应服务；enabled 翻转 → start/stop；失败时状态置 `error` 并把异常信息带给 `/list`（不抛给保存动作——配置保存成功与启动失败是两件事）
- SFTP 停止要等会话排空或 `stop(true)` 强停，取后者（管理动作要求即时生效）
- WebDAV 的「停用」= 请求在 filter 直接 404/503（不可用时统一 503 + 简短文案），不要卸载 controller 映射

### 4.9 权限与 i18n（后端）

- `UserPermissions`：加 `SERVICE_MANAGE = "service:manage"`，同时进 `ADMIN_EXTRA_PERMISSIONS` 与 `of()` 的管理员分支列表
- 三个 properties 同步加（中文 base 与 zh_CN 同文案，en 翻译）：
  ```
  service.type.invalid=不支持的服务类型
  service.port.invalid=端口无效，允许范围 1024-65535
  service.not.found=服务配置不存在
  service.webdav.name=WebDAV 服务
  service.sftp.name=SFTP 服务
  ```

## 5. 前端实施步骤（fs-ui）

1. `src/types/permission.ts`：PermissionCode 加 `SERVICE_MANAGE: 'service:manage'`
2. `src/i18n/index.ts`：注册新命名空间 `services`（import `zh/services.json`、`en/services.json`）
3. `src/locales/{zh,en}/layout.json`：加 `groups.services`（服务 / Services）、`nav.webdav`（WebDAV / WebDAV）、`nav.sftp`（SFTP / SFTP）
4. `src/locales/{zh,en}/services.json`：页面文案（标题、启停、端口、监听地址、运行状态 running/stopped/error、保存、确认启停对话框、使用提示：Windows 映射 `net use Z: http://<主机>/dav`、rclone/sshfs/WinSCP 示例命令）
5. `src/api/service.ts`：`listServiceSettings()` / `updateServiceConfig(type, data)` / `serviceAction(type, action)`，走 `src/api/request.ts`
6. `src/pages/services/`：webdav 与 sftp 两个页面（可抽公共 `ServiceSettingCard`）：
   - 表单：启用开关（启停走 ConfirmDialog，参照 storage 卡片的 toggle 流程）、SFTP 的端口/监听地址输入
   - 状态区：运行状态徽标（running=绿/stopped=灰/error=红+错误信息），react-query `refetchInterval` 10s 轮询 `/apis/service/list`
   - 底部：挂载用法提示块
   - 保存成功 toast + 刷新状态
7. `src/components/layout/data/sidebar-data.ts`：在 files 组与 system 组之间插入新组 `sidebar.groups.services`，两项：`/services/webdav`（图标建议 `RiGlobalLine/RiGlobalFill`）、`/services/sftp`（建议 `RiTerminalBoxLine/RiTerminalBoxFill`），均带 `permission: 'service:manage'`
8. `src/router/index.tsx`：AppRoute children 加两条路由，均包 `ProtectedRoute requiredPermission='service:manage'`
9. `vite.config.ts`：proxy 加 `'/dav'` 指向后端（仅 dev 浏览器调试用）

## 6. 行为边界（实现时必须遵守）

1. 删除 = 进回收站（不永久删除）；客户端 DELETE 后文件在 Web 端回收站可见
2. 同名冲突：WebDAV PUT/覆盖按覆盖处理；SFTP/新建走系统现有重名策略时不要自动改名（generateUniqueName 只用于 Web 端交互场景，协议端语义要精确）
3. LOCK 不实现 → Office 直接在线编辑保存可能失败；资源管理器/跨平台挂载不受影响。文档（README/页面提示）要说明
4. Windows 原生映射只在 80/443 端口可用（这正是 WebDAV 复用 80 的原因）；`net use` 需要客户端机 WebClient 服务开启
5. Basic 认证走明文 HTTP，生产环境提示用户配 HTTPS 反代；SFTP 自带加密无此问题
6. bind_address 默认 0.0.0.0 供内网使用，页面提示不要暴露公网
7. SFTP 只支持顺序写；随机写、硬链接、符号链接一律明确抛错，不做静默降级

## 7. 验证清单（本机开发环境）

环境备忘：
- 构建：`JAVA_HOME='D:\devTools\jdk\jdk21.0.12.1' D:\devTools\maven\bin\mvn.cmd -DskipTests package`（**在仓库根目录执行**；-pl 用模块路径如 `-pl fs-modules/fs-service -am`）
- 后端：`java -jar fs-admin/target/fs-admin.jar`（80 端口）；dev 库 `application-dev.yml`：MySQL 127.0.0.1:3306/gfs（root/root）、Redis 6379——**DDL 先手工执行到 dev 库再启动**
- 前端：`pnpm dev`（5173）；类型检查 `npx tsc --noEmit`
- Windows 杀进程用 PowerShell `Stop-Process -Id <pid>`（`taskkill //PID` 会被误判 UNC 路径）
- git commit 会因 Qoder 钩子挂起 2 分钟以上：用长超时（≥300s）并以 `git log` 复核为准

验证步骤：
1. `mvn -DskipTests package` 全量通过
2. 浏览器登录 → 「服务」菜单组出现且仅管理员可见；普通用户不可见且直接访问 /apis/service/list 返回 403
3. WebDAV（curl，账号密码用真实账号）：
   - `curl -u user:pass -X PROPFIND -H "Depth: 1" http://127.0.0.1/dav/` → 207，根目录条目正确
   - MKCOL 建目录 → Web 端文件页可见；PUT 上传 → Web 端可见可预览；GET 下载内容一致；MOVE 改名；DELETE 后 Web 端回收站出现
   - 错误密码 → 401；停用服务后 → 503
4. SFTP：`sftp -P 9022 user@127.0.0.1`（Windows 自带 OpenSSH 客户端）：ls / put / get / mkdir / rm；重启后端后客户端重连无主机指纹告警；改端口重启生效
5. 后端重启后 enabled=1 的服务自动拉起
6. 前端两页面：开关→确认→状态变 running；改端口保存→状态正常；错误提示链路通
7. 回归：Web 端文件列表、上传、存储配置页不受影响（协议桥接复用了同一批 service）

## 8. 验收定义

- §7 全部通过
- 新代码零裸 new Thread（SFTP 用 MINA 自身线程模型，管理动作可用虚拟线程）
- 三个 properties 文件、zh/en locale 文件全部成对补齐
- 不引入本指南之外的依赖（仅允许 sshd-core/sshd-sftp）
