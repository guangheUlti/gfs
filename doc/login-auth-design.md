# GFS 登录认证机制设计

> 面向维护者：说明登录链路的设计思路、多端登录互不影响的实现原理，以及全部配置项的含义。
>
> 最后更新：2026-09-05 —— 移除 sa-token-jwt（Simple 模式），启用同账号多设备并发登录。

---

## 1. 组件全景

| 层 | 组件 | 位置 | 职责 |
| --- | --- | --- | --- |
| 入口 | `AuthController` | `fs-modules/fs-system/.../controller/AuthController.java` | `POST /apis/auth/login`、`POST /apis/auth/logout`；登录/登出时下发/失效 `Authorization` Cookie |
| 编排 | `AuthServiceImpl` | `fs-modules/fs-system/.../service/impl/AuthServiceImpl.java` | 策略分发 → 建立会话 → 写入会话数据 → 更新最后登录时间 |
| 分发 | `LoginStrategyFactory` | `fs-modules/fs-system/.../auth/LoginStrategyFactory.java` | 注入所有 `LoginStrategy` 实现，按 `loginType` 路由（新增登录方式无需改动此类） |
| 认证 | `PasswordLoginStrategy` | `fs-modules/fs-system/.../auth/impl/PasswordLoginStrategy.java` | 账号/邮箱 + 口令认证，当前 `LoginType` 仅有 `password` 一种 |
| 防爆破 | `LoginGuardService` | `fs-modules/fs-system/.../auth/LoginGuardService.java` | Redis 计数，按「账号 + 客户端 IP」维度失败锁定 |
| 口令 | `PasswordHashService` | `fs-modules/fs-system/.../auth/PasswordHashService.java` | BCrypt(cost=12)；识别遗留 SHA-256 哈希并在登录成功时透明升级 |
| 权限源 | `StpInterfaceImpl` | `fs-modules/fs-system/.../auth/StpInterfaceImpl.java` | 向 Sa-Token 提供权限码/角色，支撑 `@SaCheckPermission` 类校验 |
| 会话 | Sa-Token `StpLogic` | 框架 | token 生成、校验、注销、会话存储 |
| 审计 | `LoginLogAspect` + `@LoginLog` | `fs-modules/fs-system/.../aspect/LoginLogAspect.java` | 环绕 `doLogin`，采集 IP / 归属地 / 浏览器 / OS / 耗时，异步事件落库 |
| 拦截链 | `WebMvcConfig` | `fs-admin/.../web/WebMvcConfig.java` | 注册工作空间、登录校验、存储平台三个拦截器 |
| 前端 | `auth-context` / `utils/auth` / `api/request` | `fs-ui/src/` | 登录态编排、token 持久化、请求头注入、401 整页跳转 |

---

## 2. 登录时序

```
浏览器                     后端                                    存储
  │ POST /apis/auth/login   │
  ├────────────────────────►│ AuthController.doLogin
  │                         │  ├─ 该路径命中 security.excludes → 跳过登录校验
  │                         │  ├─ @LoginLog 切面开始（采 IP/UA/计时）
  │                         │  ├─ LoginStrategyFactory.getStrategy(password)
  │                         │  └─ PasswordLoginStrategy.authenticate
  │                         │      ├─ LoginGuardService.checkLoginAllowed(account)   ← Redis 锁定检查
  │                         │      ├─ 邮箱正则判定 → 按 email 或 username 查询 sys_user
  │                         │      ├─ 用户不存在 → recordLoginFailure → 统一错误文案
  │                         │      ├─ 状态判定：DISABLED / PENDING_REVIEW / REJECTED → 业务拒绝（不计入试错）
  │                         │      ├─ PasswordHashService.matches → 失败则 recordLoginFailure
  │                         │      ├─ clearLoginFailures + 遗留 SHA-256 → 升级为 BCrypt
  │                         │      └─ 更新 last_login_at
  │                         ├─ StpUtil.login(userId, isRemember)      ├─ 生成 token（random-128）
  │                         │                                        ├─ 写 token→userId 映射
  │                         │                                        └─ Account-Session 追加 terminal
  │                         ├─ StpUtil.getSession().set("username", …)
  │                         ├─ 再次更新 last_login_at（业务字段落库）
  │  ◄── Set-Cookie: Authorization=… ─┤
  │  ◄── body: { code:200, data:{ accessToken, username, id } }
  │ 前端 saveToken(accessToken, remember) → localStorage / sessionStorage
```

