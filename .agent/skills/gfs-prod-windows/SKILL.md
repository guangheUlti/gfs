---
name: gfs-prod-windows
description: 生产 Windows 服务器 172.19.10.153 的连接与运维：SMB 文件读写、DCOM 远程执行、RDP、部署路径、启停与排障
---

# GFS 生产 Windows 运维（172.19.10.153）

本文所有主机事实均为 2026-11-14 实测结果；环境变化后请重新核对，不要把本文当永久事实。

## 一、主机

| 项 | 实测值 |
|---|---|
| 地址 | `172.19.10.153`（内网，无公网域名） |
| 系统 | Windows Server 2019 Datacenter，`10.0.17763` |
| 账号 | `administrator`（口令见「二、凭据」） |
| 开放端口 | 80（应用）、3389（RDP）、445（SMB）、135（RPC）、22（SSH，2026-11-14 启用）、5986（WinRM HTTPS，2026-11-14 启用） |
| 关闭端口 | 5985（WinRM HTTP，明文监听已删除，防火墙规则已禁用） |
| 上次开机 | 2026-10-25 10:05 |
| D 盘 | 787 GB 总容量，约 770 GB 可用 |

## 二、凭据（绝不入库）

解析顺序（`prod-153.ps1` 已实现）：

1. 环境变量 `GFS_PROD_USER` / `GFS_PROD_PASS`
2. `%USERPROFILE%\.gfs\prod-153.cred`，内容为两行：`user=...` / `password=...`（已在本机创建，ACL 仅当前用户可读写）

禁止写入：仓库内任何文件（含 `.agent/`、`doc/`）、提交信息、日志、截图、Issue/讨论。只有 RDP 手动登录时才由人输入。

**Git Bash 传参坑**：`powershell -Command "...$var..."` 双引号里的 `$` 会被 bash 吞掉，导致命令静默变形。远程操作逻辑一律落到 `.ps1` 再 `-File` 执行，或整段用单引号。

## 三、三条通道

### 1. SMB 管理员共享（文件读写，最常用）

`\\172.19.10.153\C$`、`D$` 可用，需管理员凭据：

```powershell
$cred = New-Object System.Management.Automation.PSCredential(
    'administrator', (ConvertTo-SecureString $env:GFS_PROD_PASS -AsPlainText -Force))
New-PSDrive -Name PD -PSProvider FileSystem -Root '\\172.19.10.153\D$' -Credential $cred -Scope Global
Get-ChildItem 'PD:\application\gfs\logs'
```

用途：读日志、取配置、替换 `lib\fs-admin.jar`、替换 `frontend\`。**生产一律不备份**（2026-11-14 用户要求）：改动前确认目标与版本，回滚靠重新构建历史版本再部署。

### 2. DCOM / CIM（远程执行命令，无需 WinRM）

已实测可用，Windows Server 2019 上走 DCOM（135 + 动态 RPC）：

```powershell
$opt  = New-CimSessionOption -Protocol Dcom
$s    = New-CimSession -ComputerName 172.19.10.153 -Credential $cred -SessionOption $opt
Invoke-CimMethod -CimSession $s -ClassName Win32_Process -MethodName Create `
    -Arguments @{ CommandLine = 'cmd.exe /c C:\Windows\Temp\probe.bat' }
```

`prod-153.ps1 exec` 已封装：上传临时 bat → Win32_Process 启动 → 轮询输出文件 → 返回退出码 → 清理。**不要**为了省事去启用 WinRM 或装 psexec。

### 3. SSH（2026-11-14 启用，推荐日常使用）

- 版本：`OpenSSH_for_Windows_9.5p1`，装在 `C:\Program Files\OpenSSH`（官方 MSI `v9.5.0.0p1-Beta` 安装）
- 服务：`sshd`，Automatic / Running；配置 `C:\ProgramData\ssh\sshd_config`（生产不保留备份文件）
- 已设置：`PasswordAuthentication yes`、`DefaultShell = powershell.exe`
- 防火墙：`GFS-OpenSSH-In-TCP`，仅 `172.19.0.0/16` + 127.0.0.1 可连 TCP 22；MSI 自带的 Any 规则已禁用

