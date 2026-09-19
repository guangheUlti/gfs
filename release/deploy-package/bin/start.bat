@echo off
setlocal EnableDelayedExpansion

:: watch mode entry: launched from the app section below into its own window so
:: the app can restart itself (see run-watchdog / watchdog_core).
if /i "%~1"=="run-watchdog" goto :run_watchdog

:: ============================================================================
::  GFS - start Redis + MySQL + application with one command
::
::  Usage:  start.bat [port] [fg]
::          port   override the HTTP port (default 80)
::          fg     keep the application in this console (Ctrl+C stops it)
::
::  First run performs database initialisation automatically.
:: ============================================================================

set "ENV=%~dp0env.bat"
if not "%~1"=="" set "SERVER_PORT=%~1"
if /i "%~2"=="fg" set "FOREGROUND=1"

call "%ENV%"
call "%ENV%" ensure_configs
call "%ENV%" check_binaries || goto :fail

pushd "%PKG_ROOT%"

echo ============================================
echo  GFS starting
echo ============================================
echo  HTTP    : %SERVER_PORT%
echo  MySQL   : %MYSQL_PORT%   (data: data\mysql)
echo  Redis   : %REDIS_PORT%
echo  Logs    : logs\
echo.

:: --- first run: initialise the database ------------------------------------
if not exist "%DB_INIT_FLAG%" (
    echo [setup] Database not initialised yet - running init ...
    call "%~dp0init.bat" /silent || goto :fail
    echo.
)

:: --- Redis ------------------------------------------------------------------
call "%ENV%" port_pid %REDIS_PORT%
if defined GFS_PID (
    echo [redis ] already running - port %REDIS_PORT%, PID %GFS_PID%
) else (
    echo [redis ] starting ...
    start "GFS Redis (%REDIS_PORT%)" /min "%REDIS_SERVER%" %REDIS_ARGS%
    call "%ENV%" wait_port %REDIS_PORT% 20
    if not "!GFS_OK!"=="1" (
        echo [redis ] FAILED to start. See logs\redis.log
        goto :fail
    )
    echo [redis ] up on port %REDIS_PORT%
)

:: --- MySQL ------------------------------------------------------------------
call "%ENV%" port_pid %MYSQL_PORT%
if defined GFS_PID (
    echo [mysql ] already running - port %MYSQL_PORT%, PID %GFS_PID%
) else (
    echo [mysql ] starting ...
    start "GFS MySQL (%MYSQL_PORT%)" /min "%MYSQLD_EXE%" %MYSQLD_ARGS%
    call "%ENV%" wait_port %MYSQL_PORT% 60
    if not "!GFS_OK!"=="1" (
        echo [mysql ] FAILED to start. See logs\mysql.log
        goto :fail
    )
    echo [mysql ] up on port %MYSQL_PORT%
)

:: SERVER_PORT, MYSQL_*, REDIS_*, GFS_HOME and GFS_DATA_DIR were exported by
:: env.bat; conf\application-prod.yml reads them through ${VAR:default}
:: placeholders, and java inherits them from this shell.

:: --- application ------------------------------------------------------------
call "%ENV%" port_pid %SERVER_PORT%
if defined GFS_PID (
    echo [app   ] already running - port %SERVER_PORT%, PID %GFS_PID%
    goto :summary
)

echo [app   ] starting ...
if defined FOREGROUND goto :run_fg

start "GFS App (%SERVER_PORT%)" /min "%COMSPEC%" /c ""%~dp0start.bat" run-watchdog %SERVER_PORT%"
call "%ENV%" wait_port %SERVER_PORT% 120
if not "!GFS_OK!"=="1" (
    echo [app   ] did not answer within 120s - check logs\gfs.log
    goto :fail
)
echo [app   ] up on port %SERVER_PORT%
goto :summary

:run_fg
echo [app   ] running in foreground - press Ctrl+C to stop.
call :watchdog_core
set "_rc=%ERRORLEVEL%"
popd
exit /b %_rc%

:: --- watchdog ----------------------------------------------------------------
:: Own window (run-watchdog) that launches the app and re-launches it whenever
:: conf\jvm-restart.flag appears, so a config change takes effect on restart.
:: Reads the -Xmx from jvm-xmx.conf (MB); when empty falls back to %JAVA_OPTS%.
:run_watchdog
if not "%~2"=="" set "SERVER_PORT=%~2"
set "ENV=%~dp0env.bat"
call "%ENV%"
call "%ENV%" ensure_configs
pushd "%PKG_ROOT%"
call :watchdog_core
set "_rc=%ERRORLEVEL%"
popd
echo [app   ] watchdog exits
exit /b %_rc%

:watchdog_core
:watchdog_loop
:: consume any flag left from a previous cycle, so a normal stop is never seen as a restart request
if exist "%GFS_JVM_FLAG%" del /q "%GFS_JVM_FLAG%" >nul 2>&1
call "%ENV%" read_xmx "%GFS_JVM_CONF%" 2>nul
if defined JVM_MEM_OPTS (set "APP_OPTS=%JVM_MEM_OPTS%") else (set "APP_OPTS=%JAVA_OPTS%")
echo [app   ] %time% starting - memory opts: %APP_OPTS%
"%JAVA_EXE%" %APP_OPTS% -jar "%JAR_FILE%" %SPRING_ARGS%
set "_code=%ERRORLEVEL%"
echo [app   ] %time% exited with code %_code%
if exist "%GFS_JVM_FLAG%" goto :watchdog_loop
exit /b %_code%

:summary
echo.
echo ============================================
echo  GFS is up:  %FS_FRONTEND_DOMAIN%
echo  Admin login : admin / admin
echo.
echo  Stop everything with:  bin\stop.bat
echo  Show status with:      bin\status.bat
echo ============================================
popd
exit /b 0

:fail
echo.
echo Start aborted - see the messages above and the files in logs\
popd
if not defined GFS_QUIET pause
exit /b 1
