---
name: gfs-build-release
description: 编译、打包与发布 GFS 发行版（Windows zip / Linux tar.gz），含版本号、前端三处镜像、校验与常见坑
---

# GFS 编译打包与发布

## 版本号

- `pom.xml` 的 `<revision>` 固定为 `5.0`，**不是发行版本号**。
- 发行版本由命令行 `-Version x.y.z` 显式传入；不传会回退成 `5.0`，属于错误产物。
- 打包前先看 `release/` 下已有包和最近一条 `chore(release)` 提交，确认是递增还是重打同版本；重打会覆盖同名文件并改变 SHA256。

## 工具链（禁止使用发行包内置运行时）

| 工具 | 路径 |
|---|---|
| Maven | `D:\devTools\maven\bin\mvn.cmd` |
| JDK 21 | `D:\devTools\jdk\jdk21.0.12.1` |
| Node / pnpm | `D:\devTools\nvm` |

Git Bash 中传 Windows 路径必须用**单引号**：`-MavenCmd 'D:\devTools\maven\bin\mvn.cmd'`。

## 打包命令

Windows（构建 jar + 同步前端 + 打 zip）：

```bash
powershell -ExecutionPolicy Bypass -File script/package-release.ps1 -Version 5.0.16 -MavenCmd 'D:\devTools\maven\bin\mvn.cmd'
```

Linux（复用现有 jar 与前端，`-SkipBuild`）：

```bash
powershell -ExecutionPolicy Bypass -File script/package-release-linux.ps1 -Version 5.0.16 -SkipBuild -MavenCmd 'D:\devTools\maven\bin\mvn.cmd'
```

产物：`release/gfs-<ver>-windows-x64.zip`、`release/gfs-<ver>-linux-x64.tar.gz`。两者只作 GitHub Release 附件，**不入 git**。

## 前端产物：三处镜像

`fs-ui` 执行 `pnpm build` 后，`fs-ui/dist` 要同步到三处：

1. `fs-admin/src/main/resources/static`（打进 jar）
2. `release/deploy-package/frontend`（Windows 包热更新层）
3. `release/deploy-package-linux/frontend`（Linux 包热更新层）

镜像前先 `rm -rf` 目标目录再复制，`cp -r` 会残留上一版 hash 资源。打包脚本会校验 jar 内嵌 index 与 dist 的 asset 引用一致；只改 TS/TSX 注释不会改变 bundle hash，可省一次重建。

## 打包前检查

1. 停掉占用 `fs-admin/target/fs-admin.jar` 的本地进程：`.agent/scripts/stop-backend.ps1`，否则 repackage 失败。
2. 清理 `fs-admin/target/classes/static`，避免旧 hash 资源被增量复制进 jar。
3. Linux 包若上次打包后留下空的 `data/mysql`、`data/redis`、`data/upload` 骨架目录，用 `rmdir` 删除；**非空说明有真实数据，脚本会报错，不要 `rm -rf`**。
4. Linux tar 必须由 Git Bash 的 GNU tar 生成（脚本已处理），不能用 PowerShell 原生 tar，否则丢权限位。

## 打包后核验

```bash
# 包内 jar 与本地构建一致
sha256sum fs-admin/target/fs-admin.jar
unzip -p release/gfs-<ver>-windows-x64.zip gfs-<ver>/lib/fs-admin.jar | sha256sum
tar -xOzf release/gfs-<ver>-linux-x64.tar.gz gfs-<ver>/lib/fs-admin.jar | sha256sum

# 跑测试（根 pom 默认 skipTests=true）
JAVA_HOME=/d/devTools/jdk/jdk21.0.12.1 /d/devTools/maven/bin/mvn.cmd -pl fs-admin -am -DskipTests=false -DfailIfNoTests=false test
```

## 发布 GitHub Release（仅在用户明确要求时）

本机没有 `gh` CLI，走 REST API：

1. 从 `git credential fill` 取 PAT，不打印。
2. 先 `GET /repos/guangheUlti/gfs/releases?per_page=10` 查目标版本是否已存在。
3. `POST /releases`（tag_name `v<ver>`、target_commitish 为当前 HEAD、中文 body 写进 `.agent/tmp/*.md` 后由 Node fetch 提交）。
4. `POST uploads.github.com/.../releases/<id>/assets?name=<文件名>`，`--data-binary @文件 --retry 5`。
5. 核验附件 size、下载 URL、tag 指向；不要提交 zip/tar。

## 常见坑

- 双击/直接跑 `release/deploy-package/bin/start.bat` 会在发行目录产生数据、日志和机器本地配置，禁止用于开发联调。
- 同版本重打后 SHA256 变化，已有 Release 附件需 `--clobber` 覆盖。
- jar 被运行中的 dev 后端占用时，Windows 上 `mvn package` 会失败。