**要点**

- token **双通道下发**：响应体 `accessToken`（供前端存 web storage 并注入请求头）+ `HttpOnly` Cookie（供浏览器直接发起的下载/预览请求携带）。
- 用户不存在与口令错误返回**同一句文案**（`user.account.or.password.incorrect`），避免账号枚举。
- 禁用/待审核/已拒绝属于业务拒绝，**不计入密码试错次数**，不会因管理员审核延迟把用户锁在门外。

---

## 3. 凭证与 token 生命周期

| 维度 | 现行设计 |
| --- | --- |
| token 形态 | Sa-Token **普通模式**，`token-style: random-128`（128 位随机串），**不是 JWT** |
| 有效性判定 | 每次请求回查服务端 `token → loginId` 映射，因此**服务端可即时作废** |
| 会话存储 | Redis（`sa-token-redis-template` 提供的 `SaTokenDaoForRedisTemplate`）→ 后端重启不掉线、多实例共享会话，键与影响见 §5.4 |
| 绝对过期 | `timeout: 86400`（24 小时） |
| 活跃冻结 | `active-timeout: 3600`（1 小时无请求则冻结，再访问视为失效） |
| 携带方式 | ① `Authorization: Bearer <token>` 请求头（前端 axios 注入）② `Authorization` Cookie（浏览器自动）③ `is-read-body: true` 允许从请求体读取 |
| 前端持久化 | 勾选记住我 → `localStorage`（跨浏览器会话保留）；未勾选 → `sessionStorage`（关闭标签页即清） |
| 失效处理 | 响应体 `code: 401` → 前端清空本地登录态并整页跳转登录页（登录/注册接口自身的 401 不触发跳转，避免密码输错就跳页） |

> **Cookie 的 `isRemember` 语义**：只有勾选记住我时，Controller 才为 Cookie 设置 `maxAge = timeout`；否则下发的是会话级 Cookie（关闭浏览器即消失）。

---

## 4. 多端登录互不影响的实现原理

### 4.1 配置语义矩阵

| `is-concurrent` | `is-share` | 行为 |
| --- | --- | --- |
| **`true`** | **`false`** | ✅ **当前配置**：同一账号可在多设备/多浏览器同时在线，**每次登录新建独立 token**，各端会话彼此隔离 |
| `true` | `true` | 多端在线但**复用同一个 token**（同设备类型只保留一份凭证），一处注销即全端下线 |
| `false` | 任意 | 新登录顶掉旧登录（单端互踢），可用 `replacedLoginExitMode` / `replacedRange` 微调 |

### 4.2 为什么各端互不影响

Sa-Token 的会话模型分三层，这是「多端并存」的结构性基础：

```
Account-Session（按 loginId 唯一，账号级共享数据）
 ├── tokenValue → username 等账号级数据
 └── terminalList：每个已登录终端一条 SaTerminalInfo
       ├── { deviceType: "DEF", tokenValue: <t1>, createTime: … }   ← 设备 1
       └── { deviceType: "DEF", tokenValue: <t2>, createTime: … }   ← 设备 2
Token-Session（按 tokenValue 唯一，端级私有数据）
```

- `is-concurrent: true` 时，登录流程**跳过顶人分支**（`distUsableToken` 只有在 `isConcurrent == false` 时才调用 `replaced()`），直接为本次登录新建 token 并向 `terminalList` 追加一条终端记录。
- `is-share: false` 保证每端拿到**不同**的 token；若为 `true`，同设备类型会复用旧 token，多端就共享同一凭证了。
- 登出调用的是 `StpUtil.logout()`（**无参重载**），它只注销「当前请求上下文携带的那个 token」对应的终端记录，不会遍历注销该账号的其它终端 → **A 设备退出，B 设备不受影响**。

