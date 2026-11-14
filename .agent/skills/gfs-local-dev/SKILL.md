---
name: gfs-local-dev
description: GFS 本地开发环境：工具链、MySQL/Redis、编译启动、端口、前端 dev server 与健康检查
---

# GFS 本地开发与重启

## 强制约定

开发、编译、联调、测试一律使用 `D:\devTools` 下的工具链，**不要用 `release/deploy-package/lib` 里捆绑的运行时**，也不要执行 `release/deploy-package/bin/start.bat`（会在部署包内产生数据、日志和本地配置）。

| 工具 | 路径 / 参数 |
|---|---|
| Maven | `D:\devTools\maven\bin\mvn.cmd` |
| JDK 21 | `D:\devTools\jdk\jdk21.0.12.1` |
| MySQL 8.4 | `D:\devTools\mysql`（`bin\mysql.exe`） |
| Redis 8.0 | `D:\devTools\redis\redis8.0` |
| Node / pnpm | `D:\devTools\nvm` |

## 数据库

`fs-admin/src/main/resources/application-dev.yml`：

- MySQL：`127.0.0.1:3306/gfs`，`root/root`
- Redis：`127.0.0.1:6379`，无密码
- 后端端口：`2000`

## 编译与启动

```bash
# 停掉占用 jar 的旧进程
powershell -ExecutionPolicy Bypass -File .agent/scripts/stop-backend.ps1

# 编译（根 pom 默认 skipTests=true）
JAVA_HOME=/d/devTools/jdk/jdk21.0.12.1 /d/devTools/maven/bin/mvn.cmd -DskipTests package

# 启动 dev 后端（仓库根目录执行）
/d/devTools/jdk/jdk21.0.12.1/bin/java.exe -jar fs-admin/target/fs-admin.jar \
  --spring.profiles.active=dev > fs-admin/target/dev-backend.log 2>&1 &
```

启动成功判据：日志出现 `Started FsAdminApplication`。

**健康检查注意**：2000 端口未暴露 `/actuator/health`，请求它会命中 SPA fallback 返回 `index.html`（HTTP 200，看似正常），不要据此判断后端可用。

## 前端

- 目录：`fs-ui`，使用 pnpm。
- dev server：`http://localhost:8000`，用户可能正在用手机实机验收，**不要擅自停掉 8000**。
- 窄屏验证按 390px 宽度量横向溢出。
- 生产构建产物 `fs-ui/dist` 被 git 忽略；构建后需同步三处镜像，见 `gfs-build-release`。

## 测试

```bash
JAVA_HOME=/d/devTools/jdk/jdk21.0.12.1 /d/devTools/maven/bin/mvn.cmd \
  -pl fs-admin -am -DskipTests=false -DfailIfNoTests=false test
```

测试类集中在 `fs-admin/src/test/java`，覆盖 IP 解析、协议认证锁定、存储临时目录清理、管理员建号越权回归等。

## 运行时目录

- 本地上传/头像/分片临时文件在仓库根 `storage/`（已 gitignore）。
- 分片临时目录残留可用 `storage/temp` 观察；孤儿目录由 `TransferTaskCleanupScheduler` 兜底扫描（无活动任务且超过 1 小时）。