```bash
ssh administrator@172.19.10.153
scp -r fs-ui/dist/* administrator@172.19.10.153:"D:/application/gfs/frontend/"
```

自动化场景没有 sshpass/setsid 时，用 `SSH_ASKPASS` 脚本读 `GFS_PROD_PASS`（脚本用完即删，不要提交）。

### 4. WinRM（仅 HTTPS）

- 监听：`5986`（HTTPS），自签证书 `CN=172.19.10.153`，指纹 `A0BE94181ADA07AB2AB1A2A6BAB293313512A67A`（5 年有效）
- 认证：`Basic` 仅在 TLS 上启用；`AllowUnencrypted=false`；**HTTP 监听已删除，5985 关闭**
- 防火墙：`GFS-WinRM-HTTPS-In-TCP`，仅 `172.19.0.0/16`；系统自带 `Windows Remote Management` 规则已禁用

```powershell
$cred = New-Object System.Management.Automation.PSCredential('administrator', (ConvertTo-SecureString $env:GFS_PROD_PASS -AsPlainText -Force))
$opt  = New-PSSessionOption -SkipCACheck -SkipCNCheck -SkipRevocationCheck
$s = New-PSSession -ComputerName 172.19.10.153 -Port 5986 -UseSSL -Authentication Basic -Credential $cred -SessionOption $opt
Invoke-Command -Session $s { hostname; Get-Service sshd, WinRM }
```

### 5. RDP（图形界面 / 需要交互式会话的操作）

```
mstsc /v:172.19.10.153
```

口令不写进 `.rdp` 文件。提示：经 DCOM 启动的进程落在非交互会话（session 0），功能正常但与历史进程的会话号不同；若要求完全一致（例如依赖桌面会话的映射盘），用 RDP 登录后执行 `bin\start.bat`。

## 四、部署拓扑

包根：`D:\application\gfs`（自解压发行包，非系统服务）

| 目录 | 内容 |
|---|---|
| `bin\` | `start.bat` / `stop.bat` / `status.bat` / `verify.bat` / `init.bat` / `autostart.bat` / `env.bat` / `install-service.bat` |
| `conf\` | `application-prod.yml`、`application.yml`、`my.ini`、`redis.conf`、可选 `jvm-xmx.conf`、`jvm-restart.flag` |
| `data\` | `mysql\`（库文件）、`redis\`、`upload\` |
| `logs\` | `gfs.YYYY-MM-DD.log`、`mysql.log`；装了自启才有 `autostart.log`；Redis 日志在 `data\redis\redis.log` |
| `lib\` | `jdk\`、`mysql\`、`redis\`、`fs-admin.jar`（约 109 MB） |
| `storage\` | 用户文件根目录（不要随意删除/覆盖） |
| `frontend\` | 外部前端产物，`static-locations` 第一条；换前端不必重打 JAR |
| `sql\` | 初始化脚本 |

运行态（实测）：

- 应用：`java.exe`，`-Xms512m -Xmx1024m -jar lib\fs-admin.jar --server.port=80`，工作目录即包根
- MySQL：`lib\mysql\bin\mysqld.exe`，父 PID 6892 / 子 PID 5568，数据目录 `data\mysql`，端口 3306
- Redis：`lib\redis\redis-server.exe`，PID 6596，`conf\redis.conf`，端口 6379
- 默认连接串：MySQL `root/root`，Redis 无密码且只绑定 `127.0.0.1`（`env.bat` 可改）
- 生产端口只有 80；`2000` 是本地开发端口，这里没有

**同机其他组件，不要动**：`D:\application\nginx`、`D:\application\alist`（均未运行）；`D:\software\穿透\service.exe` 注册的服务 `guanghePierced`（LocalSystem / Auto / 当前 Stopped）；`D:\datacenter\mysql-8.0` 的系统服务 `MySQL`（Manual / 当前 Stopped，GFS 未使用）。

## 五、启停与重启

- `bin\stop.bat`：按 app → Redis → MySQL 顺序停，只处理本包端口上的进程
- `bin\start.bat`：拉起 Redis/MySQL，再以 watchdog 方式启动应用；`conf\jvm-restart.flag` 出现时 watchdog 自动重启应用
- **当前没有 `GFS-Autostart` 计划任务，也没有 `logs\autostart.log`**：机器重启后不会自启，需要手工 `bin\start.bat`，或先执行 `bin\install-service.bat` 装自启
- 重启会中断进行中的上传/下载与预览；操作前先确认无活跃任务，避开业务高峰

### 交互式启动（重启后窗口留任务栏）

DCOM/WinRM 启动的进程落在 session 0，用户看不到窗口。要在用户桌面会话（本机为 session 2，`quser` 里 administrator 常是 Discarded/已断开）里保留最小化的 `GFS App (80)` watchdog 窗口，用 `/IT` 计划任务代跑 `start.bat`：

```powershell
schtasks /create /f /tn GFS-Deploy-Start /tr 'cmd.exe /c D:\application\gfs\bin\start.bat' `
  /sc once /st 00:00 /ru administrator /rp <口令> /it /rl HIGHEST
schtasks /run /tn GFS-Deploy-Start
# 等端口 80 + 日志 Started FsAdminApplication，然后删除任务
schtasks /delete /tn GFS-Deploy-Start /f
```