### 4.3 实测结论（2026-09-05，本机 dev 环境）

| 步骤 | 预期 | 实测 |
| --- | --- | --- |
| 同一账号连续登录两次 | 得到两个不同 token | ✅ `ZWMCHBmk…` / `rOJMgTnq…` |
| 两个 token 分别访问 `/apis/user/info` | 均通过 | ✅ 均 `code:200` |
| 用 token1 调 `/apis/auth/logout` 后再用 token1 访问 | 被拒绝 | ✅ `code:401`（Token 无效） |
| 此时 token2 再访问 | 仍可用 | ✅ `code:200` |
| 伪造 token 访问 | 被拒绝 | ✅ `code:401` |

> 上述五项的基础验证是直接用 `java -jar release\deploy-package\lib\fs-admin.jar --server.port=18080` 跑的，即结论对**当时的发行包产物**成立，不只是对 `mvn spring-boot:run`。

启用 `sa-token-redis-template`（会话真正落到 Redis）之后，按当前源码追加验证（验证脚本为本地辅助脚本，放在未入库的 `.qoder/` 下：`verify-redis-session.ps1` 分 pre/post 两阶段跨越一次重启、`verify-multi-instance.ps1` 跑双实例）：

| 步骤 | 预期 | 实测 |
| --- | --- | --- |
| 登录两次后查 Redis | 出现 `Authorization:login:token:*`、`session:*`、`last-active:*` | ✅ 7 个键，键名内嵌 token |
| 单独登出 token1 后查键数 | 只回收 token1 相关的键 | ✅ 7 → 5 |
| **kill 后端进程并重启**，token2 再访问 | 仍可用（会话不在了 JVM 里） | ✅ `code:200` |
| 重启后，用重启前已登出的 token1 访问 | 仍被拒绝（登出记录也持久化） | ✅ `code:401` |
| 实例 A（:80）签发的 token 拿到实例 B（:18080）访问 | 通过（共享会话） | ✅ `code:200`，两实例 token 仍互不相同 |
| 在实例 B 登出该 token，回实例 A 访问 | 被拒绝（登出/踢人全集群可见） | ✅ `code:401` |
| 期间另一实例签发的 token 在 A 上访问 | 不受影响 | ✅ `code:200` |
| 全部登出后 Redis 残留会话键 | 无泄漏 | ✅ `Authorization:login:*` = 0 |

> 注意：追加验证跑的是**当前源码**，而 GitHub 上已发布的 `v2.3.1` zip 是本次改动**之前**构建的；要让发行包具备该能力，需用 `script\package-release.ps1` 重新出包（见 §5.3）。

### 4.4 历史坑：为什么过去「配置看起来不生效」

此前 `fs-security` 的 `SaTokenAutoConfigure` 注册了 `StpLogicJwtForSimple` Bean（sa-token-jwt 的 Simple 模式）。该模式把 `getLoginIdNotHandle()` 覆盖为**纯 JWT 解析**，于是：

- 校验链路**不再回查服务端存储**，只要 JWT 签名正确且未过期即视为已登录；
- 即使 `is-concurrent: false` 让顶人逻辑正常执行（终端记录、token 映射都删了），**已签发的旧 JWT 依然可用直到 24 小时过期**；
- 连 `StpUtil.logout()` 也无法真正下线 —— 表现为「退出登录后 token 仍有效」，属于安全隐患；
- 表面看到的「多设备同时在线」其实是被这个缺陷动因造成的假象，而非 `is-concurrent` 配置的效果。

2026-09-05 的处理：删除该 Bean、移除 `sa-token-jwt` 依赖、删除 `jwt-secret-key` 配置，并将 `is-concurrent` 显式设为 `true` —— 让「多端并存」由配置真实决定，同时恢复登出/顶人/踢人的有效性。

