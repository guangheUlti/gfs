---
name: gfs-storage-plugin
description: GFS 存储插件体系与协议接入：模块结构、平台收敛、休眠约束、分片临时目录与已知风险
---

# GFS 存储插件与协议接入

## 模块结构

`fs-framework/fs-storage-plugin/`：

| 模块 | 说明 |
|---|---|
| `storage-plugin-core` | `IStorageOperationService` 契约、分片基类、S3 公共实现、`TempDirCleaner` |
| `storage-plugin-boot` | `StoragePluginManager` 与实例缓存，按配置创建/失效插件实例 |
| `storage-plugin-local` | 系统内置本地存储 |
| `storage-plugin-localmount` | 本地挂载点（哨兵平台，不可随显示名改名） |
| `storage-plugin-rustfs` | S3 兼容的对象存储接入（托管对象存储统一走它） |
| `storage-plugin-smb` / `sftp` / `ftp` / `webdav` | 挂载式或协议式远程存储 |
| `storage-plugin-memory` | 测试/临时内存存储 |

插件通过 `@StoragePlugin` 注解注册，显示名也来自注解并在启动时回写配置，改显示名不需要数据迁移。

## 平台收敛约定

- 托管对象存储只保留通用 OSS（S3 协议）实现；AliyunOSS、MinIO 等厂商专用插件已删除。
- 下线插件时必须同时清理该平台的配置与索引，避免残留平台在列表中可选但不可用。
- 「本地挂载」是挂载点哨兵，不能被当成普通平台重命名或合并。

## 远程存储休眠约束（强制）

SMB/NAS 等远程存储必须允许休眠：

- 定时扫描、任务清理等后台流程**只能遍历已缓存实例**（`StoragePluginManager#getCachedInstances` / `StorageServiceFacade#getResidentStorageServices`），不能按 configId 取实例，否则会把休眠中的 NAS 唤醒。
- 需要判断某个平台能否处理任务时，先按平台能力位预筛，再决定是否建连。
- 新增后台任务时复查：是否会在没有任何用户请求的情况下实例化远程插件。

## 对外协议与存储绑定

- 五个对外协议（WebDAV / SFTP / FTP / AList / OSS）各绑定一个「服务级存储」，默认可写。
- 协议端鉴权统一走 `DavUserAuthenticator` + `LoginGuardService`；IP 名单判定在密码校验之前，名单外地址不查库、不做 BCrypt、不消耗试错计数。
- 只读开关只作用于对外文件服务层，存储接入本身不做只读。
- 删除挂载即永久删除远端内容；虚拟 ID 移动必须携带改名，否则会丢文件。

## 分片临时目录

- 分片先落本地时，目录名就是 `uploadId`，位于 `storage/temp`。
- 删除必须用 `TempDirCleaner.deleteWithRetry` 并回查目录消失：Windows 上文件句柄未释放时 Hutool `FileUtil.del` 会返回 true 但目录仍在。
- 合并/中止失败的分片目录由 `TransferTaskCleanupScheduler` 兜底扫描：无活动任务且最后修改超过 1 小时才删除。
- 任务行删除前先按 `uploadId` 清理本地目录；uploadId 一旦随行删除就再也找不到目录。

## 已知风险（待修）

- 分片上传的 `objectKey` 可由客户端指定，缺少目录边界校验，可越出用户目录写入远端存储的其他位置。改动上传链路时优先收口该问题。

## 测试

`fs-admin/src/test/java` 下有 `LocalStorageTempCleanupTest`、`ProtocolAuthLockoutTest`、`StreamCipherSupportTest` 等；涉及插件行为变更时至少跑：

```bash
JAVA_HOME=/d/devTools/jdk/jdk21.0.12.1 /d/devTools/maven/bin/mvn.cmd \
  -pl fs-admin -am -DskipTests=false -DfailIfNoTests=false test
```
