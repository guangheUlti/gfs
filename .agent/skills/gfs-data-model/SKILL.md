---
name: gfs-data-model
description: GFS 数据库与迁移约定：无 Flyway、全量初始化 SQL、双数据库口径、种子数据与账号状态
---

# GFS 数据模型与迁移

## 迁移策略

- 项目**没有 Flyway/Liquibase**，DDL 手工执行。
- `sql/` 只保留全量初始化脚本：
  - MySQL：`sql/mysql/gfs.sql`
  - PostgreSQL：`sql/postgresql/gfs_pg.sql`
- 2026-11-05 起不再提供 `upgrade-*.sql` 增量脚本；存量库升级由 agent 生成一次性 SQL 并交由用户执行，不要擅自写回 `sql/` 目录。
- 改实体结构后必须同步两份全量脚本；两套脚本的历史表数量并不完全一致（需逐表核对，不要假设自动同步）。

## 执行原则

1. 改 SQL 或实体后，主动说明：**现有库是否需要执行、生产是否需要迁移**。
2. 生产库操作前先备份；不确定时只给 SQL，不直连执行。
3. 种子数据（服务列表、存储平台、系统配置）在 MySQL 与 PostgreSQL 两边都要补齐，缺一行可能导致功能页空白。

## 核心表

| 表 | 用途 |
|---|---|
| `sys_user` | 账号；`status` 0 正常 / 1 禁用 / 2 待审核 / 3 已拒绝 |
| `sys_operation_log` | 操作审计：operator、operation_type、target、IP、UA、时间 |
| `sys_login_log` | 登录审计：username、login_ip、status、msg、时间 |
| `sys_ip_guard` | IP 白/黑名单，`mode` 为 `OFF` 时降级放行；缺表不阻塞启动 |
| `file_transfer_task` | 分片/传输任务，`upload_id` 关联本地临时目录 |
| `sys_storage_platform` / `sys_user_storage` | 存储平台与用户存储实例配置 |
| `sys_service_settings` | 对外协议服务的存储绑定与开关 |

## 主键与时间

- 业务主键多为 ULID（26 位 Crockford Base32），可按前 10 位反解创建时间，见 `gfs-incident-audit`。
- `BaseEntity` 统一提供 `created_at` / `updated_at`，脚本里用 `NOW()` 填充。
- 删账号是物理删除并清理其文件/分享/收藏/传输任务，`sys_operation_log` 中对应 `DELETE_USER` 记录保留。

## 权限与账号

- 没有角色表：系统管理员由 `security.super-admin.username`（默认 `admin`）判定。
- 权限编码唯一来源：`UserPermissions`（`file:read`、`file:write`、`file:share`、`storage:manage`、`service:manage`、`log:read`）。
- 新增管理接口时同时考虑服务层 `assertSuperAdmin()` 与 `/apis/admin/**` 拦截，二者缺一不可。

## 常用查询

```sql
-- 某账号最近登录
SELECT username, login_ip, status, msg, login_time
FROM sys_login_log WHERE username = ? ORDER BY login_time DESC LIMIT 50;

-- 某时段所有管理操作
SELECT operation_time, operator_name, operation_type, target_name, operation_ip, user_agent
FROM sys_operation_log
WHERE operation_time BETWEEN ? AND ?
ORDER BY operation_time;
```