> **经验**：任何依赖「服务端主动让某个 token 失效」的能力（登出、踢人、顶人、封禁），都必须保证校验会回查服务端存储。纯 JWT 自证式的校验做不到这一点。

### 4.5 如需改回「单端互踢」

1. `sa-token.is-concurrent: false`，重启后端；
2. 可选语义微调（Sa-Token 默认值即为下述组合）：
   - `replacedLoginExitMode`：`OLD_DEVICE`（旧设备被顶下线，新登录成功）/ `NEW_DEVICE`（拒绝新登录，旧设备保持在线）；
   - `replacedRange`：`CURR_DEVICE_TYPE`（仅顶同设备类型）/ `ALL_DEVICE_TYPE`（顶掉该账号所有设备）。

---

## 5. 配置项详解

### 5.1 `sa-token`（`fs-admin/src/main/resources/application.yml` 中 `---` 分隔的 Sa-Token 文档块）

| 配置项 | 当前值 | 含义 |
| --- | --- | --- |
| `token-name` | `Authorization` | 凭证名，同时作为 Cookie 名与请求头名 |
| `token-prefix` | `Bearer` | 请求头读取 token 时要求的前缀，即 `Authorization: Bearer <token>` |
| `cookie-auto-fill-prefix` | `true` | 写 Cookie 时自动补上 `Bearer ` 前缀，保持与请求头一致 |
| `timeout` | `86400` | token 绝对有效期（秒），24 小时；`-1` 表示永久 |
| `active-timeout` | `3600` | 最低活跃频率（秒），1 小时无访问则冻结；`-1` 表示不限制 |
| `is-concurrent` | `true` | **是否允许同账号多端同时在线**，见 §4 |
| `is-share` | `false` | 多端是否共用同一 token；`false` = 每次登录新建独立 token |
| `is-read-body` | `true` | 允许从请求体读取 token（配合表单/特殊客户端场景） |
| `token-style` | `random-128` | token 生成风格：`uuid`/`simple-uuid`/`random-32`/`random-64`/`random-128`/`tik` |
| `is-log` | `true` | Sa-Token 操作日志开关 |
| `cookie.http-only` | `true` | Cookie 禁止 JS 读取，降低 XSS 窃取风险 |
| `cookie.secure` | `${AUTH_COOKIE_SECURE:false}` | 是否仅 HTTPS 下发；生产以 HTTPS 暴露时置 `true` |
| `cookie.same-site` | `${AUTH_COOKIE_SAME_SITE:Strict}` | 跨站发送策略：`Strict`/`Lax`/`None`；前后端分离跨域部署时通常需调成 `Lax` 或 `None`（`None` 必须配 `secure: true`） |

### 5.2 `security`（同文件「认证授权相关配置」文档块）

| 配置项 | 当前值 | 含义 |
| --- | --- | --- |
| `path-pattern` | `/apis/**` | 需要登录校验的 URL 前缀（REST 接口全部收敛在此前缀下）；`/files/**` 等静态资源不在其中 |
| `excludes` | 见下 | **免登录白名单**（Ant 风格），逐条含义见下表 |
| `auth-cookie.secure` / `auth-cookie.same-site` | 环境变量注入 | Controller 手工下发 Cookie 时使用，与 `sa-token.cookie` 保持同源配置 |
| `brute-force.max-attempts` | 默认 `3` | 连续失败多少次触发锁定（`LoginGuardService`） |
| `brute-force.lock-minutes` | 默认 `30` | 锁定时长（分钟），同时作为失败计数的窗口期 |

`excludes` 白名单逐条说明：

