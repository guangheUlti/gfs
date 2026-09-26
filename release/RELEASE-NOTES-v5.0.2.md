# GFS v5.0.2 发布说明

`gfs-5.0.2-windows-x64.zip`（自带 JDK 21、MySQL 8.4、Redis，免安装部署）

## 主要变更

### 对外文件服务新增 OSS（S3 兼容网关）

- **复用主服务 HTTP 端口**，路径前缀 `/oss`：整个网盘暴露为 S3 虚拟桶 `gfs`（path-style），rclone、AWS SDK、Cyberduck、WinSCP S3 等客户端可直接连接
- **自实现 AWS SigV4 验签**（不依赖 AWS SDK）：HMAC-SHA256 推导链、Canonical Request 重建、±15 分钟时钟偏移校验、UNSIGNED-PAYLOAD
- **每用户密钥对管理**（服务页 OSS 卡片）：签发 Access Key（20 位）/ Secret Key（40 位，明文仅创建时返回一次）、吊销立即失效；Secret Key 以 AES-256-GCM 加密存储（主密钥 `oss.aes-key`，部署时建议在 `conf/application.yml` 固化 32 位密钥，缺失时启动随机生成并告警，重启后已签发密钥失效需重签）
- **全操作支持**：ListBuckets / ListObjects（V1+V2，prefix 过滤）/ Get（Range 断点续传）/ Head / Put（父目录自动创建）/ Delete（进回收站，幂等 204）/ 分片上传三件套（Initiate / UploadPart / Complete / Abort）
- 删除进回收站与 Web 端语义一致；SigV4 端到端实测 17 项断言全部通过

### 存储空间弹窗改版

- 移除磁盘分区区域
- 环形进度改为横向百分比条（保留 75% / 90% 告警变色）
- 四个指标方框移至条形下方，依次为：存储类型 / 已用空间 / 剩余空间 / 总容量
- 描述文案同步调整

### 其他

- 对外文件服务卡片顺序调整为：FTP → SFTP → WebDAV → OSS
- 内存挂载（Memory）存储插件：容量读数修复（解析 `-XX:MaxDirectMemorySize`），上限 64GB
- 本地挂载（LocalMount）插件：根路径归一化、跟随符号链接改布尔开关、实时监听（OS 目录变更秒级同步索引）与定时扫描双模式
- 本地直挂（LocalDirect）：不建索引实时列目录，增删改穿透真实文件系统

## 升级注意

- 从 v5.0.1 升级：新增 `oss_access_keys` 表与 `service_settings` 的 `svc-oss` 种子行，`bin\start.bat` 首次启动自动初始化；已有库需手动执行（见 `sql/mysql/gfs.sql` 中对应建表语句）
- 部署包不含运行数据与机器本地配置；解压后 `bin\start.bat` 首次启动自动初始化数据库与配置
