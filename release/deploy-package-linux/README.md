# GFS 部署包（Linux）

光何文件管理系统（GFS）—— 开箱即用的 Linux 服务器部署包。

应用本体、JDK 21、MySQL 8.4、Valkey（Redis 协议兼容的缓存服务）全部内置在本目录中。
解压后执行 `bin/start.sh` 即可 —— 无需安装 Java / MySQL / Redis，也无需其他配置。

## 目录结构

```
gfs-<版本>/
├── bin/
│   ├── env.sh              # 唯一配置入口：端口、密码、目录都在这改（被其他脚本 source）
│   ├── init.sh             # 初始化 data/mysql、设置密码、导入 sql/init.sql
│   ├── start.sh            # 依次启动缓存服务 + MySQL + 应用（[port] [fg]）
│   ├── stop.sh             # 全部停止（[/force]）
│   ├── status.sh           # 查看运行状态：端口、PID、初始化情况、systemd 服务
│   ├── verify.sh           # 启动前/后的完整性、依赖与健康检查
│   ├── install-service.sh  # 注册 systemd 服务并立即启动（需 root）
│   ├── uninstall-service.sh# 移除 systemd 服务（需 root，不动数据）
│   ├── service-run.sh      # systemd 实际执行的守护脚本（内部使用）
│   └── app-watchdog.sh     # JVM 看门狗：内存配置变更后自动重启（内部使用）
├── lib/
│   ├── fs-admin.jar        # Spring Boot 应用
│   └── runtime/            # 内置运行时归档，首次启动解压到 lib/{jdk,mysql,redis}
│       ├── jdk.tar.gz      # Temurin OpenJDK 21（x86_64）
│       ├── mysql.tar.xz    # MySQL Community 8.4 minimal（glibc2.28）
│       ├── valkey.tar.gz   # Valkey 9.x（Redis 协议兼容，官方预编译）
│       └── SOURCES.txt     # 三个归档的官方下载地址与 SHA256
├── conf/
│   ├── application.yml     # profile 选择（prod）
│   ├── application-prod.yml# 从环境变量读取 MYSQL_*/REDIS_*/SERVER_PORT
│   ├── my.cnf              # 首次运行时由 env.sh 生成
│   └── redis.conf          # 首次运行时由 env.sh 生成
├── frontend/               # 前端构建产物，由应用在 / 路径下直接提供服务
├── sql/init.sql            # 表结构 + 初始数据（admin 账号、权限）
├── data/                   # 首次运行自动创建：mysql、redis
├── storage/                # 首次运行自动创建：上传文件根目录（GFS_STORAGE_DIR）
└── logs/                   # 首次运行自动创建：gfs.log、mysql.log、redis.log 等
```

首次启动会把 `lib/runtime/` 下的三个归档解压成 `lib/jdk`、`lib/mysql`、`lib/redis`
（约 1.5 GB），因此所在分区建议预留 **5 GB 以上**空闲空间。归档保留原样，是为了让
MySQL 自带的符号链接和执行权限在解压时原样保留。

## 环境要求

* **x86_64** Linux，systemd。
* **glibc ≥ 2.34、OpenSSL 3**、`libsystemd`。经验证的系统：Ubuntu 22.04+、Debian 12+、
  RHEL / Rocky / AlmaLinux 9+。**不支持** CentOS 7/8、RHEL 8、Ubuntu 20.04（内置缓存
  服务的官方二进制基线）。
* 系统包：`libaio`、`libnuma`（MySQL 依赖）、`xz`（解压 MySQL 归档）、`tar`。
  `bin/verify.sh` 会逐项检查并给出对应的 apt / dnf 安装命令。
* 应用、MySQL、缓存服务各自的 TCP 端口未被占用（默认 80 / 3306 / 6379）。
* `bin/install-service.sh` / `bin/uninstall-service.sh` 需要 root；`start.sh` 在默认
  80 端口下也需要 root（低于 1024 的端口需要特权）。

## 快速开始

```bash
tar -xzf gfs-<版本>-linux-x64.tar.gz
cd gfs-<版本>

bin/verify.sh     # 可选：检查包内容、系统依赖、端口与磁盘
bin/start.sh      # 首次运行会自动解压运行时并初始化数据库
```

然后打开 `http://<服务器地址>`，使用 `admin` / `admin` 登录。
运行 `bin/stop.sh` 停止全部服务。

如需开机自启，用 root 执行一次：

```bash
sudo bin/install-service.sh
```

## 脚本说明