| 路径 | 为什么免登录 |
| --- | --- |
| `/apis/auth/login` | 登录本身 |
| `/apis/transfer/sse` | SSE 长连接（`EventSource` 无法自定义请求头） |
| `/apis/user/register` | 开放注册（注册后仍需审核才可登录） |
| `/apis/invitation/verify/**` | 邀请链接校验，被邀请人可能尚未登录 |
| `/apis/share/**/items`、`/apis/share/verify/code`、`/apis/share/**/info`、`/apis/share/**/download/**` | 分享链接的匿名访问/提取码校验/取文件 |
| `/apis/file-collections/public/**` | 公开合集 |
| `/dav/**`、`/webdav-test/**` | WebDAV 通道，使用自己的鉴权方式 |

### 5.3 发行版（`release/deploy-package`）如何生效

- `conf/application-prod.yml` **不含** `sa-token` / `security` 段 → 这些配置继承自 jar 内的 `application.yml`；
- 因此**修改登录相关配置需要重新打包 jar**；临时覆盖可用启动参数（`--sa-token.timeout=7200`）或环境变量 `SPRING_APPLICATION_JSON`；
- Cookie 的跨站策略通过 `bin\env.bat` 导出的 `AUTH_COOKIE_SECURE` / `AUTH_COOKIE_SAME_SITE` 调整，无需改配置文件；
- 发行版内置 MySQL/Redis/JDK，`bin\start.bat` 按 Redis → MySQL → 应用顺序拉起。
- 发行包整体由 `script\package-release.ps1` 重建（构建 → 产物入位 → 打「干净」zip：剔除 `data\mysql`、`data\redis`、`data\upload`、`logs\*`、`*.log` 以及 `bin\env.bat` 首次运行会重新生成的 `conf\my.ini` / `conf\redis.conf`）。zip 不进代码仓库，作为 GitHub Release 附件分发。

### 5.4 会话存储（`SaTokenDao`）

| 项 | 说明 |
| --- | --- |
| 实现类 | `cn.dev33.satoken.dao.SaTokenDaoForRedisTemplate`，由 `fs-framework/fs-security/pom.xml` 引入的 `sa-token-redis-template` 提供，通过 jar 内 `AutoConfiguration.imports` 自动装配，**无需额外配置** |
| 生效确认 | 启动日志 `Sa-Token 全局组件 SaTokenDao 载入成功: cn.dev33.satoken.dao.SaTokenDaoForRedisTemplate`（未启用时是 `SaTokenDaoDefaultImpl`） |
| 存储介质 | 复用 `spring.data.redis`（dev/prod 均为 `127.0.0.1:6379` db0，无密码），与 Spring Cache 同一实例 |
| 键名 | `Authorization:login:token:<tokenValue>`（token → loginId 映射）、`Authorization:login:session:<loginId>`（账号级 Account-Session）、`Authorization:login:last-active:<tokenValue>`（活跃时间戳，支撑 `active-timeout`） |
| 值格式 | 走 `StringRedisTemplate`，值为 JSON 字符串，可直接 `redis-cli get` 排查（区别于 JDK 二进制序列化） |
| 收益 | 后端重启不再全员掉线；多实例可共享会话且登出/踢人全集群即时生效 |
| 代价 | Redis 成为登录硬依赖。但 `spring.cache.type: redis` 本就要求 Redis 可用，`bin\start.bat` 也已按 Redis → MySQL → 应用顺序拉起，**没有新增运维前提** |
| 回退 | 注释掉该依赖即回到 JVM 内存实现，无需其它改动（代价：重启掉线、无法多实例） |

---

## 6. 请求进入时的鉴权链

`WebMvcConfig.addInterceptors` 注册的顺序（`order` 越小越先执行）：

| 顺序 | 拦截器 | 作用范围 | 职责 |
| --- | --- | --- | --- |
| 1 | `WorkspaceInterceptor` | `/apis/**` + 预览 token 路径 | 解析 `X-Workspace-Id`（头优先，回退同名查询参数）→ 校验当前登录用户是否为该工作空间成员 → 写入 `WorkspaceContext`；`afterCompletion` 清理 ThreadLocal |
| 2 | `SaInterceptor(handle -> StpUtil.checkLogin())` | `security.path-pattern` 去掉 `excludes` | 登录校验，未登录抛 `NotLoginException` |
| 2 | `SaInterceptor`（预览 token 路径） | `/preview/token/**`、`/archive/preview/token/**` | 预览链路的独立登录校验 |
| 3 | `StoragePlatformInterceptor` | 同 `path-pattern` 去 `excludes` | 依据 `X-Storage-Platform-Config-Id` 切换本次请求的存储平台上下文 |

