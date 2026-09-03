@echo off
setlocal EnableDelayedExpansion

:: ============================================================================
::  GFS - remove the boot time auto start
::
::  Usage:  uninstall-service.bat
::
::  Deletes the scheduled task registered by install-service.bat. The running
::  stack is left alone on purpose - stop it with bin\stop.bat once you are
::  sure nothing else on this machine uses those ports.
::
::  Data is never touched: data\mysql, data\redis and data\upload stay where
::  they are. Delete them by hand only if you really want a clean slate.
:: ============================================================================

set "ENV=%~dp0env.bat"
call "%ENV%"
call "%ENV%" require_admin || goto :fail

schtasks /query /tn "%TASK_NAME%" >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [SKIP] Task '%TASK_NAME%' is not installed.
    goto :done
)

echo Removing scheduled task '%TASK_NAME%' ...
schtasks /delete /tn "%TASK_NAME%" /f
if %ERRORLEVEL% NEQ 0 goto :fail
echo [OK] Removed. It will no longer start at boot.

:done
echo.
echo  If the stack is still running you can stop it with:  bin\stop.bat
echo  Status of everything:                                 bin\status.bat
pause
exit /b 0

:fail
echo.
echo [ERROR] Could not remove the task. Run this script from an elevated prompt.
pause
exit /b 1