验证：`Get-Process java` 的 `SessionId=2`；`Win32_Process` 里有一条 session 2 的 `cmd.exe /c "..\start.bat" run-watchdog 80`。从 session 0 读 `MainWindowTitle` 恒为空，属正常，不要据此判断窗口没出来。`/create` 报「/ST 早于当前时间」只是提醒，任务仍能 `/run`。

### 远程部署（jar + 前端 + conf，2026-11-14 实跑验证）

```bash
powershell -ExecutionPolicy Bypass -File .agent\scripts\prod-deploy.ps1 `
  -Jar fs-admin\target\fs-admin.jar -Frontend fs-ui\dist `
  -Conf .agent\tmp\deploy-5.0.19\conf -Sql sql\mysql\gfs.sql -Yes
```

脚本链路：SMB 上传到 `C:\Windows\Temp\gfs-deploy` 并校验 SHA256 → **只停 80 端口上的应用** → 替换 jar/frontend、按需覆盖 conf/sql（`init.sql` 只复制不执行）→ `/IT` 计划任务启动并等端口 + `Started FsAdminApplication` → 校验 JAR hash / SessionId / HTTP 200 → 删任务、清暂存。MySQL/Redis 全程不动。**不创建任何备份**。

- 前置：先比一次库结构（见下节），新版本缺列会表现为笼统「操作失败」。
- 顺序原则：**任何可能失败的原生命令（schtasks 等）要么放在文件替换前，要么包在 `ErrorActionPreference='Continue'` 里**；2026-11-14 首次执行就是在文件已替换、应用已停后卡在 `schtasks /delete`。
- 回滚：生产不保留备份，需重新构建并部署上一版本。
- `bin\*.bat` 正常不随版本变化；确需更新时单独替换，不要整包覆盖。

### 部署更新与库结构（2026-11-14 补充）

