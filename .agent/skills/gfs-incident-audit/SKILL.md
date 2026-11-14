---
name: gfs-incident-audit
description: GFS 生产日志与数据库的安全排查方法：证据来源、登录耗时判据、IP 伪造辨识、已知事件与审计盲区
---

# GFS 生产安全排查

## 证据优先级

1. `sys_operation_log`：谁（`operator_name`）、从哪（`operation_ip`）、用什么客户端（`user_agent`）、对谁（`target_id`/`target_name`）、做了什么（`operation_type`）、何时（`operation_time`）。
2. `sys_login_log`：`username`、`login_ip`、`status`、`msg`、`login_time`。
3. `sys_user`：账号现状、`status`、`register_ip`、`created_at`、`last_login_at`。
4. 应用文件日志：登录耗时、异常堆栈、存储/清理任务行为；**不记录 HTTP 请求操作者**，不能单独用于归因。

先查 `sys_operation_log` 硬证据，再下结论。不要因为用户名像测试账号就默认是用户自测——2026-11-03 事件中 `zzptest01-07` 正是攻击者越权创建。

## 登录耗时判据

- `~650–750ms`：走了 BCrypt，账号存在且**密码正确**（成功登录或口令已验证）。
- `2–5ms`：用户不存在或账号待审核/禁用，未做密码校验。
- `700ms 左右但失败`：账号存在、密码错误。

`sys_login_log.msg` 与 `sys_operation_log.operation_time` 可交叉验证同一秒内的建号/登录行为。

## IP 可信度

- 当前 `security.trust-proxy: false`，HTTP 侧只取 TCP 对端地址，`X-Forwarded-For` 不可信。
- **2026-11-04 之前**曾采信 XFF，历史日志里的来源 IP 可能被伪造；判断时结合 IP 归属地与行为模式，不要只看 IP。
- `203.0.113.0/24`、`198.51.100.0/24` 是 RFC 5737 文档保留段（TEST-NET），公网不可路由，出现即人为伪造。
- 常见攻击者地址形态：`172.247.228.250`、`20.0.2.101`、`10.99.99.99`、`198.51.100.x`；用户常用管理 IP 如 `111.198.29.4`。

## 账号与权限模型

- `sys_user.status`：`0` 正常、`1` 禁用、`2` 待审核、`3` 已拒绝。
- 系统管理员由 `security.super-admin.username` 决定（默认 `admin`），不是角色表。
- 开放注册默认 `PENDING_REVIEW`；管理员手动创建直接 `NORMAL`。
- 管理接口在服务层调用 `assertSuperAdmin()`，`/apis/admin/**` 另有管理员角色拦截兜底。

## 已知事件：2026-11-03 越权建号

- 攻击链：`guanghe` 口令泄露 → 普通会话调用 `POST /apis/admin/users` 批量创建 `zzptest01-07`（免审核）→ 尝试探测存储/服务接口（被权限码拦截）→ 通过 SFTP 存储向 `172.19.32.10` 的 Tomcat 写入 `ws49110.jsp`。
- 根因：`SysUserServiceImpl.createUserByAdmin()` 漏写 `assertSuperAdmin()`，且 `/apis/admin/**` 当时只有登录校验。
- 修复：服务层补校验 + `/apis/admin/**` 增加管理员角色拦截 + 越权回归测试。
- 教训：`admin` 审批成功不是「系统自动审批」，而是普通账号借越权接口造出了已审定账号。

## ULID 反解 ID 创建时间

`sys_user.id` 是 ULID，前 10 位 base32 编码毫秒时间戳，可用于判断账号创建时刻、区分同批账号：

```js
const A = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
function decode(id) {
  let t = 0n;
  for (const c of id.slice(0, 10).toUpperCase()) t = t * 32n + BigInt(A.indexOf(c));
  return new Date(Number(t) + 8 * 3600 * 1000); // 东八区
}
```

`sys_operation_log` 记录的是操作发生时间，二者对齐可确认「某批账号就是某次操作创建的」。

## 审计盲区

- 协议端（WebDAV/SFTP/FTP）密码错误不写持久化日志：`DavUserAuthenticator` 校验失败只累加 Redis 防暴力计数，数据库无记录。排查暴力破解要看 `LoginGuardService` 的锁定状态与应用 WARN（IP 名单/锁定拒绝会打日志）。
- 应用文件日志没有访问日志，看不到 URI 的操作者；操作者只能从数据库操作日志取。

## 处置清单（确认入侵后）

1. 改 `guanghe`、`admin`、`founder` 等账号口令，踢掉全部会话。
2. 删除攻击者创建的账号，复核 `DELETE_USER`/`CREATE_USER` 操作日志确认操作者。
3. 清理内网被写入的 JSP/webshell，检查对应 Tomcat 的 `webapps`。
4. 收紧上传 `objectKey` 边界（客户端可指定 objectKey 越出目录写远端存储，属待修项）。
5. 生产开启 `springdoc` 关闭、IP 白名单、`security.trust-proxy` 保持 false。
