# GFS 部署包

光何文件管理系统（GFS）—— 开箱即用的 Windows 服务器部署包。

应用本体、JDK、MySQL、Redis 全部内置在本目录中。把整个目录拷贝到服务器，
运行 `bin\start.bat` 即可 —— 无需安装任何软件，也无需其他配置。

## 目录结构

```
deploy-package/
├── bin/
│   ├── env.bat             # 唯一配置入口：端口、密码、目录、任务名都在这改
│   ├── init.bat            # 初始化 data\mysql、设置密码、导入 sql\init.sql
│   ├── start.bat           # 依次启动 Redis + MySQL + 应用（[port] [fg]）
│   ├── stop.bat            # 全部停止                    （[/force]）
│   ├── status.bat          # 查看运行状态：端口、PID、初始化情况
│   ├── verify.bat          # 启动前/后的完整性与健康检查
│   ├── autostart.bat       # 开机任务实际执行的入口
│   ├── install-service.bat # 注册开机自启任务（需管理员）
│   └── uninstall-service.bat
├── lib/
│   ├── fs-admin.jar        # Spring Boot 应用
│   ├── jdk/                # Temurin OpenJDK 21.0.12.1 LTS
│   ├── mysql/              # MySQL Community 8.4.11
│   └── redis/              # Redis 8.10.1（redis-windows 构建）
├── conf/
│   ├── application.yml     # profile 选择（prod）
│   ├── application-prod.yml# 从环境变量读取 MYSQL_*/REDIS_*/SERVER_PORT
│   ├── my.ini              # 首次运行时由 env.bat 生成
│   └── redis.conf          # 首次运行时由 env.bat 生成
├── frontend/               # 前端构建产物，由应用在 / 路径下直接提供服务
├── sql/init.sql            # 表结构 + 初始数据（admin 账号、权限）
├── data/                   # 首次运行自动创建：mysql、redis
├── storage/                # 首次运行自动创建：上传文件根目录（GFS_STORAGE_DIR）
└── logs/                   # 首次运行自动创建：gfs*.log、mysql.log、redis.log
```

`lib/` 约 640 MB，整个部署包约 650 MB；同一磁盘分区还需预留几个 GB 空闲空间
用于存放上传文件和数据库文件。

## 环境要求

* 64 位 Windows（Server 2016+ 或 Windows 10+）。
* 无需预装 Java、MySQL、Redis 或 Visual C++ 运行库 —— `mysqld.exe` 依赖的
  CRT DLL 已复制到 `lib\mysql\bin`，`java.exe` 自带所需运行库。
* 应用、MySQL、Redis 各自的 TCP 端口未被占用。
* 仅 `install-service.bat` / `uninstall-service.bat` 需要管理员权限。

## 快速开始

```bat
bin\verify.bat         :: 可选：检查包内容是否齐全、端口是否可用
bin\start.bat          :: 首次运行会自动初始化数据库
```

然后打开 `http://<服务器地址>`，使用 `admin` / `admin` 登录。
运行 `bin\stop.bat` 停止全部服务。

如需开机自动启动，在**管理员权限**的命令行中执行一次：

```bat
bin\install-service.bat
```

## 脚本说明

| 脚本 | 作用 |
|--------|--------------|
| `start.bat [port] [fg]` | 依次启动 Redis、MySQL、应用。port 参数可临时覆盖 `SERVER_PORT`；`fg` 让应用留在当前控制台运行。已在监听的组件会自动跳过，可放心重复执行。 |
| `stop.bat [/force]` | 按 `env.bat` 中的端口找到对应 PID 并停止应用、Redis、MySQL。每个进程都会等待其真正退出（Redis 和 MySQL 按 PID 而不仅是端口判断），只有拒不退出的才强制结束，避免下次 `start.bat` 与仍占用数据文件的 `mysqld` 冲突。`/force` 跳过等待。不会误杀无关的 Java/MySQL/Redis 实例。 |
| `init.bat` | 一次性初始化：创建 `data\mysql`、设置 MySQL root 密码、建库并导入 `sql\init.sql`。已完成则直接跳过。 |
| `status.bat` | 显示内置运行时版本、各组件 PID 与端口、数据库是否已初始化、开机任务是否存在。 |
| `verify.bat` | 清点包内文件、版本号、端口占用、磁盘剩余空间，并做实测：Redis `PING`、MySQL 查询、HTTP `GET /`。建议在 `start.bat` 前后各跑一次。 |
| `install-service.bat` | 注册开机任务并立即启动整套服务。 |
| `uninstall-service.bat` | 移除开机任务；不影响正在运行的服务和任何数据。 |
| `env.bat` | 不直接运行 —— 其他所有脚本都以 `call "%~dp0env.bat"` 开头。 |

## 配置

所有可调项集中在 `bin\env.bat` 顶部：

