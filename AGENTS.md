# GFS 项目开发约定

## 开发与测试环境（强制）

- 开发、编译、联调、测试一律使用 `D:\devTools` 下的工具链，**禁止使用 `release\deploy-package\lib` 里捆绑的运行时**（那是发行版自带内容，必须保持原样）：

  | 工具 | 路径 |
  |---|---|
  | Maven | `D:\devTools\maven\bin\mvn.cmd` |
  | JDK 21 | `D:\devTools\jdk\jdk21.0.12.1` |
  | MySQL 8.4 | `D:\devTools\mysql`（客户端 `bin\mysql.exe`） |
  | Redis | `D:\devTools\redis\redis8.0` |
  | Node / pnpm | `D:\devTools\nvm`（node 24 / pnpm 11） |

- 本地联调直接跑 `D:\devTools` 的 MySQL/Redis + `fs-admin` 的 dev 配置（或 `mvn spring-boot:run`），**不要执行 `release\deploy-package\bin\start.bat`**——那会在部署包里产生数据、日志和机器本地配置。

## release\deploy-package（发行版模板目录）

- 此目录是打包模板，必须保持干净：不得积累 MySQL/Redis 数据、日志、上传文件等运行残留。
- 以下内容是运行时生成物（`bin\env.bat ensure_configs` 与 `init.bat` 可自动再生），如果出现在目录里应删除：
  - `conf\my.ini`、`conf\redis.conf`、`conf\jvm-xmx.conf`、`conf\jvm-restart.flag`
  - `data\mysql\*`、`data\redis\*`、`data\upload\*`、`logs\*`、`storage\*` 中的实际文件
  - `lib\fs-admin.jar.bak` 等任何补丁/备份残留
- `lib\{jdk,mysql,redis}` 三个运行时目录不进 git，由打包脚本原样收入 zip。
- 打包命令（版本号取 pom.xml `<revision>`）：

  ```powershell
  powershell -ExecutionPolicy Bypass -File script\package-release.ps1 -MavenCmd D:\devTools\maven\bin\mvn.cmd
  ```

- zip 输出到 `release\gfs-<版本>-windows-x64.zip`，同一版本重新打包直接覆盖同名文件；zip 只作 GitHub Release 附件，不提交 git。
- 打包脚本会把 `fs-ui\dist` 同步进 JAR 内嵌静态资源（`BOOT-INF/classes/static/`）并做一致性校验；外部 `frontend\` 目录是运行时热更新覆盖层（`WebMvcConfig` 透传 `spring.web.resources.static-locations`）。
