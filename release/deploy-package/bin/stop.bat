@echo off
setlocal EnableDelayedExpansion

:: ============================================================================
::  GFS - stop the application, then Redis, then MySQL
::
::  Usage: stop.bat [/force]
::         /force  skip the graceful waits and kill whatever is left
::
::  Only the processes listening on this package's ports are touched - other
::  Java, MySQL or Redis instances on the machine are left alone.
:: ============================================================================

set "ENV=%~dp0env.bat"
set "FORCE="
if /i "%~1"=="/force" set "FORCE=1"

call "%ENV%"

:: --- application ------------------------------------------------------------
call "%ENV%" port_pid %SERVER_PORT%
if defined GFS_PID (
    echo [app   ] stopping PID %GFS_PID% ...
    taskkill /PID %GFS_PID% >nul 2>&1
    if defined FORCE (
        taskkill /F /PID %GFS_PID% >nul 2>&1
    ) else (
        call "%ENV%" wait_port_free %SERVER_PORT% 30
        if not "!GFS_OK!"=="1" (
            echo [app   ] still listening after 30s - forcing it down
            taskkill /F /PID %GFS_PID% >nul 2>&1
        )
    )
    echo [app   ] stopped
) else (
    echo [app   ] not running on port %SERVER_PORT%
)

:: --- Redis ------------------------------------------------------------------
call "%ENV%" port_pid %REDIS_PORT%
if defined GFS_PID (
    echo [redis ] stopping PID %GFS_PID% ...
    if defined FORCE (
        taskkill /F /PID %GFS_PID% >nul 2>&1
    ) else (
        "%REDIS_CLI%" -h 127.0.0.1 -p %REDIS_PORT% shutdown >nul 2>&1
        call "%ENV%" wait_pid_free %GFS_PID% 15
        if not "!GFS_OK!"=="1" (
            echo [redis ] still running after 15s - forcing it down
            taskkill /F /PID %GFS_PID% >nul 2>&1
        )
    )
    echo [redis ] stopped
) else (
    echo [redis ] not running on port %REDIS_PORT%
)

:: --- MySQL ------------------------------------------------------------------
call "%ENV%" port_pid %MYSQL_PORT%
if defined GFS_PID (
    echo [mysql ] stopping PID %GFS_PID% ...
    if defined FORCE (
        taskkill /F /PID %GFS_PID% >nul 2>&1
    ) else (
        "%MYSQLADMIN%" -u %MYSQL_USER% -p%MYSQL_PASSWORD% -h 127.0.0.1 --protocol=TCP -P %MYSQL_PORT% shutdown >nul 2>&1
        call "%ENV%" wait_pid_free %GFS_PID% 60
        if not "!GFS_OK!"=="1" (
            echo [mysql ] still running after 60s - forcing it down
            taskkill /F /PID %GFS_PID% >nul 2>&1
        )
    )
    call "%ENV%" wait_port_free %MYSQL_PORT% 15
    echo [mysql ] stopped
) else (
    echo [mysql ] not running on port %MYSQL_PORT%
)

echo.
echo All GFS services stopped.
if not defined GFS_QUIET pause
exit /b 0
