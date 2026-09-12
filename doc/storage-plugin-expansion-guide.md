# GFS 存储插件扩展实施指南

> 目标读者：负责实施编码的 agent。本文档自包含，实施前只需通读本文 + 按锚点核对代码现状。
>
> 代码基线：2026-09-09，main 分支 commit `acab558`（所有 `文件:行号` 锚点若有漂移，以**符号搜索**为准）。
>
> 执行约束：阶段顺序 P0→P5，每阶段结束必须可独立编译验证。**不要做「明确不做」清单之外的事。**
>
> 最后更新：2026-09-09 —— P0–P5 已随 commit `20b2f77` 落地，本文转为实施记录与维护参考。

---

## 0. 项目约定（必须遵守）

- 语言：代码注释用中文；与用户交流用中文
- 依赖注入：Lombok `@RequiredArgsConstructor` + `private final` 字段，不用 @Autowired
- ORM：MyBatis-Flex（QueryWrapper / UpdateChain）
- 双 SQL 基线：`sql/mysql/gfs.sql` 与 `sql/postgresql/gfs_pg.sql` 必须同步修改；项目无迁移脚本体系，运行库由用户手工执行 ALTER（本文档 8.6 给出语句）
- 后端 i18n：`fs-admin/src/main/resources/i18n/messages.properties`、`messages_zh_CN.properties`、`messages_en_US.properties` 三份必须同步加 key
- 权限注解：`@SaCheckPermission("storage:manage")`（参考 `fs-modules\fs-storage\...\controller\StorageController.java`）
- 已知坑：`ErrorMessageUtils.extractUserFriendlyMessage`（fs-common-core）会把中文消息中第一个冒号后的内容截掉——给用户的错误文案里避免依赖冒号后的技术细节

## 1. 需求清单

| # | 需求 | 说明 |
| --- | --- | --- |
| R1 | 移除 Kodo、Obs 插件 | 模块删除 + DB 孤儿平台行清理 |
| R2 | 新增 SMB / WebDAV / SFTP / FTP(FTPS) 四个插件 | 纯对象式（同 Local/Minio 模式），分片先落本地 temp，complete 时合并写远程 |
| R3 | 新增「本地目录挂载」插件（LocalMount，读写） | 真实文件系统目录挂进网盘：DB 目录树镜像真实结构，GFS 内写操作穿透真实 FS，外部改动靠扫描同步回来 |
| R4 | 保存存储设置时连接测试 | validateConfig + initialize（真实连接）失败即拒绝保存 |
| R5 | 前端小改动 | 密码字段掩码输入；LocalMount 设置卡片「重新扫描」按钮 |

**明确不做**：网盘类（OAuth）存储；挂载式秒传/去重；前端 dataType 类型化渲染（保持全 Input）；现有 Local 插件重构。

## 2. 架构现状速查（实施前必读）

### 2.1 存储插件体系

- **SPI 接口** `fs-framework\fs-storage-plugin\storage-plugin-core\src\main\java\com\guanghe\fs\storage\plugin\core\IStorageOperationService.java`：方法全集 = createConfiguredInstance:27 / uploadFile:36 / downloadFile:44 / downloadFileRange:54 / deleteFile:62 / rename:70 / getFileUrl:79 / getFileStream:87 / isFileExist:95 / initiateMultipartUpload:104 / uploadPart:116 / listParts:126 / completeMultipartUpload:136（partETags=`List<Map{partNumber,eTag}>`）/ abortMultipartUpload:145 / getAvailableSpace:155(default null) / close:163。**无 list/stat/mkdir 浏览能力（纯对象读写）**。
- **抽象基类** `...\core\AbstractStorageOperationService.java`：无参构造=SPI 原型；(StorageConfig) 构造器内先 `validateConfig` 再 `initialize`（L31-42）；反射工厂走 StorageConfig 构造器（L60-72）；MultipartFile 上传默认实现 L105-130。
- **注册**：`@StoragePlugin` 注解（identifier/name/description/icon/link/configSchema/schemaResource/isDefault，定义在 `core/annotation/StoragePlugin.java`）+ 每插件 `src/main/resources/META-INF/services/com.guanghe.fs.storage.plugin.core.IStorageOperationService`（内容一行实现类全名）+ `StoragePluginRegistry`（storage-plugin-boot）@PostConstruct ServiceLoader 加载并校验 identifier 去重（L48-97）。
- **注解取值风格**（参照 `storage-plugin-minio\...\minio\config\MinioStorageServiceImpl.java:15-22`）：identifier 英文（如 "Minio"），name 中文（如 "Minio对象存储"），icon 随意（前端目前不渲染），schemaResource=`classpath:schema/xxx-schema.json`。
- **schema 资源格式**：自定义 JSON **数组**（非标准 JSON Schema），元素 `{label, dataType, identifier, validation:{required}}`。示例见 `storage-plugin-aliyunoss\src\main\resources\schema\aliyun-oss-schema.json`。加载逻辑 `core\dto\StoragePluginMetadata.java:81-121`。
- **实例缓存**：`StorageInstanceCache` cacheKey=`configId:platformIdentifier`（L23），Striped 锁 getOrCreate，invalidate 时 `close()`；`StoragePluginManager` 统一封装；`LocalStorageManager` 管内置 Local 单例（configId=null）。
- **门面**：`fs-modules\fs-storage\...\facade\StorageServiceFacade.java` `getStorageService(configId)` L60-71——`StorageUtils.isLocalConfig`（null 或字面 "Local"）→ 内置单例；否则查库 getOrCreateInstance。
- **单启用约束（重要）**：`StorageSettingServiceImpl.enableOrDisableStoragePlatform` L142-150——**全系统同时只允许一个 enabled=1 的存储配置**，启用一个会先把其它全部置禁用。挂载扫描器依赖此约束（至多 1 个启用挂载设置），但代码仍按"遍历所有 enabled 挂载设置"编写，不硬编码单例。
- **配置掩码**：`StorageSettingServiceImpl` SECRET_MASK="********" L55；`isSensitiveKey` L311-317——key 小写后包含 `password`/`secret`/`token`，或同时含 `access` 和 `key`，即视为敏感字段（展示掩码、编辑时用旧值回填）。新插件密码字段命名含 password 即自动掩码。
- **平台行同步**：`StoragePlatformAutoRegister`（fs-storage，ApplicationRunner）启动时把插件元数据同步进 `storage_platform` 表——跳过 Local（L64-68）、无则 insert、有则 update，**无 delete**（孤儿残留，P0 修复）。
- **`listByPlatformIdentifier`**：`StorageSettingService.java:68`，实现 `StorageSettingServiceImpl.java:274`，现成可复用。

