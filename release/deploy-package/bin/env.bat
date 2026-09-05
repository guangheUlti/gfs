@echo off

:: ============================================================================
::  GFS - shared environment + helpers
::  Called by every script in bin\ :  call "%~dp0env.bat"
::
::  This is the ONLY place to change ports, credentials and folder locations.
::  Values may also be pre-set in the calling shell, e.g.
::      set MYSQL_PORT=3307 && call bin\start.bat
::      set DATA_DIR=E:\gfs-data && call bin\start.bat
::
::  Set GFS_QUIET=1 to suppress every "press a key" pause (boot task, CI).
::    autostart.bat does exactly that.
::
::  Advanced usage - run a helper instead of plain setup:
::      call env.bat ensure_configs
::      call env.bat port_pid   3306      :: -> %GFS_PID%
::      call env.bat wait_port  3306 60   :: -> %GFS_OK%=0/1
::      call env.bat wait_port_free 3306 60
::      call env.bat wait_pid_free  4128 60   :: -> %GFS_OK%=1 when PID 4128 exited
::      call env.bat read_cap       :: %GFS_CAPFILE% first line -> %CAP%
::      call env.bat require_admin
:: ============================================================================

set "SCRIPT_DIR=%~dp0"
for %%i in ("%SCRIPT_DIR%..") do set "PKG_ROOT=%%~fi"

set "LIB_DIR=%PKG_ROOT%\lib"
set "CONF_DIR=%PKG_ROOT%\conf"
set "SQL_DIR=%PKG_ROOT%\sql"
if not defined DATA_DIR set "DATA_DIR=%PKG_ROOT%\data"
if not defined LOG_DIR  set "LOG_DIR=%PKG_ROOT%\logs"

set "JAVA_HOME=%LIB_DIR%\jdk"
set "MYSQL_HOME=%LIB_DIR%\mysql"
set "REDIS_HOME=%LIB_DIR%\redis"
set "JAR_FILE=%LIB_DIR%\fs-admin.jar"
set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
set "MYSQLD_EXE=%MYSQL_HOME%\bin\mysqld.exe"
set "MYSQL_CLI=%MYSQL_HOME%\bin\mysql.exe"
set "MYSQLADMIN=%MYSQL_HOME%\bin\mysqladmin.exe"
set "REDIS_SERVER=%REDIS_HOME%\redis-server.exe"
set "REDIS_CLI=%REDIS_HOME%\redis-cli.exe"
set "MY_INI=%CONF_DIR%\my.ini"
set "REDIS_CONF=%CONF_DIR%\redis.conf"
set "DB_INIT_FLAG=%DATA_DIR%\mysql\.gfs-initialized"
:: scratch file scripts use to capture one line of command output (read_cap)
set "GFS_CAPFILE=%TEMP%\gfs-capture.tmp"

:: exported for conf\application-prod.yml, which resolves ${GFS_HOME},
:: ${GFS_DATA_DIR} and ${GFS_STORAGE_DIR} instead of relying on the JVM's
:: working directory.
set "GFS_HOME=%PKG_ROOT%"
set "GFS_DATA_DIR=%DATA_DIR%"
:: Upload storage root - kept separate from DATA_DIR so bundled MySQL/Redis
:: runtime data never mixes with user files.
set "STORAGE_DIR=%PKG_ROOT%\storage"
set "GFS_STORAGE_DIR=%STORAGE_DIR%"

:: ---- tunables -------------------------------------------------------------
if not defined SERVER_PORT    set "SERVER_PORT=80"
if not defined MYSQL_HOST     set "MYSQL_HOST=127.0.0.1"
if not defined MYSQL_PORT     set "MYSQL_PORT=3306"
if not defined MYSQL_DB       set "MYSQL_DB=gfs"
if not defined MYSQL_USER     set "MYSQL_USER=root"
if not defined MYSQL_PASSWORD set "MYSQL_PASSWORD=root"
if not defined REDIS_HOST     set "REDIS_HOST=127.0.0.1"
if not defined REDIS_PORT     set "REDIS_PORT=6379"
if not defined REDIS_PASSWORD set "REDIS_PASSWORD="
if not defined INNODB_POOL    set "INNODB_POOL=256M"
if not defined JAVA_OPTS      set "JAVA_OPTS=-Xms512m -Xmx1024m"
if not defined FS_FRONTEND_DOMAIN set "FS_FRONTEND_DOMAIN=http://localhost:%SERVER_PORT%"

