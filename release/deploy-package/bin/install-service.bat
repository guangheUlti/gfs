@echo off
setlocal EnableDelayedExpansion

:: ============================================================================
::  GFS - install / refresh the boot time auto start
::
::  Usage:  install-service.bat          run from an elevated prompt
::
::  Registers the scheduled task "%TASK_NAME%" (override it with
::  `set TASK_NAME=Other^&^&install-service.bat`), which runs
::  bin\autostart.bat every time Windows boots - before anyone logs in.
::
::  The task is the production equivalent of the scripts you test by hand:
::      install-service.bat        ->  create it
::      schtasks /run  /tn TASK    ->  start the stack now
::      bin\stop.bat               ->  stop the stack
::      uninstall-service.bat      ->  remove it
::
::  Why a task and not three SCM services: neither java.exe -jar nor the
::  bundled redis-server.exe is a service binary, so registering them with the
::  service control manager needs an extra wrapper (NSSM / WinSW) plus its own
::  runtime on an otherwise bare server. A task costs nothing, keeps one start
::  path for both modes, and is created with a single built-in command.
::
::  MySQL is the one component that COULD be a real service
::  (`lib\mysql\bin\mysqld.exe --install GFS-MySQL ...`) - see README if you
::  prefer that. Do not run both for the same data directory.
:: ============================================================================

set "ENV=%~dp0env.bat"
call "%ENV%"
call "%ENV%" require_admin || goto :fail
call "%ENV%" ensure_configs
call "%ENV%" check_binaries || goto :fail

echo ============================================
echo  GFS auto start installation
echo ============================================
echo  task name : %TASK_NAME%
echo  runs      : %SCRIPT_DIR%autostart.bat
echo  HTTP port : %SERVER_PORT%
echo ============================================
echo.

:: --- the stack has to be initialised while we still have a console ---------
if not exist "%DB_INIT_FLAG%" (
    echo [setup] First install - creating the MySQL data directory ...
    call "%~dp0init.bat" /silent || goto :fail
    echo.
)

:: --- register ---------------------------------------------------------------
:: \" is un-escaped by the C runtime before schtasks stores it, so the task
:: ends up running: cmd.exe /c "...\autostart.bat"  (path may contain spaces).
schtasks /create /f ^
    /tn "%TASK_NAME%" ^
    /tr "cmd.exe /c \"%SCRIPT_DIR%autostart.bat\"" ^
    /sc onstart ^
    /ru SYSTEM ^
    /rl HIGHEST
if %ERRORLEVEL% NEQ 0 goto :fail_create

echo.
echo [OK] Task '%TASK_NAME%' registered. It runs at every boot.
echo.
echo [....] Starting the stack now ...
schtasks /run /tn "%TASK_NAME%"
if %ERRORLEVEL% NEQ 0 (
    echo [WARN] Could not start the task immediately. Start it any time with:
    echo            schtasks /run /tn "%TASK_NAME%"
) else (
    echo [....] Waiting for the application to answer on port %SERVER_PORT% ...
    call "%ENV%" wait_port %SERVER_PORT% 150
    if not "!GFS_OK!"=="1" echo [WARN] Not answering yet - check logs\autostart.log
)

echo.
echo ============================================
echo  Installed.
echo  URL         : %FS_FRONTEND_DOMAIN%
echo  Admin login : admin / admin
echo.
echo  Start now   : schtasks /run /tn "%TASK_NAME%"
echo  Status      : bin\status.bat
echo  Stop        : bin\stop.bat
echo  Remove      : bin\uninstall-service.bat
echo  Boot log    : logs\autostart.log
echo ============================================
pause
exit /b 0

:fail_create
echo.
echo [ERROR] schtasks could not register the task.
echo         Most likely the prompt is not elevated. Try again from
echo         "Command Prompt (Administrator)", or check that the Task Scheduler
echo         service (Schedule) is running.
pause
exit /b 1

:fail
echo.
echo Installation aborted - fix the problem above and run install-service.bat again.
pause
exit /b 1