### 2.2 文件模块（fs-modules\fs-file）

- **目录树模型**：`file_info` 表（实体 `domain\FileInfo.java`）= parent_id 邻接表（根为 NULL），**无 path 字段**；is_dir；object_key 仅文件有、**与显示名解耦**（格式 `userId/yyyyMMdd/uuid.后缀`，`fs-common-core\...\utils\FileUtils.java:120-132`）；`storage_platform_setting_id` NULL=内置本地。**object_key 列宽 varchar(128)**（gfs.sql:105），file_transfer_task.object_key varchar(255)（gfs.sql:239）。同名靠应用层 `generateUniqueName` 加 (1)(2)（`FileInfoServiceImpl.java:641-673`），无 DB 唯一约束。
- **上传链路**（`FileTransferTaskServiceImpl.java`）：initUpload:256-302（生成 taskId + objectKey:263 + 预占唯一显示名:264，任务落 file_transfer_task 含 parentId）→ checkUpload:305-367（秒传：Redis 锁内按 md5+size+platform 查 `FileObjectReferenceService.java:41-69` 复用；**空文件特殊分支**:337-340,372-389 直接 uploadFile 空流）→ uploadChunk:477-602 → checkAndAutoMerge:498-553 → doMergeChunks:775-921（completeMultipartUpload:840 + **合并后二次去重**:851-868——md5 相同复用旧对象并 deleteFile 新对象 + buildUploadedFileInfo:923-941 建 file_info）。文件夹记录不在上传链路创建：前端逐级 POST → `FileController.java:125` → `FileInfoServiceImpl.createDirectory:213-246`（objectKey=NULL,is_dir=true）。**文本新建/编辑也是写路径**：`createTextFile`/`updateTextContent` 走 generateObjectKey + findReusableFile 去重。
- **存储选择**：请求头 `X-Storage-Platform-Config-ID` → `fs-admin\...\interceptor\StoragePlatformInterceptor.java:30-49`（Local 头值规范化 null）→ ThreadLocal（WebMvcConfig.java:86-93 注册）。
- **改名/移动**：`renameFile:412-432` / `moveFile:436-488` **只改 DB**（display_name / parent_id），无插件调用。
- **删除**：入回收站 `moveFilesToRecycleBin:135-175`（递归子级，批量 is_deleted=1，无插件调用）；恢复 `FileRecycleServiceImpl.restoreFiles:105-130`；物理删除 `doPermanentDelete:184-226`——removeByIds 后**事务提交后**逐对象引用计数（`FileObjectReferenceService.java:100-121` Redis 双锁）无引用才调插件 `deleteFile(objectKey)`:87-95。
- **下载/预览**：`FileStreamController.preview:52-77` 按 file.storagePlatformSettingId 取插件；Range→handleRangeRequest:98-137 用 `downloadFileRange(objectKey,start,end)` 返 206；普通下载 `downloadFile:97-115`。

### 2.3 前端（fs-ui）

- 新建弹窗 `src\pages\storage\components\AddStorageModal.tsx`：下拉选项来自 GET /apis/storage/platforms（L200-208）；选中平台后 JSON.parse(configScheme) 渲染（L76-99）；**所有字段按 string Input 渲染**（L222-250，dataType 读了没用）；必填校验 validation.required（L125-131）；提交 `{platformIdentifier, configData: JSON.stringify(formData)}`（L155-159）。
- 设置卡片 `src\pages\storage\components\StorageSettingCard.tsx`：编辑弹窗 L403-433 同样全 Input；查看弹窗 L334-363 有 maskValue 掩码（保留首尾 4 位）。
- 类型定义 `src\types\storage.ts`（ConfigScheme L63-70）；api 封装 `src\api\storage.ts`。
- i18n：`src\locales\{zh,en}\storage.json`。

### 2.4 模块依赖方向

`fs-admin → fs-modules/fs-file → fs-modules/fs-storage → fs-framework/fs-storage-plugin/{boot,core,各插件}`。版本集中在 `fs-dependencies\pom.xml`（BOM）；插件聚合在 `fs-framework\fs-storage-plugin\pom.xml`；`storage-plugin-boot` 的 pom 依赖 local/aliyunoss/rustfs/obs（minio/kodo 未被 boot 依赖，属孤儿模块）。

## 3. 阶段总览