:: ---- boot auto start -------------------------------------------------------
:: One Task Scheduler task starts the whole stack at boot (see autostart.bat).
:: A task rather than three SCM services because neither java.exe nor the
:: bundled redis-server.exe is a service binary - wrapping them would need an
:: extra download on the target server. Override by pre-setting TASK_NAME.
if not defined TASK_NAME set "TASK_NAME=GFS-Autostart"

:: ---- runtime arguments shared by start.bat and the autostart task --------
:: MySQL takes absolute paths (a relative datadir would be joined onto basedir).
:: The bundled Redis is an MSYS2 binary: a Windows path passed as an argument
:: gets rewritten to /D:/... and rejected, so its config file is named relative
:: to %PKG_ROOT% - which is why every script pushd's there. Inside that file
:: forward slashes are fine, so the data dir below is absolute and Redis no
:: longer depends on where it was started from.
set "REDIS_DATA=%DATA_DIR:\=/%/redis"
set "MYSQLD_ARGS=--defaults-file="%MY_INI%" --basedir="%MYSQL_HOME%" --datadir="%DATA_DIR%\mysql" --port=%MYSQL_PORT% --log-error="%LOG_DIR%\mysql.log""
set "REDIS_ARGS=conf/redis.conf --port %REDIS_PORT%"

:: spring boot property overrides (config placeholders resolve from these env
:: vars, so no JDBC url has to be rebuilt on the command line)
set "SPRING_ARGS=--spring.config.additional-location="file:conf/application.yml,file:conf/application-prod.yml" --server.port=%SERVER_PORT% --fs.frontend.domain=%FS_FRONTEND_DOMAIN%"

if "%~1"=="" goto :eof
if /i "%~1"=="ensure_configs" call :ensure_configs & goto :eof
if /i "%~1"=="port_pid"        call :port_pid %2     & goto :eof
if /i "%~1"=="wait_port"       call :wait_port %2 %3 & goto :eof
if /i "%~1"=="wait_port_free"  call :wait_port_free %2 %3 & goto :eof
if /i "%~1"=="wait_pid_free"   call :wait_pid_free %2 %3 & goto :eof
if /i "%~1"=="require_admin"   call :require_admin   & goto :eof
if /i "%~1"=="check_binaries"  call :check_binaries  & goto :eof
if /i "%~1"=="read_cap"        call :read_cap        & goto :eof
echo [ERROR] env.bat: unknown helper "%~1"
exit /b 2

:: ----------------------------------------------------------------------------
:ensure_configs
:: (Re)generate conf\my.ini and conf\redis.conf. They are machine generated,
:: but only when missing - hand edits survive until you delete them.
if not exist "%CONF_DIR%" mkdir "%CONF_DIR%"
if not exist "%DATA_DIR%\mysql" mkdir "%DATA_DIR%\mysql"
if not exist "%DATA_DIR%\redis" mkdir "%DATA_DIR%\redis"
if not exist "%STORAGE_DIR%" mkdir "%STORAGE_DIR%"
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

if not exist "%MY_INI%" (
    echo Generating %MY_INI%
    (
        echo # AUTO-GENERATED by bin\env.bat - delete this file to regenerate.
        echo # port / basedir / datadir / log-error are supplied on the command
        echo # line by the scripts in bin\, so they are intentionally absent here.
        echo [mysqld]
        echo character-set-server=utf8mb4
        echo collation-server=utf8mb4_general_ci
        echo default-time-zone='+08:00'
        echo default_storage_engine=InnoDB
        echo max_connections=300
        echo max_connect_errors=1000
        echo innodb_buffer_pool_size=%INNODB_POOL%
        echo innodb_flush_log_at_trx_commit=2
        echo slow_query_log=0
        echo loose-mysqlx=OFF
        echo sql_mode='STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION'
        echo.
        echo [mysql]
        echo default-character-set=utf8mb4
        echo.
        echo [client]
        echo default-character-set=utf8mb4
    ) > "%MY_INI%"
)

if not exist "%REDIS_CONF%" (
    echo Generating %REDIS_CONF%
    (
        echo # AUTO-GENERATED by bin\env.bat - delete this file to regenerate.
        echo # Only this file has to be named relative to the package root, because
        echo # the bundled MSYS2 build rejects an absolute config path on the command
        echo # line. Everything inside it is absolute - forward slashes only. Redis
        echo # chdirs into "dir" before opening "logfile", hence the bare name below,
        echo # which therefore lands in the data directory.
        echo port %REDIS_PORT%
        echo bind 127.0.0.1 -::1
        echo protected-mode yes
        echo daemonize no
        echo databases 16
        echo dir %REDIS_DATA%
        echo logfile redis.log
        echo save 900 1
        echo save 300 10
        echo save 60 10000
        echo appendonly no
        echo maxmemory-policy noeviction
        if defined REDIS_PASSWORD if not "%REDIS_PASSWORD%"=="" echo requirepass %REDIS_PASSWORD%
    ) > "%REDIS_CONF%"
)
goto :eof