两套白名单不要混淆：

- `security.excludes`：免**登录**校验（匿名可访问）；
- `WorkspaceInterceptor.WHITELIST`：免**工作空间上下文**（仍需登录，如 `/apis/user/info`、`/apis/workspace/list`、`/apis/auth/logout`、`/apis/admin/**`）。

> **排查提示（重要）**：本项目的业务异常经 `GlobalExceptionHandler` 统一封装为 **HTTP 200 + body `code`**（例如未登录是 `{"code":401,"msg":"Token无效…"}`）。只有 `WorkspaceInterceptor` 直接写响应流的场景才是真 400/401/403。用 `curl`/脚本验证登录态时**必须检查响应体的 `code` 字段**，只看 HTTP 状态码会得到「好像没鉴权」的错误结论。

---

## 7. 扩展与维护指南

| 想做的事 | 怎么做 |
| --- | --- |
| 新增登录方式（短信、OAuth…） | 在 `LoginType` 枚举加值 → 实现 `LoginStrategy`（`getLoginType()` 返回新枚举）并注册为 `@Component`，工厂自动收集，无需改 `LoginStrategyFactory` |
| 调整多端策略 | 见 §4.5；改完重启后端 |
| 支持多实例部署 / 重启不掉线 | ✅ 已是默认行为（`sa-token-redis-template` 已启用，实测见 §4.3、键与代价见 §5.4）；退回 JVM 内存存储只需注释掉该依赖 |
| 强制某端下线 | 普通 token 模式下 `StpUtil.logout(loginId, device)` / `StpUtil.kickout(...)` 均可用（旧 JWT 模式下这些API 均失效） |
| 直连数据库改 `sys_user` | 必须清 Redis 用户缓存 `user:{userId}`，否则 `/apis/user/info` 返回旧值（`SysUserServiceImpl.getDetail()` 带 `@Cacheable("user")`） |
| 换头像/改资料后前端不更新 | 走应用接口（会自动 evict 缓存），不要直接改库 |

---

## 8. 关键文件索引

| 关注点 | 文件 |
| --- | --- |
| 登录/登出接口 | `fs-modules/fs-system/src/main/java/com/guanghe/fs/system/controller/AuthController.java` |
| 登录编排 | `fs-modules/fs-system/src/main/java/com/guanghe/fs/system/service/impl/AuthServiceImpl.java` |
| 密码认证与防爆破调用 | `fs-modules/fs-system/src/main/java/com/guanghe/fs/system/auth/impl/PasswordLoginStrategy.java` |
| 失败锁定 | `fs-modules/fs-system/src/main/java/com/guanghe/fs/system/auth/LoginGuardService.java` |
| 口令哈希与升级 | `fs-modules/fs-system/src/main/java/com/guanghe/fs/system/auth/PasswordHashService.java` |
| Sa-Token 框架配置 | `fs-admin/src/main/resources/application.yml`（`sa-token` / `security` 两个文档块） |
| 拦截器注册 | `fs-admin/src/main/java/com/guanghe/fs/web/WebMvcConfig.java` |
| 工作空间校验 | `fs-admin/src/main/java/com/guanghe/fs/interceptor/WorkspaceInterceptor.java` |
| 前端登录态 | `fs-ui/src/contexts/auth-context.tsx`、`fs-ui/src/utils/auth.ts`、`fs-ui/src/api/request.ts` |
| 登录页跳转 | `fs-ui/src/router/index.tsx`（`RootRedirect`：登录后直达 `/w/{slug}/files`） |