| 阶段 | 内容 | 规模 |
| --- | --- | --- |
| P0 | 移除 Kodo/Obs + 孤儿平台清理 | 小 |
| P1 | SPI 扩展（4 个 default 方法 + 1 个 DTO）+ 分片 temp 公共基类 + 4 个依赖坐标 | 小 |
| P2 | 四个远程协议插件（SMB/WebDAV/SFTP/FTP） | 大 |
| P3 | 保存时连接测试 | 小 |
| P4 | LocalMount 插件 + fs-file 挂载集成（同步器/写穿透/锁/SQL 扩列） | 最大 |
| P5 | 前端（密码掩码、重新扫描按钮、i18n） | 小 |

## 4. P0 移除 Kodo/Obs + 孤儿平台清理

1. **删目录**：`fs-framework\fs-storage-plugin\storage-plugin-kodo\`、`storage-plugin-obs\` 整个删除。
2. **fs-framework\fs-storage-plugin\pom.xml**：`<modules>` 删除 `storage-plugin-kodo`、`storage-plugin-obs` 两行。
3. **storage-plugin-boot\pom.xml**：删除 obs 依赖（L35-39 附近）。
4. **fs-dependencies\pom.xml**：删除属性 `qiniu-java.version`、`huaweicloud.version`；dependencyManagement 删除 `storage-plugin-obs`、`com.qiniu:qiniu-java-sdk`、`com.huaweicloud:esdk-obs-java-bundle` 三个条目。
5. **SQL 种子**：`sql\mysql\gfs.sql`（约 328 行 INSERT）、`sql\postgresql\gfs_pg.sql`（约 291-294 行）删除 identifier=Obs 的行（Kodo 无种子行）。
6. **孤儿清理**（代码方案，弃手工 SQL——存量部署也能自愈）：`StoragePlatformAutoRegister.syncPluginsToDatabase()` 末尾追加：

```java
// 平台行的真相来源是代码插件清单：插件已删除的残留行（及其全部配置）一并清理
Set<String> activeIdentifiers = allMetadata.stream()
        .map(StoragePluginMetadata::getIdentifier).collect(Collectors.toSet());
for (StoragePlatform row : storagePlatformMapper.selectListByQuery(QueryWrapper.create())) {
    if (StorageUtils.LOCAL_PLATFORM_IDENTIFIER.equals(row.getIdentifier())
            || activeIdentifiers.contains(row.getIdentifier())) {
        continue;
    }
    // 先删引用该平台的配置（避免 getStorageSettingsByUser 组装 VO 时 NPE），再删平台行
    List<StorageSetting> settings = storageSettingService.listByPlatformIdentifier(row.getIdentifier());
    for (StorageSetting setting : settings) {
        storageServiceFacade.removeInstance(setting.getId());
        storageSettingService.removeById(setting.getId());
    }
    storagePlatformMapper.deleteById(row.getId());
    log.warn("清理孤儿存储平台: {}", row.getIdentifier());
}
```

（StoragePlatformAutoRegister 与 StorageSettingService 同在 fs-storage 模块，直接注入即可。）

## 5. P1 SPI 扩展 + 分片公共基类

### 5.1 IStorageOperationService 追加 4 个 default 方法

```java
/** 是否为挂载式存储（目录树镜像真实文件系统）。默认 false；业务代码一律用能力位判断，勿比较 identifier 字符串 */
default boolean isMountMode() { return false; }

/** 创建目录（含逐级父链）。仅挂载式实现 */
default void mkdirDirectory(String dirKey) {
    throw new StorageOperationException("当前存储平台不支持创建目录");
}

/** 列举一层目录内容（同步器用）。key 规范：posix '/' 分隔、无前导 '/'、'' 表示根。仅挂载式实现 */
default List<StorageObjectEntry> listObjects(String dirKey) {
    throw new StorageOperationException("当前存储平台不支持目录列举");
}

/** 删除目录（递归，含内部文件）。仅挂载式实现，供永久删除清理真实目录 */
default void deleteDirectory(String dirKey) {
    throw new StorageOperationException("当前存储平台不支持删除目录");
}
```

### 5.2 新建 DTO `core\model\StorageObjectEntry.java`

字段：`String key`（相对 posix 路径）、`boolean isDir`、`Long size`（目录为 null）、`Long lastModified`（毫秒，取不到为 null）。

### 5.3 新建 `core\chunk\AbstractTempChunkStorageService.java`

照 `LocalStorageOperationService` 的本地 temp 分片模式提炼（**Local 插件本身一行不动**）：

- 子类实现 `protected abstract String getTempRoot();`（建议取插件配置 tempPath，默认 `${java.io.tmpdir}/gfs-storage-temp/<identifier小写>`）
- 基类实现：initiateMultipartUpload（`Files.createDirectories` 建 temp 任务目录，返回 taskId=目录名）/ uploadPart（写 `part-<partNumber>` 文件，etag 返回 size_mtime）/ listParts（读目录）/ abortMultipartUpload（递归删 temp 目录）
- completeMultipartUpload：按 partNumber 排序合并到临时文件 → 调抽象方法 `protected abstract void writeMerged(Path mergedFile, String objectKey);`（子类写远程）→ 删 temp。入参 partETags 忽略（本地分片无真实 etag）
- 一律用 `Files.createDirectories`（幂等），禁止 exists()+mkdirs()（并发竞态，Local 插件已修过此 bug）
- 空实现 close()；getAvailableSpace() 默认 null

### 5.4 fs-dependencies\pom.xml 追加依赖坐标

```xml
<dependency><groupId>com.hierynomus</groupId><artifactId>smbj</artifactId><version>0.14.0</version></dependency>
<dependency><groupId>com.hierynomus</groupId><artifactId>sshj</artifactId><version>0.38.0</version></dependency>
<dependency><groupId>com.github.lookfirst</groupId><artifactId>sardine</artifactId><version>5.12</version></dependency>
<dependency><groupId>commons-net</groupId><artifactId>commons-net</artifactId><version>3.11.0</version></dependency>
```

（Maven central 走阿里云镜像，均可拉取；若个别版本缺失就降/升到镜像可用的最近版本，并在交付说明中记录实际版本。）

**P1 验证**：全量 `mvn clean package -DskipTests` 通过，8 个旧插件零改动。

## 6. P2 四个远程协议插件

### 6.1 通用骨架（每个插件相同）

模块结构（照 local 插件布局，实现类放根包、配置类放 config 子包）：

```
storage-plugin-smb\
  pom.xml                    （父=fs-storage-plugin，仅依赖 storage-plugin-core + 协议库）
  src\main\java\com\guanghe\fs\storage\plugin\smb\SmbStorageOperationService.java
  src\main\java\com\guanghe\fs\storage\plugin\smb\config\SmbConfig.java
  src\main\resources\schema\smb-schema.json
  src\main\resources\META-INF\services\com.guanghe.fs.storage.plugin.core.IStorageOperationService