| 变量 | 默认值 | 用途 |
|----------|---------|----------|
| `SERVER_PORT` | `80` | API + 界面的 HTTP 端口 |
| `MYSQL_HOST` `MYSQL_PORT` | `127.0.0.1` `3306` | 应用连接 MySQL 的地址 |
| `MYSQL_DB` `MYSQL_USER` `MYSQL_PASSWORD` | `gfs` `root` `root` | 数据库名、账号、密码 |
| `REDIS_HOST` `REDIS_PORT` `REDIS_PASSWORD` | `127.0.0.1` `6379`（空） | 缓存 / 会话存储 |
| `DATA_DIR` | `<包目录>\data` | MySQL 数据、Redis 持久化、上传文件 |
| `LOG_DIR` | `<包目录>\logs` | 全部日志 |
| `INNODB_POOL` | `256M` | `innodb_buffer_pool_size` |
| `JAVA_OPTS` | `-Xms512m -Xmx1024m` | JVM 内存参数 |
| `FS_FRONTEND_DOMAIN` | `http://localhost:<port>` | 应用对外生成的绝对链接前缀 |
| `TASK_NAME` | `GFS-Autostart` | `install-service.bat` 创建的计划任务名 |

直接改文件即可，也可以在单次运行时从命令行临时覆盖 —— 除此之外不需要改任何
地方，配置文件本身永远不用手动编辑：

```bat
set SERVER_PORT=9090
set MYSQL_PASSWORD=choose-one
set DATA_DIR=E:\gfs-data
bin\start.bat
```

`conf\my.ini` 和 `conf\redis.conf` 在首次需要时由上述变量生成，之后不再重新
生成，因此手工修改的内容在重启后依然有效；删除这两个文件即可强制重新生成。
`conf\application-prod.yml` 通过 `${VAR:default}` 占位符读取 `env.bat` 导出的
环境变量，所以在 `env.bat` 改一处，MySQL、Redis、JDBC 和应用会同时生效。

前端由应用自身从 `frontend\` 目录提供服务 —— 该目录排在
`spring.web.resources.static-locations` 的第一位，新版前端构建产物直接覆盖
进去即可。

## 开机自启

`install-service.bat` 会在任务计划程序中注册一个「系统启动时」触发的任务，
以 `SYSTEM` 身份运行 `bin\autostart.bat`，后者调用 `bin\start.bat` 并把全部
输出追加到 `logs\autostart.log`。

这里用一个计划任务而不是三个 Windows 服务，是因为 `java.exe -jar` 和内置的
`redis-server.exe` 都不是服务程序 —— 注册成服务需要额外套一层包装器
（NSSM、WinSW），在一台干净的服务器上还要多装一套东西。单个任务让手动启动
和开机自启共用同一条启动路径，且用一条系统自带命令即可完成安装。

```bat
schtasks /run /tn "GFS-Autostart"      :: 手动触发一次
bin\stop.bat                           :: 停止（任务仍保持注册状态）
```

如果你确实希望 MySQL 以真正的 Windows 服务方式运行，它是三个组件中唯一原生
支持的 —— 先停掉当前的，再执行：

```bat
lib\mysql\bin\mysqld.exe --install GFS-MySQL --defaults-file=D:\gfs\conf\my.ini ^
    --basedir=D:\gfs\lib\mysql --datadir=D:\gfs\data\mysql --port=3306 ^
    --log-error=D:\gfs\logs\mysql.log
net start GFS-MySQL
```

注意：不要把该服务和 `start.bat` 同时指向同一个 `data\mysql`。

## 数据、日志与备份

GFS 写入的所有内容都在 `data\`（`mysql\` 数据库、`redis\` 持久化 + 日志）与
`storage\`（上传的文件，即 `GFS_STORAGE_DIR`）下。用 `bin\stop.bat` 停掉服务
后，复制 `data\`、`storage\` 和 `conf\` 三个目录就是一份完整备份。删除
`data\` 等于重置系统，下次启动时 `init.bat` 会重新初始化数据库。

## 前端与深链接

应用自身直接托管 `frontend\`（`conf\application-prod.yml` 中的
`spring.web.resources.static-locations` 指向该目录），因此不需要 nginx。
前端路由使用 history 模式，`GlobalExceptionHandler` 会把没有对应接口的浏览器
页面请求（如 `/login`、`/w/main/storage`）转发到 `index.html`，交由前端路由
接管 —— 直接输入、收藏、刷新深链接都能正常打开。其余请求保持原有行为：
`/apis/**` 及带文件扩展名的路径在匹配不到时仍返回 JSON 格式的
`404 资源未找到`。

## 故障排查

* **`[mysql ] FAILED to start`** —— 查看 `logs\mysql.log`。通常是端口被占用：
  运行 `bin\status.bat` 可以看到是谁占着端口。
* **`Access denied for user 'root'`** —— `env.bat` 里的 MySQL 密码与
  `data\mysql` 中实际密码不一致。要么改回正确的变量值，要么删除 `data\mysql`
  后重新执行 `bin\init.bat`（会清空数据库）。
* **应用有响应但浏览器白屏** —— `frontend\` 缺失，或从其他工作目录启动了
  应用；运行 `bin\verify.bat` 查看 `GET /` 是否返回 200。
* **首次启动很慢** —— Windows Defender 会在首次执行时扫描 100 MB 的 JAR 和
  50 MB 的 `mysqld.exe`。
* **端口需要与其他实例共用** —— 本包不会绑定已被占用的端口，请用上面的
  变量把自己的端口改掉。

## 许可

© 2026 GFS Project · Powered by @guangheUlti