| 脚本 | 作用 |
|--------|--------------|
| `start.sh [port] [fg]` | 依次启动缓存服务、MySQL、应用。port 参数可临时覆盖 `SERVER_PORT`；`fg` 让应用留在当前终端运行（Ctrl+C 停止）。已在监听的组件会自动跳过，可放心重复执行。 |
| `stop.sh [/force]` | 按 `env.sh` 中的端口找到对应 PID 并停止应用、缓存服务、MySQL。每个进程都会等待其真正退出（缓存服务和 MySQL 按 PID 而不仅是端口判断），只有拒不退出的才强制结束，避免下次启动与仍占用数据文件的 `mysqld` 冲突。`/force` 跳过等待。**只处理本包目录下的进程**，不会误杀同机其他 Java / MySQL / Redis 实例。 |
| `init.sh` | 一次性初始化：创建 `data/mysql`、设置 MySQL root 密码、建库并导入 `sql/init.sql`。已完成则直接跳过。 |
| `status.sh` | 显示内置运行时版本、各组件 PID 与端口、数据库是否已初始化、systemd 服务是否安装。 |
| `verify.sh` | 清点包内文件与版本、检查 glibc / OpenSSL / libaio / libnuma、端口占用、磁盘空间，并实测缓存 PING、MySQL 查询、HTTP `GET /`。建议在启动前后各跑一次。 |
| `install-service.sh` | 注册 systemd 服务 `gfs.service` 并立即启动整套服务。 |
| `uninstall-service.sh` | 停止并移除 systemd 服务；不影响任何数据。 |
| `env.sh` | 不直接运行 —— 其他所有脚本都以 `. env.sh` 开头。 |

## 配置

所有可调项集中在 `bin/env.sh` 顶部：

| 变量 | 默认值 | 用途 |
|----------|---------|----------|
| `SERVER_PORT` | `80` | API + 界面的 HTTP 端口（WebDAV `/dav`、OSS `/oss` 复用） |
| `MYSQL_HOST` `MYSQL_PORT` | `127.0.0.1` `3306` | 应用连接 MySQL 的地址 |
| `MYSQL_DB` `MYSQL_USER` `MYSQL_PASSWORD` | `gfs` `root` `root` | 数据库名、账号、密码 |
| `REDIS_HOST` `REDIS_PORT` `REDIS_PASSWORD` | `127.0.0.1` `6379`（空） | 缓存 / 会话存储 |
| `DATA_DIR` | `<包目录>/data` | MySQL 数据、缓存持久化 |
| `LOG_DIR` | `<包目录>/logs` | 全部日志 |
| `INNODB_POOL` | `256M` | `innodb_buffer_pool_size` |
| `JAVA_OPTS` | `-Xms512m -Xmx1024m` | JVM 内存参数 |
| `FS_FRONTEND_DOMAIN` | `http://localhost:<port>` | 应用对外生成的绝对链接前缀 |
| `GFS_SERVICE_NAME` | `gfs` | systemd 服务名（`install-service.sh` 使用） |

直接改文件即可，也可以在单次运行时从命令行临时覆盖 —— 除此之外不需要改任何
地方，配置文件本身永远不用手动编辑：

```bash
SERVER_PORT=9090 MYSQL_PASSWORD=choose-one DATA_DIR=/data/gfs bin/start.sh
```

`conf/my.cnf` 和 `conf/redis.conf` 在首次需要时由上述变量生成，之后不再重新
生成，因此手工修改的内容在重启后依然有效；删除这两个文件即可强制重新生成。
`conf/application-prod.yml` 通过 `${VAR:default}` 占位符读取 `env.sh` 导出的
环境变量，所以在 `env.sh` 改一处，MySQL、缓存、JDBC 和应用会同时生效。

MySQL 与缓存服务都只监听 `127.0.0.1`，不对外暴露端口。

前端由应用自身从 `frontend/` 目录提供服务 —— 该目录排在
`spring.web.resources.static-locations` 的第一位，新版前端构建产物直接覆盖
进去即可。

## 安装为系统服务

`install-service.sh` 会写入 `/etc/systemd/system/gfs.service`（服务名可用
`GFS_SERVICE_NAME` 覆盖），内容等价于：

```ini
[Unit]
Description=GFS file management service
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory="<包目录>"
ExecStart="<包目录>/bin/service-run.sh"
Restart=on-failure
RestartSec=5
TimeoutStopSec=120
KillMode=control-group
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
```