```

- `pom.xml` 注册进聚合 pom `<modules>`；`storage-plugin-boot\pom.xml` 加 4 个依赖（与现有插件并列）。
- 配置类：纯 `@Data` POJO + 手写 `toObject(StorageConfig)` 静态工厂（照 `S3CompatibleConfig` 模式）；校验全在手写 `validateConfig()`（逐字段非空，参照 `AliyunOssStorageServiceImpl.java:52-79`），不用 Bean Validation 注解。
- `initialize()` 必须**建立真实连接/登录**（这是 P3 连接测试的基础）；`close()` 释放客户端。
- 密码字段 identifier 命名必须含 `password`（自动掩码）。
- object_key 规范：posix `/`、无前导 `/`（由 `FileUtils.generateObjectKey` 的 uuid 日期路径天然满足，插件不做路径加工）。
- `getFileUrl` 一律抛 `StorageOperationException`（无公网直链能力；下载走 FileStreamController 流路径，不依赖 getFileUrl——已核实）。
- 分片继承 `AbstractTempChunkStorageService`；再自行覆写 uploadFile/downloadFile/downloadFileRange/deleteFile/rename/getFileUrl/getFileStream/isFileExist。
- 客户端线程安全约定：**FTP/FTPClient、SMB/SMBClient、SFTP/SSHClient 逐操作建连/会话，用完即断**（实例会被缓存跨线程共享）；WebDAV/Sardine 线程安全可复用单实例。
- Windows/Unix 路径：一律 posix key，插件内部用协议客户端 API 拼路径，不做字符串替换。

### 6.2 SMB（smbj 0.14.0）

- 配置：`smbHost`、`smbPort`(默认445)、`smbDomain`(可选)、`smbShare`、`smbUsername`、`smbPassword`、`tempPath`(可选)
- 生命周期：initialize 建 `SMBClient`；每次操作 connection→session.authenticate(Domain/username,password)→`DiskShare`，用完 disconnect；close 关 client
- API 映射：uploadFile=`openFile(WRITE)` 流写；downloadFileRange=`openFile(READ)`+`read(offset)`；rename=`renameTo`（同 share 内，目录亦可）；mkdirDirectory=逐级 `mkdir`；listObjects=`share.list(folder)` 过滤 "."/".."；deleteDirectory=`share.rmdir(key,true)`；deleteFile=`rm`；isFileExist=`fileExists`；getAvailableSpace=`share.getDiskFreeSpace`

### 6.3 WebDAV（sardine 5.12）

- 配置：`webdavEndpoint`（完整 URL 前缀）、`webdavUsername`、`webdavPassword`、`webdavBasePath`(可选，拼在 endpoint 后)、`tempPath`
- 生命周期：initialize 建 `SardineImpl(user,pass)`（可复用）；close=shutdown
- API 映射：uploadFile=`put`；downloadFileRange=`get(url, headers{Range})`（流用 `Sardine.begin()` 包裹防泄漏）；deleteFile=`delete`；rename=`move`（同址改名）；mkdirDirectory=逐级 `mkcol`；listObjects=`list(url,1)`→`DavResource`（第一层，跳过自身）；isFileExist=`exists`
- 风险：sardine 传递 httpclient4 与 Boot 的 httpclient5 包不同名共存，无冲突

### 6.4 SFTP（sshj 0.38.0）

- 配置：`sftpHost`、`sftpPort`(默认22)、`sftpUsername`、`sftpPassword`(与 privateKey 二选一)、`sftpPrivateKeyPath`(可选)、`sftpKnownHostsPath`(可选)、`tempPath`
- 生命周期：initialize 建 `SSHClient`（knownHosts 缺省 `PromiscuousVerifier` + warn 日志）+ 认证；逐操作 `new SFTPClient()`（或 startSession）用完关；close 断 client
- API 映射：uploadFile=`FileHandle(WRITE)` 流写；downloadFileRange=`openRemoteFile`+`read(offset,len)`；rename=`SFTPClient.rename`（目录亦可）；mkdirDirectory=逐级 `mkdir`；listObjects=`ls` 过滤 "."/".."，**symlink 跳过**；deleteFile=`rm`；deleteDirectory=DFS 递归删；getAvailableSpace=`statvfs`

### 6.5 FTP/FTPS（commons-net 3.11.0）

- 配置：`ftpHost`、`ftpPort`(默认21)、`ftpUsername`、`ftpPassword`、`ftpsEnabled`(布尔，schema 里仍是 string，值 "true"/"false")、`passiveMode`(默认true)、`controlEncoding`(默认UTF-8)、`tempPath`
- 生命周期：initialize 按 ftpsEnabled 建 `FTPClient`/`FTPSClient("TLS")`（passiveMode→`enterLocalPassiveMode`、`setFileType(BINARY)`、FTPS 加 `execPROT("P")`），**逐操作 connect+login，用完 logout+disconnect**；close 空实现
- API 映射：uploadFile=`storeFile`；downloadFileRange=`setRestartOffset(start)`+`retrieveFileStream` 读满 length 后 `completePendingCommand`；deleteFile=`deleteFile`；rename=`rename`（全路径，目录亦可）；mkdirDirectory=逐级 `makeDirectory`；listObjects=优先 `mlistDir`（MLSD，带 mtime），异常时退 `listFiles`（mtime=null，接受）；isFileExist=`mlst`/`listNames`
- 注意：FTP 无原生"目录存在"判断，isFileExist 对目录用 listNames 命中判断

### 6.6 schema 文件完整示例（SMB，其余照此结构）

```json
[
  {"label":"服务器地址","dataType":"string","identifier":"smbHost","validation":{"required":true}},
  {"label":"端口","dataType":"string","identifier":"smbPort","validation":{"required":false}},
  {"label":"域","dataType":"string","identifier":"smbDomain","validation":{"required":false}},
  {"label":"共享名","dataType":"string","identifier":"smbShare","validation":{"required":true}},
  {"label":"用户名","dataType":"string","identifier":"smbUsername","validation":{"required":true}},
  {"label":"密码","dataType":"string","identifier":"smbPassword","validation":{"required":true}},
  {"label":"分片临时目录","dataType":"string","identifier":"tempPath","validation":{"required":false}}
]
```

注解示例：`@StoragePlugin(identifier="Smb", name="SMB网络存储", schemaResource="classpath:schema/smb-schema.json", icon="icon-bendicunchu1", description="...")`。

**P2 验证**：全量编译；启动日志注册 4 个新插件；建配置后（见 10.2 环境速查），配置错误时 initialize 抛 StorageConfigException 且实例不缓存。

## 7. P3 保存时连接测试

落点：`StorageSettingServiceImpl.addStorageSetting / editStorageSetting`，在重复性校验之后、`save/updateById` 之前：

```java
private void testStorageConnection(String platformIdentifier, String configData) {
    StorageConfig cfg = StorageConfig.builder()
            .configId(null)                 // 测试实例不入缓存
            .platformIdentifier(platformIdentifier)
            .properties(parseConfigData(configData))
            .build();
    IStorageOperationService instance = null;
    try {
        instance = storagePluginRegistry.getPrototype(platformIdentifier)
                .createConfiguredInstance(cfg);   // validateConfig + initialize 真实连接
    } catch (Exception e) {
        throw new BusinessException(I18nUtils.getMessage("storage.config.test.failed",
                new Object[]{ErrorMessageUtils.extractUserFriendlyMessage(e)}));
    } finally {
        if (instance != null) { try { instance.close(); } catch (Exception ignore) { } }
    }
}
```

- **edit 场景**：先用现有 `mergeSensitiveConfig` 得到 mergedConfigData 再测试（掩码占位符不会被当真实密码）；测试通过后才 `updateById` + `refreshInstance`，抛异常即整单回滚（现有 `rollbackFor=Exception.class` 兜底）。
- **Local 平台**：isLocalConfig(platformIdentifier) 时跳过测试（内置单例有自己的目录保障）。
- i18n（三份）：`storage.config.test.failed=存储连接测试失败：{0}` / `Storage connection test failed: {0}`
- 启用/禁用不做测试（保持现状）。

## 8. P4 本地目录挂载（读写）——最大阶段

### 8.1 数据模型与不变量

- 每个挂载设置在**启用它的用户根目录**下有一条**挂载点记录**：`is_dir=1, storage_platform_setting_id=设置id, parent_id=NULL, object_key=NULL, display_name=配置项 mountName`。由 `MountPointService.ensureMountPoint(userId, setting)` 幂等懒创建（`FileHomeServiceImpl` 根列表入口 + 扫描器兜底），按 `(user_id, storage_platform_setting_id, parent_id IS NULL, is_dir=1)` 判存。
- 挂载点子树内**文件记录** `object_key` = 相对挂载根的真实相对路径（强制 posix `/`、无前导 `/`、无 `..`）；`content_md5=NULL`（扫描不做 hash）；suffix/mimeType 复用 `FileUtils.extName` 与现有 mime 推断；`update_time` 存扫描到的 mtime。
- 目录记录 object_key 保持 NULL（与现有约定一致），目录与真实 FS 的对应靠"父链 display_name"还原。
- 全系统单启用约束下，同时至多 1 个启用的挂载设置，但代码按集合遍历编写。

### 8.2 新插件 storage-plugin-localmount

结构同 6.1；注解 `identifier="LocalMount"`（**不能叫 Local**——与内置插件 SPI identifier 去重冲突）、`name="本地目录挂载"`。

- 配置：`mountName`（挂载点显示名，默认"本地挂载"）、`rootPath`（真实绝对路径，必填）、`followSymlinks`(默认false)、`rescanIntervalSeconds`(可选，覆盖全局扫描间隔)
- 实现（继承 AbstractStorageOperationService，**不继承 temp 基类**——直接写真实路径）：
  - `resolveFullPath(key)`：`rootPath.resolve(key.split("/"))`，**必须 normalize 后校验仍以 rootPath 开头**（防路径逃逸），Windows 反斜杠输入一律拒绝
  - uploadFile / downloadFile / downloadFileRange / getFileStream：`Files.newOutputStream/newInputStream`（Range 用 `FileChannel.position(start)` 读指定长度）；deleteFile=`Files.deleteIfExists`（幂等）
  - rename=真实 `Files.move`（文件与目录通吃，不开 REPLACE_EXISTING——同名目标应报错）；mkdirDirectory=逐级 createDirectories；listObjects=`Files.newDirectoryStream` 一层（**isSymbolicLink 且未开 followSymlinks 则跳过**，防循环）；deleteDirectory=`Files.walk` 逆序删；getAvailableSpace=`Files.getFileStore(root).getTotalSpace/UsableSpace`
  - isFileExist=`Files.exists`
  - `isMountMode()=true`

### 8.3 fs-file 挂载集成（新包 `com.guanghe.fs.file.mount`）

新建 5 个类：

| 类 | 职责 |
| --- | --- |
| `MountPathResolver` | `resolveRelativeKey(FileInfo file, long settingId)`：沿 parent_id 向上收集 display_name 直到挂载点记录（判定：is_dir=1 && parent_id IS NULL && storage_platform_setting_id=settingId），逆序拼 `a/b/c.ext`；名字含 `/` 或 `\` → 抛 `mount.invalid.name`；未遇挂载点到根 → `mount.not.in.scope`；长度>500 → `mount.key.too.long` |
| `MountPointService` | ensureMountPoint（懒建挂载点记录）；unmount(settingId)（删索引见 8.4-⑧） |
| `MountLocks` | `ConcurrentHashMap<String, ReentrantLock>`，key=settingId；`acquire/callWithLock(settingId, supplier)`。**所有写穿透与扫描都持此锁** |
| `MountScanService` | 同步器（算法见 8.5）+ @Scheduled + 启动触发 |
| `MountScanController` | `POST /apis/file/mount/scan/{settingId}` + `@SaCheckPermission("storage:manage")`（放 fs-file 模块——fs-storage 不能反向依赖 fs-file） |

修改的现有文件：

| 文件 | 挂载分支 |
| --- | --- |
| `FileTransferTaskServiceImpl.initUpload:256-302` | 拿到 `getStorageService(configId).isMountMode()` 时：objectKey 不走 `FileUtils.generateObjectKey`，改 `MountPathResolver.resolveRelativeKey(parentDirRecord)` + `"/" + 预占显示名`；同时校验父记录 storagePlatformSettingId==configId（不一致抛 `mount.platform.mismatch`）；普通平台行为不变 |
| `FileTransferTaskServiceImpl.checkUpload:305-367` | 挂载式**跳过秒传查询**（复用旧对象会破坏路径映射）；空文件分支改 `uploadFile(空流, task.objectKey)` 直写真实路径 |
| `FileTransferTaskServiceImpl.doMergeChunks:775-921` | 挂载式**跳过合并后二次去重**（:851-868）；completeMultipartUpload 由插件直接写真实路径；`file_info` 插入与真实写入都包在 `MountLocks` 内 |
| `FileInfoServiceImpl.createDirectory:213-246` | 挂载式：先真实 `mkdirDirectory`（成功后再插 DB，失败不落库） |
| `FileInfoServiceImpl.renameFile:412-432` | 挂载式：算旧/新相对路径 → 真实 `rename` → 改 display_name + 本记录 object_key；**目录改名**还要子树 objectKey 前缀批量替换（`like 'old/%'` 转义后 UpdateChain） |
| `FileInfoServiceImpl.moveFile:436-488` | 挂载式：仅允许**同一挂载设置内**移动（跨设置/挂载点本身移动拒绝 `mount.move.unsupported`）；真实 rename + 子树前缀替换 + parent_id 更新 |
| `FileInfoServiceImpl.createTextFile / updateTextContent` | 挂载式：跳过 findReusableFile 去重，直接 `uploadFile(bytes, 真实key)` |
| `FileInfoServiceImpl.moveFilesToRecycleBin:135-175` | 挂载点记录本身**禁止删除**（`mount.point.protected`）；子树内容允许正常入回收站 |
| `FileRecycleServiceImpl.doPermanentDelete:184-226` | 挂载式：afterCommit 除 `deleteFile` 外，目录记录调 `deleteDirectory` 清真实目录；真实文件已不存在时幂等通过 |
| `FileHomeServiceImpl` | 根目录入口 `ensureMountPoint` |

### 8.4 边界情况处理表（实施时逐条对照）

| # | 场景 | 策略 |
| --- | --- | --- |
| ① | GFS 上传中（分片在插件 temp） | 分片全在 temp（挂载根之外），真实树无痕迹；合并与扫描互斥（MountLocks） |
| ② | 合并写真实文件与扫描并发 | 合并的 completeMultipartUpload + file_info 落库都在 MountLocks 内，与扫描串行化 |
| ③ | 外部删除文件 | DB 记录**硬删**（不入回收站——内容已没了，进回收站给用户"可恢复"错觉）；本就 is_deleted=1 的不动 |
| ④ | 外部删除目录 | 子树整体硬删；子树内回收站项成断头记录留在回收站（与普通平台永久删父目录行为一致），其永久删除 deleteFile 幂等成功 |
| ⑤ | 回收站恢复时真实文件已被外部删 | 允许恢复（恢复只改 is_deleted）；打开/下载时 isFileExist 报不存在，与现有行为一致，v1 不做缺失标记 |
| ⑥ | 外部新建路径与回收站中同路径软删记录冲突 | 软删占用集（该 setting 下 is_deleted=1 记录 path 集）命中的路径**跳过不导入**，旧记录永久删除后下轮再导入 |
| ⑦ | 挂载设置禁用 | 不动 DB 子树、不动真实文件；切走后子树因 settingId 过滤自然不可见；重新启用后首轮全量扫描校准 |
| ⑧ | 挂载设置删除 | **卸载=只清索引，绝不碰真实文件**：MountLocks 内硬删该 setting 全部 file_info（含挂载点与回收站记录；md5=NULL 不参与秒传引用，无 file_object_reference 行——实施时断言）+ 删 file_transfer_task 行 + removeInstance + 删 setting 行，同事务 |
| ⑨ | 路径分隔符 Windows/Unix | 插件统一输出 posix key；resolveFullPath 用 split("/") 还原；import 前校验无 `..`/前导 `/`/`\` |
| ⑩ | 文件名非法字符 | 真实 FS 是命名权威照收录；仅强制：含 `/` 或 `\` 的名字跳过导入并告警 |
| ⑪ | 软链接/循环 | LocalMount 默认跳过 symlink（目录与文件都跳）；深度>32 兜底；SFTP symlink 同样跳过 |
| ⑫ | 外部把目录改成同名文件（或反之） | 按 (路径+isDir) 匹配，类型不符视为"旧删+新增"两条处理 |
| ⑬ | mtime 缺失（FTP 等无 MLSD） | mtime=null 时比较退化为仅 size；风险登记（同 size 改动漏更新，罕见） |
| ⑭ | 用户在 GFS 内删挂载点目录 | 拒绝（见 moveFilesToRecycleBin 行） |
| ⑮ | 扫描期间设置被禁用/删除 | enable/disable/delete 处理挂载平台时也走 MountLocks，串行化 |
| ⑯ | 上传目标父目录与所选平台不一致 | 挂载分支强制校验父记录 storagePlatformSettingId==context configId；普通平台实施时确认现状（若无校验则只对挂载加校验，不动普通平台） |

### 8.5 同步器算法（MountScanService）

触发：`@Scheduled(fixedDelayString = "${fs.file.mount.scan-interval:300000}", initialDelay=60000)` + `ApplicationReadyEvent` 先跑一次（对齐 `FileTransferTaskServiceImpl.cleanupFolderDownloadTasksOnStartup` 模式）+ 手动接口。扫描对象：`storageSettingService.listByPlatformIdentifier("LocalMount")` 中 enabled=1 者（**不要**写死"至多一个"）。`scan-enabled=false` 时全部跳过。

```
scanSetting(setting):
  MountLocks.callWithLock(setting.id):
    instance = storageServiceFacade.getStorageService(setting.id)
    if (!instance.isMountMode()) return
    1. listObjects("") 失败 → log + 本轮放弃（不删任何 DB 数据）
    2. 载 DB 侧：该 setting 全部 is_deleted=0 记录（挂载点除外）按 parentId 建树
       → Map<recordId, relPath>
    3. 载软删占用集：该 setting is_deleted=1 记录 path 集
    4. DFS 真实树 visit(dirKey, dbDirRecord):
         entries = instance.listObjects(dirKey)
         for e in entries:
           rel = join(dirKey, e.key)
           if 命中软删占用集 → skip
           elif e.isDir:
             dbDir 有同名子目录 → (mtime 变则更新 update_time) 递归
             否则插入目录记录后递归
           else:
             同名文件记录存在 → size/mtime 不一致才更新（md5 保持 NULL）
             否则插入文件记录(object_key=rel)
           seenPaths += rel
         for db 子记录 not in seenPaths → 硬删（③④规则）
    5. 批量落库 insertBatch/updateBatch/removeByIds，500 一批
    6. 保险丝：单轮导入+删除 > fs.file.mount.scan.max-entries(默认50000) 中止本轮并告警
       DFS 异常：保留已收集增/改、放弃本轮删除段（宁多留勿误删）
