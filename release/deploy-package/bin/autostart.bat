@echo off
setlocal EnableDelayedExpansion

:: ============================================================================
::  GFS - boot time entry point
::
::  This is what the scheduled task created by install-service.bat runs. It is
::  start.bat with the interactive parts removed, and everything it prints is
::  appended to logs\autostart.log - at boot time there is no console to read.
::
::  You can run it by hand to test exactly what Windows will do on the next
::  reboot:      bin\autostart.bat   then   type logs\autostart.log
:: ============================================================================

set "GFS_QUIET=1"
call "%~dp0env.bat"
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

echo ==================================================================== >> "%LOG_DIR%\autostart.log"
echo  autostart invoked %date% %time% >> "%LOG_DIR%\autostart.log"
echo ==================================================================== >> "%LOG_DIR%\autostart.log"

call "%~dp0start.bat" >> "%LOG_DIR%\autostart.log" 2>&1
set "RC=%ERRORLEVEL%"

echo  autostart finished with exit code %RC% >> "%LOG_DIR%\autostart.log"
exit /b %RC%