一个 unit 管三个进程：`service-run.sh` 拉起缓存服务与 MySQL 后在前台运行应用
看门狗，三者同属一个 control group —— `systemctl stop` 会把它们一起优雅关闭。
应用崩溃时 unit 自动重启；后台修改 JVM 最大内存时，看门狗按新值重启 JVM，
MySQL 与缓存保持运行。

```bash
systemctl status gfs            # 状态
journalctl -u gfs -f            # 服务日志（应用自身日志在 logs/gfs.log）
systemctl restart gfs           # 重启
sudo bin/uninstall-service.sh   # 移除服务（数据保留）
```

从手动启动切换为服务时，`install-service.sh` 会先停掉本包手动启动的实例；
如果端口被**其他程序**占用则直接报错退出，不会强行抢占。

**服务装好后不要再手动执行 `bin/stop.sh`**——它检测到该 unit 处于运行状态会
直接拒绝并提示改用 `systemctl stop`，否则 systemd 会立刻把刚停掉的进程重新
拉起。重复执行 `install-service.sh` 是安全的：它会走 `systemctl stop` 停掉
旧单元，再写入并重启。

## 数据、日志与备份

GFS 写入的所有内容都在 `data/`（`mysql/` 数据库、`redis/` 持久化）与
`storage/`（上传的文件，即 `GFS_STORAGE_DIR`）下。用 `bin/stop.sh`（或
`systemctl stop gfs`）停掉服务后，复制 `data/`、`storage/` 和 `conf/`
三个目录就是一份完整备份。删除 `data/` 等于重置系统，下次启动时会重新
初始化数据库。

升级时替换 `lib/fs-admin.jar` 与 `frontend/` 即可，`data/`、`storage/`、
`conf/` 和 `lib/runtime/` 无需改动。

## 关于内置缓存服务

Redis 官方不提供 Linux 预编译二进制（只有源码和 Docker 镜像），因此本包内置
**Valkey 9.x**（Linux 基金会维护的 Redis 分支，协议与配置文件完全兼容，官方
发布 x86_64 预编译包），脚本按其实际文件名调用。应用只通过 TCP 使用标准
Redis 协议，无感知差异。

如果你希望换成 Redis 官方二进制，把 `lib/redis/` 换成包含
`bin/redis-server`、`bin/redis-cli` 的目录即可（脚本会自动优先使用
`valkey-server`，没有时回退到 `redis-server`）。

## 前端与深链接

应用自身直接托管 `frontend/`（`conf/application-prod.yml` 中的
`spring.web.resources.static-locations` 指向该目录），因此不需要 nginx。
前端路由使用 history 模式，`GlobalExceptionHandler` 会把没有对应接口的浏览器
页面请求（如 `/login`、`/w/main/storage`）转发到 `index.html`，交由前端路由
接管 —— 直接输入、收藏、刷新深链接都能正常打开。其余请求保持原有行为：
`/apis/**` 及带文件扩展名的路径在匹配不到时仍返回 JSON 格式的
`404 资源未找到`。

## 故障排查

* **`[mysql ] FAILED to start`** —— 查看 `logs/mysql.log`。常见原因是
  `libaio.so.1` / `libnuma.so.1` 缺失（`bin/verify.sh` 会提示安装命令），
  或端口被占用（`bin/status.sh` 可以看到是谁占着端口）。
* **`[redis ] FAILED to start`** —— 查看 `logs/redis.log`。内置缓存需要
  glibc ≥ 2.34 与 OpenSSL 3，旧系统（CentOS 7/8、Ubuntu 20.04）无法运行。
* **`Access denied for user 'root'`** —— `env.sh` 里的 MySQL 密码与
  `data/mysql` 中实际密码不一致。要么改回正确的变量值，要么删除 `data/mysql`
  后重新执行 `bin/init.sh`（会清空数据库）。
* **应用有响应但浏览器白屏** —— `frontend/` 缺失；运行 `bin/verify.sh`
  查看 `GET /` 是否返回 200。
* **服务起不来** —— `journalctl -u gfs -n 100 --no-pager`；再对照
  `logs/gfs.log`、`logs/mysql.log`、`logs/redis.log`。
* **端口被其他程序占用** —— 本包不会绑定已被占用的端口，请用上面的变量
  把自己的端口改掉（systemd 安装后需重新执行 `install-service.sh`）。
* **SELinux（RHEL 系）** —— 若服务被拒绝写文件，检查
  `ausearch -m avc -ts recent`；可将本目录放到 `/opt` 下或按需调整策略。

## 许可

© 2026 GFS Project · Powered by @guangheUlti