```

### 8.6 SQL 变更与配置

1. 两份基线 SQL 原地改：`file_info.object_key` varchar(128)→**varchar(512)**（gfs.sql:105）、`file_transfer_task.object_key` varchar(255)→**varchar(512)**（gfs.sql:239）；postgresql 基线同理。
2. 运行库手工迁移（交付给用户执行）：

```sql
ALTER TABLE `file_info` MODIFY COLUMN `object_key` varchar(512) DEFAULT NULL COMMENT '资源名称';
ALTER TABLE `file_transfer_task` MODIFY COLUMN `object_key` varchar(512) NOT NULL COMMENT '对象key';
```

3. `fs-admin\src\main\resources\application*.yml` 增加：

```yaml
fs:
  file:
    mount:
      scan-enabled: true        # 总开关
      scan-interval: 300000     # ms，默认 5 分钟
      scan-max-entries: 50000   # 单轮保险丝
```

4. 后端 i18n 三份新增 key：`mount.invalid.name` / `mount.not.in.scope` / `mount.key.too.long` / `mount.platform.mismatch` / `mount.move.unsupported` / `mount.point.protected`（中英文都要）。

## 9. P5 前端（fs-ui）

| 文件 | 改动 |
| --- | --- |
| `src\pages\storage\utils.ts`（新建） | `isSensitiveField(id)`：小写后含 password/secret/token，或同时含 access 和 key——**与后端 isSensitiveKey 完全同规则** |
| `AddStorageModal.tsx` | 字段渲染处：敏感字段 `<Input type='password' autoComplete='new-password'>` |
| `StorageSettingCard.tsx` | ① 编辑弹窗同上；② 卡片操作区：`setting.storagePlatform?.identifier === 'LocalMount'` 时渲染「重新扫描」按钮（RefreshCw 图标 + loading 态），调新 api |
| `src\api\storage.ts` | `scanMountStorage(settingId)` → `request.post('/apis/file/mount/scan/' + settingId)` |
| `src\locales\zh\storage.json` / `en\storage.json` | `card.rescan`（重新扫描/Rescan）、`card.rescanOk`（扫描已触发，稍后刷新查看/Scan triggered）、`card.rescanFail`（触发失败/Failed to trigger scan） |

说明：编辑弹窗回填的是后端掩码值，password 类型下无视觉问题；提交掩码值时后端 mergeSensitiveConfig 自动回填旧值，链路自洽。

## 10. 验证与环境速查

### 10.1 每阶段验证

| 阶段 | 验证 |
| --- | --- |
| P0 | mvn 编译过；启动日志出现"清理孤儿存储平台: Obs/Kodo"；设置页添加下拉不再出现 OBS；`SELECT * FROM storage_platform` 无两行 |
| P1 | 全量编译过（8 个旧插件零改动）；启动正常，现有 Local 上传下载回归 |
| P2 | 启动日志注册 4 新插件；正确配置保存成功；**错误配置（错密码/端口/share）保存被 P3 拒绝**并返回 `storage.config.test.failed`，DB 无残留行 |
| P3 | 同上（P2/P3 合并验证）；重复配置校验仍生效 |
| P4 | ① LocalMount 指向 Windows 测试目录（如 `D:\mnt-test`）→ 文件页根出现挂载点 → 浏览真实子树；② GFS 内新建文件夹/上传/改名/移动/永久删除 → 真实目录逐项核对；③ 外部改文件 → 手动扫描后列表变化；④ `POST /apis/file/mount/scan/{id}` 返回 ok；⑤ 上传中触发扫描无重复记录；⑥ 删除挂载设置 → 真实文件仍在、索引清空 |
| P5 | 前端 build + robocopy 同步后：密码字段圆点显示；LocalMount 卡片出现重新扫描按钮；中英文完整 |
| 终态 | curl 冒烟（附录 A.5）+ **环境起好交用户 UI 验证，不跑浏览器测试（用户明确要求省积分）** |

### 附录 A. 环境速查（Windows，maven/jdk 不在 agent PATH 上，用全路径）

```bash
# A.1 构建前端（在 D:\workspace\lab\gfs\fs-ui 下；bash 每次 cwd 会重置，必须显式 cd）
pnpm run build
# A.2 同步产物进后端静态目录（exit 0 或 3 = 成功）
MSYS2_ARG_CONV_EXCL="*" MSYS_NO_PATHCONV=1 robocopy "D:\\workspace\\lab\\gfs\\fs-ui\\dist" "D:\\workspace\\lab\\gfs\\fs-admin\\src\\main\\resources\\static" /MIR /NFL /NDL /NJH
# A.3 停后端（先查 80 端口 PID，确认是 java 再杀）
netstat -ano | grep -E ':80 .*LISTENING'
powershell -Command "Stop-Process -Id <pid> -Force"
# A.4 重打包 + 启动（必须在项目根 D:\workspace\lab\gfs 下启动，否则 LibreOffice 解析失败起不来）
"D:/devTools/maven/bin/mvn" clean package -pl fs-admin -am -DskipTests -q
"D:/devTools/jdk/jdk21.0.12.1/bin/java" -jar fs-admin/target/fs-admin.jar > logs/backend-console.log 2>&1   # 后台运行，重定向必须有（dev 日志只写控制台）
# A.5 冒烟
curl -s -o /dev/null -w "%{http_code}" http://localhost/apis/          # 期望 200
curl -s http://localhost/ | grep -oE 'index-[A-Za-z0-9_-]+\.js'        # bundle 名应为最新
curl -s -X POST http://localhost/apis/auth/login -H "Content-Type: application/json" \
  -d '{"loginType":"password","account":"admin","password":"admin"}'   # 取 data.accessToken 后再测业务接口