只替换 `lib\fs-admin.jar` + `frontend\` 的前提是**库结构已同步**。本项目没有 Flyway，`sql\` 只有初始化脚本，新版本新增的列不会自动出现在线上库。

典型症状：接口只返回笼统的「删除失败/操作失败」，日志里是 `Unknown column 'xxx' in 'field list'`，事务整体回滚。2026-11-14 批量删除全部失败就是这个原因（新 JAR 需要 `file_transfer_task.replace_file_id`，线上库缺列）。

排查与修复：

```sql
-- 线上列清单（在服务器上用包内 mysql 客户端执行）
SELECT CONCAT(TABLE_NAME,'.',COLUMN_NAME) FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE() ORDER BY 1;
```

把结果与仓库 `sql/mysql/gfs.sql` 的 `CREATE TABLE` 列逐项比对。补列优先用 `ALGORITHM=INSTANT`（MySQL 8.0.29+ 支持任意位置），大表先看行数，避免长时间 DDL 锁表。

**OSS 主密钥**：2026-11-14 已在本机用 `setx /M OSS_AES_KEY <32 位>` 固化（存注册表，不进仓库），启动不再生成随机密钥；更换密钥后已签发的 OSS Secret Key 需重新签发。当前生产只启用 Local 存储。

## 六、助手脚本

```bash
S=.agent/scripts/prod-153.ps1
powershell -ExecutionPolicy Bypass -File $S status                      # 端口/HTTP/Java PID/启动时间
powershell -ExecutionPolicy Bypass -File $S ls   "D:\application\gfs\logs"
powershell -ExecutionPolicy Bypass -File $S get  "D:\application\gfs\logs\gfs.2026-11-14.0.log" ".agent/tmp/"
powershell -ExecutionPolicy Bypass -File $S logs -Tail 120              # 今日日志尾部，默认 120 行
powershell -ExecutionPolicy Bypass -File $S exec "bin\status.bat"
powershell -ExecutionPolicy Bypass -File $S put  "fs-admin\target\fs-admin.jar" "D:\application\gfs\lib\fs-admin.jar"
powershell -ExecutionPolicy Bypass -File $S deploy-jar "fs-admin\target\fs-admin.jar" -Yes
powershell -ExecutionPolicy Bypass -File $S deploy-frontend "fs-ui\dist" -Yes
powershell -ExecutionPolicy Bypass -File $S restart -Yes
# 完整版部署（只停应用 + 交互式重启 + 校验 + 清理，不备份）
powershell -ExecutionPolicy Bypass -File .agent\scripts\prod-deploy.ps1 -Jar fs-admin\target\fs-admin.jar -Frontend fs-ui\dist -Yes
```

- `prod-deploy.ps1` 是 2026-11-14 生产 5.0.18→5.0.19 的实跑链路，远端部分 `prod-deploy-remote.ps1` 必须保持纯 ASCII（见坑 14）。

- `exec` 在包根目录执行，输出与退出码原样返回；子 bat 的 `pause` 已被 `GFS_QUIET=1` 抑制。
- `restart` / `deploy-*` 必须显式加 `-Yes`，防止误操作。
- 输出中的源路径形如 `GFS_D:\application\gfs\...` 是 PowerShell 命名 PSDrive 的正常表现，实际指向 `\\172.19.10.153\D$`。

## 七、安全与风险规则（强制）

1. **默认只读**：查看、排障不需要写权限；任何改文件、重启、删数据先向用户说明并获得确认（用户当次明确授权除外）。
2. **生产不保留任何备份**（用户要求，2026-11-14）：不建 `gfs-backup`、不留 `.bak`；回滚 = 重新构建历史版本再部署。部署前务必确认 jar 版本、库结构已同步。
3. 不删除、不覆盖 `data\` 与 `storage\`；不同步生产数据到本地。
4. 不在业务高峰重启；重启前用 `bin\status.bat` 和日志确认无进行中任务。
5. 远程执行不留计划任务、不留自启项；临时文件只放 `C:\Windows\Temp\gfs-agent-*` 并清理。
6. SSH / WinRM 已按最小暴露启用（仅 `172.19.0.0/16`、WinRM 仅 HTTPS）。不得自行放宽防火墙范围、重开 5985 或改回口令明文传输；需要时先取得用户同意。
7. 不碰同机 nginx、alist、`guanghePierced`、系统 MySQL 服务。
8. 命令输出里出现口令、token、Cookie 时不要回显到聊天或写入文件。

## 八、已知坑

1. **批处理不返回**：在 bat 里直接调用另一个 bat 会替换当前脚本，后续行不执行；必须 `cmd /c "xxx.bat"`（脚本已按此实现）。
2. **pause 卡死**：`status.bat` / `stop.bat` 末尾有 `pause`，远程执行前必须 `set GFS_QUIET=1`（脚本已注入）。
3. **GBK 输出**：远程 cmd/PowerShell 输出是 GBK，直接抓取会乱码；PowerShell 里先 `[Console]::OutputEncoding = [Text.Encoding]::GetEncoding(936)`。
4. **80 返回 200 ≠ 健康**：SPA fallback 会把未知路径回退到 `index.html`；判断启动成功看日志 `Started FsAdminApplication` 或 PID/端口。
5. **生成配置只在缺失时创建**：`conf\my.ini`、`conf\redis.conf` 手改后 `ensure_configs` 不会覆盖；要恢复默认必须先删除文件。
6. **jar 被占用**：应用运行时无法覆盖 `lib\fs-admin.jar`，先 `bin\stop.bat` 再替换。
7. **前端残留**：替换 `frontend\` 要先清空旧 hash 资源，否则旧文件仍可被访问；`index.html` 必须在目录根部。
8. **日志体积与命名**：2026-11-14 起按天 + 按大小滚动：`gfs.<date>.<序号>.log`（单文件 200 MB、总量上限 10 GB、保留 30 天）。旧命名 `gfs.<date>.log` 是历史文件、不再写入，排障时取 `logs` 下最新修改的那个。此前单日可达 GB 级（2026-11-05 为 2.45 GB），先看大小再整份下载，优先 `logs` 取尾部。
9. **MySQL 双进程**：Windows 下 mysqld 会有一个父进程 + 一个子进程，属正常；`stop.bat` 按端口杀进程即可。
10. **OpenSSH 组件装不上**：`Add-WindowsCapability -Online -Name OpenSSH.Server~~~~0.0.1.0` 在这台机器上报「拒绝访问」（本机 FoD 为 NotPresent）。改用官方 MSI（`PowerShell/Win32-OpenSSH` 的 `OpenSSH-Win64-v9.5.0.0.msi`）经 SMB 上传后 `msiexec /i ... /quiet /norestart`，再配 `sshd_config` 与防火墙。
11. **WinRM 本地账号认证**：`administrator` 是本地账号，走 Kerberos/Negotiate 不成立；必须开 `Basic`，而 `AllowUnencrypted=false` 时 Basic 只能走 HTTPS。不要为了省事开启 5985 明文。
12. **新 JAR + 旧库**：只换 jar 不补 DDL 会出现 `Unknown column`，接口只回笼统失败文案，容易误判成业务 bug。部署后先比一次列，见「部署更新与库结构」。
13. **原生命令 stderr 中断脚本**：PS 5.1 里 `$ErrorActionPreference='Stop'` 时，`schtasks /delete` 查询不到任务写 stderr 会变成终止错误（`2>$null` 也压不住），脚本会在替换完文件后停住、应用起不来。**先停应用、再执行会失败的步骤**不可取：把 schtasks 等原生命令包在 `$ErrorActionPreference='Continue'` 里并检查 `$LASTEXITCODE`。
14. **远端脚本必须纯 ASCII**：`Invoke-Command -FilePath` 传过去的内容在 PS 5.1 按 ANSI 解析，含中文（UTF-8 无 BOM）会吞引号、报「字符串缺少终止符」。远端脚本里不要写中文注释/匹配串。

## 九、验证清单

```bash
S=.agent/scripts/prod-153.ps1
powershell -ExecutionPolicy Bypass -File $S status          # AppHttpStatus=200，JavaPid 与 Started 合理
powershell -ExecutionPolicy Bypass -File $S exec "bin\status.bat"   # app/mysql/redis 均 RUNNING
powershell -ExecutionPolicy Bypass -File $S logs -Tail 50   # 无持续报错，能看到正常请求
```

`bin\status.bat` 里显示 `task 'GFS-Autostart' : not installed` 是当前真实状态，不是脚本错误。

远程通道自检：

```bash
ssh -o ConnectTimeout=8 administrator@172.19.10.153 "hostname"   # 期望 FFSServer
powershell -NoProfile -Command 'foreach($p in 22,5985,5986){ $c=New-Object System.Net.Sockets.TcpClient; $null=$c.BeginConnect("172.19.10.153",$p,$null,$null).AsyncWaitHandle.WaitOne(3000); "$p $($c.Connected)"; $c.Close() }'
# 期望：22 True / 5985 False / 5986 True
```
