@echo off
setlocal EnableDelayedExpansion

:: ============================================================================
::  GFS - show what is running
::  Usage: status.bat
:: ============================================================================

set "ENV=%~dp0env.bat"
call "%ENV%"

echo ============================================
echo  GFS status
echo ============================================
echo  package root : %PKG_ROOT%
echo.

echo  -- bundled runtimes --
set "JDK_VER=missing"
if exist "%JAVA_EXE%" (
    "%JAVA_EXE%" -version > "%GFS_CAPFILE%" 2>&1
    call "%ENV%" read_cap
    if defined CAP set "JDK_VER=!CAP!"
)
echo   jdk    : !JDK_VER!
echo   mysql  : %MYSQL_HOME%
echo   redis  : %REDIS_HOME%
if not exist "%JAR_FILE%" echo   [!] lib\fs-admin.jar is missing
echo.

echo  -- processes --
call "%ENV%" port_pid %SERVER_PORT%
if defined GFS_PID (echo   app     : RUNNING  port %SERVER_PORT%  PID %GFS_PID%) else echo   app     : stopped  port %SERVER_PORT%
call "%ENV%" port_pid %MYSQL_PORT%
if defined GFS_PID (echo   mysql   : RUNNING  port %MYSQL_PORT%  PID %GFS_PID%) else echo   mysql   : stopped  port %MYSQL_PORT%
call "%ENV%" port_pid %REDIS_PORT%
if defined GFS_PID (echo   redis   : RUNNING  port %REDIS_PORT%  PID %GFS_PID%) else echo   redis   : stopped  port %REDIS_PORT%
echo.

echo  -- data --
if exist "%DB_INIT_FLAG%" (echo   database : initialised ^(%MYSQL_DB%^)) else echo   database : NOT initialised - run bin\init.bat
if exist "%DATA_DIR%\upload" (echo   uploads   : %DATA_DIR%\upload) else echo   uploads   : not created yet
echo.

echo  -- boot auto start --
schtasks /query /tn "%TASK_NAME%" >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo   task '%TASK_NAME%' : not installed - run bin\install-service.bat
) else (
    set "TASK_STATE="
    for /f "delims=" %%a in ('powershell -NoProfile -Command "(Get-ScheduledTask -TaskName '%TASK_NAME%').State" 2^>nul') do if not "%%a"=="" set "TASK_STATE=%%a"
    echo   task '%TASK_NAME%' : installed - !TASK_STATE! - runs at every boot
)
echo.
echo  logs: %LOG_DIR%
echo ============================================
if not defined GFS_QUIET pause
:: schtasks leaves ERRORLEVEL=1 when no boot task is installed, and echo never
:: clears it - report success explicitly so callers do not read a status as a failure.
exit /b 0