```

依赖服务常驻：MySQL 127.0.0.1:3306（库 gfs）、Redis 127.0.0.1:6379。协议测试端点：SMB 用 Windows 自带共享（如 `\\localhost\C$`，host=localhost、share=C$）；WebDAV/SFTP/FTP 若无现成服务，配置错误场景验证"拒绝保存"即可，功能实测由用户提供端点后验证。

## 11. 风险清单

| 风险 | 等级 | 缓解 |
| --- | --- | --- |
| 扫描器误删 DB 记录（网络抖动/部分失败） | 高 | 8.5-6：异常放弃本轮删除段；删除前路径二次确认；保险丝上限 |
| object_key 列宽不足 | 高 | 8.6 扩列 512 + 解析器长度守卫 |
| 远程客户端线程安全（实例被缓存共享） | 中 | FTP/SMB/SFTP 逐操作连接；WebDAV 复用 |
| 挂载目录改名/移动后子树前缀替换与扫描竞态 | 中 | 全部写穿透走 MountLocks；like 转义 |
| 新依赖拉取失败（镜像缺版本） | 低 | 5.4 注：就近换版本并记录 |
| sardine 传递 httpclient4 共存 | 低 | 包名隔离无冲突；出问题再 exclude |
| FTP 无 mtime 漏更新 | 低 | 风险登记，文档说明 |
| 收集上传（FileCollectionUpload）走同一分片链路 | 中 | P4 冒烟补一条收集上传用例 |
| 回收站中挂载文件可恢复但真实文件已不在 | 低 | 与现有"对象被外部清理"行为一致，v1 接受 |

## 12. 完成定义（DoD）

1. 全量 `mvn clean package -DskipTests` 通过；`pnpm run build` 通过
2. 启动日志：Kodo/Obs 清理、5 个新插件注册、无异常栈
3. 10.1 各阶段验证项全过（协议插件功能实测可留待用户提供端点）
4. 三份 i18n properties、双 SQL 基线、运行库 ALTER 语句齐全
5. 所有改动**不提交**，构建重启环境后交用户验证（用户规则）
