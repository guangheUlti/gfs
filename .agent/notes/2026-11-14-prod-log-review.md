# 2026-11-14 生产日志排查（5.0.19 升级后）

## 结论

- 5.0.19 部署（11:53 启动）后到 12:00 无 ERROR，只有启动期 WARN（springdoc、OSS AES、SSE 心跳）。
- 生产近 3 天 ERROR 主要是 10:58 的「批量删除账号失败」，根因是旧 JAR 缺 `replace_file_id` 列，已在该日 10:59 补列并在 5.0.19 部署前验证。
- `gfs.2026-11-05.log` 单日 2.45 GB（23.2M 行，197k ERROR）已删除，释放 2.29 GB；logs 目录现约 49 MB。

## 11-05 大日志成因

时间集中在 11:08 一分钟内，196,948 条 ERROR 全部来自 `FileRecycleServiceImpl`：

```
删除无引用物理文件失败: <相对路径>
BusinessException: 存储配置已禁用: c5db82847e3449feb399e259d229d14c
  at FileObjectReferenceService.deletePhysicalFileIfUnreferenced(...)
  at FileRecycleServiceImpl$1.afterCommit(...)
```

- 存储配置 `c5db...` 是已删除的 SFTP（`storage_settings.deleted=1, enabled=0`）。
- 当时清空了包含约 19.7 万个文件记录的回收站（路径形如 `gitlab/...`、`opt/tomcat/...`、`opt/dmdbms/...` 的整棵目录树），清理逻辑逐文件删物理文件，每次因配置已禁用抛异常并打印完整堆栈，单条约 120 行。
- 即：**一次批量清理 × 已禁用存储 × 逐文件全栈日志** = 2.45 GB。不是崩溃、OOM 或死循环。

## 可优化点（按收益排序）

1. **回收站/存储删除的失败聚合**：`FileRecycleServiceImpl` 在存储配置已禁用/不存在时应整批跳过，只记一条汇总（文件数、存储 ID、原因）；确需逐文件时也应聚合，避免 N 份堆栈。这是本次 2.45 GB 的直接原因。
2. **日志按大小滚动**：`fs-admin/src/main/resources/logback-spring.xml` 目前只有 `TimeBasedRollingPolicy` + `MaxHistory=30`，没有 `maxFileSize`/`totalSizeCap`。改为 `SizeAndTimeBasedRollingPolicy`（如 200 MB/文件、总上限 5–10 GB）可兜底任何错误风暴。
3. **OSS_AES_KEY 未固化**：每次启动生成随机主密钥并 WARN。生产当前只有 Local 存储启用，暂无影响；一旦启用 OSS，重启后已签发的 Secret Key 会无法解密，应在启用前通过环境变量/conf 固化 32 位密钥。
4. **登录失败噪声**：`founder`、`artexpen*` 等账号从 172.19.82.153 反复失败（内网某客户端用了旧口令），可能触发 IP 维度的失败计数，建议清理客户端保存的凭据。
5. **扫描器噪声**：`不支持当前请求方法`（POST / 静态资源等）属外部探测，可忽略；如要降噪可在 GlobalExceptionHandler 对 404/405 记 WARN 而非 ERROR。
6. **上传超限**：`MaxUploadSizeExceededException` 只回笼统「文件上传异常」，可返回明确的大小上限提示。

## 已实施（2026-11-14，13:00 前完成并部署）

- **清理失败聚合**：`FileRecycleServiceImpl` 按存储配置分组，配置不可用时整组跳过只记一条 WARN；逐文件失败按组汇总（失败数、最多 5 个样本、首个错误），挂载目录同样聚合。不再逐文件打印堆栈。
- **日志滚动**：`logback-spring.xml` 改为 `SizeAndTimeBasedRollingPolicy`，单文件 200 MB、总上限 10 GB、保留 30 天；文件名变为 `gfs.<date>.<序号>.log`。
- **OSS 主密钥**：生产用 `setx /M OSS_AES_KEY` 固化 32 位随机密钥（服务器本机生成，未写入仓库），重启不再告警/随机。
- **降噪**：405 改记 WARN 且不打堆栈；`MaxUploadSizeExceededException` 改记 WARN 并返回 `file.size.exceeded`（「文件大小超出限制」）。
- **部署**：新 jar `ffcfd3a2…` 于 12:48 替换，应用 12:49 启动，pid 7924、session 2、HTTP 200；新日志 `logs/gfs.2026-11-14.0.log`，无 OSS 告警。按用户要求（2026-11-14）生产不再保留任何备份，历史备份目录已删除，部署脚本已去掉备份逻辑。
- 登录失败噪声未改代码：属内网客户端旧口令，需清理客户端凭据。