:: ----------------------------------------------------------------------------
:: %~1 = tcp port ; result in %GFS_PID% (empty when nothing is listening)
:port_pid
set "GFS_PID="
for /f "tokens=5" %%a in ('netstat -ano ^| findstr /R /C:":%~1[ ]" ^| findstr /I "LISTENING"') do set "GFS_PID=%%a"
goto :eof

:: ----------------------------------------------------------------------------
:: %~1 = tcp port, %~2 = timeout in seconds ; result in %GFS_OK% (1 up / 0 down)
:wait_port
set "GFS_OK=0"
set "_tries=0"
:wait_port_loop
call :port_pid %~1
if defined GFS_PID set "GFS_OK=1" & goto :eof
set /a _tries+=1
if %_tries% GEQ %~2 goto :eof
ping -n 2 127.0.0.1 >nul
goto wait_port_loop

:: ----------------------------------------------------------------------------
:: %~1 = tcp port, %~2 = timeout in seconds ; %GFS_OK%=1 once the port is free
:wait_port_free
set "GFS_OK=0"
set "_tries=0"
:wait_port_free_loop
call :port_pid %~1
if not defined GFS_PID set "GFS_OK=1" & goto :eof
set /a _tries+=1
if %_tries% GEQ %~2 goto :eof
ping -n 2 127.0.0.1 >nul
goto wait_port_free_loop

:: ----------------------------------------------------------------------------
:: %~1 = pid, %~2 = timeout in seconds ; %GFS_OK%=1 once the process has exited
:: A port can look free while mysqld still holds the InnoDB file locks, so the
:: scripts that may start it again right away wait on the PID instead.
:wait_pid_free
set "GFS_OK=0"
set "_tries=0"
:wait_pid_free_loop
set "_alive=0"
for /f "tokens=2 delims=," %%a in ('tasklist /fi "PID eq %~1" /nh /fo csv 2^>nul') do if "%%~a"=="%~1" set "_alive=1"
if "%_alive%"=="0" set "GFS_OK=1" & goto :eof
set /a _tries+=1
if %_tries% GEQ %~2 goto :eof
ping -n 2 127.0.0.1 >nul
goto wait_pid_free_loop

:: ----------------------------------------------------------------------------
:require_admin
net session >nul 2>&1
if %ERRORLEVEL% EQU 0 goto :require_admin_ok
:: net session also fails when the LanmanServer service is stopped, so ask for
:: a second administrator-only call before reporting.
fsutil dirty query %SystemDrive% >nul 2>&1
if %ERRORLEVEL% EQU 0 goto :require_admin_ok
echo [ERROR] Administrator privileges are required for this operation.
echo         Right-click the script and choose "Run as administrator".
exit /b 1
:require_admin_ok
set "GFS_ADMIN=1"
goto :eof

:: ----------------------------------------------------------------------------
:check_binaries
set "GFS_MISSING=0"
if not exist "%JAVA_EXE%" (
    echo    missing  lib\jdk\bin\java.exe
    set "GFS_MISSING=1"
)
if not exist "%MYSQLD_EXE%" (
    echo    missing  lib\mysql\bin\mysqld.exe
    set "GFS_MISSING=1"
)
if not exist "%REDIS_SERVER%" (
    echo    missing  lib\redis\redis-server.exe
    set "GFS_MISSING=1"
)
if not exist "%JAR_FILE%" (
    echo    missing  lib\fs-admin.jar
    set "GFS_MISSING=1"
)
if "%GFS_MISSING%"=="1" (
    echo [ERROR] The deployment package is incomplete.
    echo         Expected these folders under %LIB_DIR% :  jdk, mysql, redis
    exit /b 1
)
goto :eof

:: ----------------------------------------------------------------------------
:: reads the first line of %GFS_CAPFILE% into %CAP% and deletes the file
:: Scripts redirect a command there instead of capturing it with for /f: for /f
:: hands its command to cmd /c inside another pair of quotes, cmd strips those,
:: and a command line that carries several quoted tokens comes back empty.
:read_cap
set "CAP="
if not exist "%GFS_CAPFILE%" goto :eof
for /f "usebackq delims=" %%a in ("%GFS_CAPFILE%") do if not defined CAP set "CAP=%%a"
del "%GFS_CAPFILE%" >nul 2>&1
goto :eof
