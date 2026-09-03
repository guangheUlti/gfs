@echo off
setlocal EnableDelayedExpansion

:: ============================================================================
::  GFS - one-time initialisation
::  Creates a private MySQL data directory inside the package, sets the root
::  password, creates the schema and imports sql\init.sql.
::  Safe to re-run: it exits early once the data directory is initialised.
::
::  Usage:  init.bat [/silent]
::          /silent  no pause, non-interactive (start.bat uses this)
::
::  Ports and credentials come from bin\env.bat. Override them for one run with:
::          set MYSQL_PORT=3307 && bin\init.bat
:: ============================================================================

set "SILENT=%~1"
if defined GFS_QUIET set "SILENT=1"
set "ENV=%~dp0env.bat"

call "%ENV%"
call "%ENV%" ensure_configs
call "%ENV%" check_binaries || goto :fail

echo ============================================
echo  GFS initialization
echo ============================================
echo  MySQL home   : %MYSQL_HOME%
echo  MySQL data   : %DATA_DIR%\mysql
echo  MySQL port   : %MYSQL_PORT%
echo  Database     : %MYSQL_DB%
echo  Redis config : %REDIS_CONF%
echo.

if exist "%DB_INIT_FLAG%" (
    echo [SKIP] Database already initialised.
    echo        Delete data\mysql and data\mysql\.gfs-initialized to start over.
    goto :done
)

:: --- refuse to run against a port something else already owns --------------
call "%ENV%" port_pid %MYSQL_PORT%
if defined GFS_PID (
    echo [ERROR] Port %MYSQL_PORT% is already in use by PID %GFS_PID%.
    echo         Stop that MySQL instance first, or pick another port:
    echo             set MYSQL_PORT=3307 ^&^& bin\init.bat
    goto :fail
)

:: --- create the private data directory (bootstrap with empty password) -----
echo [1/4] Initialising MySQL data directory ...
"%MYSQLD_EXE%" %MYSQLD_ARGS% --initialize-insecure
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] mysqld --initialize-insecure failed. See %LOG_DIR%\mysql.log
    goto :fail
)

:: --- boot the server temporarily -------------------------------------------
echo [2/4] Starting MySQL for setup ...
start "GFS MySQL (setup)" /min "%MYSQLD_EXE%" %MYSQLD_ARGS%
call "%ENV%" wait_port %MYSQL_PORT% 60
if not "!GFS_OK!"=="1" (
    echo [ERROR] MySQL did not come up within 60s. See %LOG_DIR%\mysql.log
    goto :fail
)
set "SETUP_PID=%GFS_PID%"

:: --- set the root password and create the database -------------------------
echo [3/4] Setting root password, creating database '%MYSQL_DB%' ...
"%MYSQL_CLI%" -u root --protocol=TCP -P %MYSQL_PORT% -e "ALTER USER 'root'@'localhost' IDENTIFIED BY '%MYSQL_PASSWORD%'; FLUSH PRIVILEGES;"
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Could not set the MySQL root password.
    goto :fail
)
"%MYSQL_CLI%" -u %MYSQL_USER% -p%MYSQL_PASSWORD% --protocol=TCP -P %MYSQL_PORT% -e "CREATE DATABASE IF NOT EXISTS `%MYSQL_DB%` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;"
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Could not create database %MYSQL_DB%.
    goto :fail
)

:: --- import schema + seed data ---------------------------------------------
echo [4/4] Importing %SQL_DIR%\init.sql ...
"%MYSQL_CLI%" -u %MYSQL_USER% -p%MYSQL_PASSWORD% --protocol=TCP -P %MYSQL_PORT% %MYSQL_DB% < "%SQL_DIR%\init.sql"
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] SQL import failed.
    goto :fail
)

:: --- stop the temporary server ---------------------------------------------
:: The next start.bat must not race this process: the port is released before
:: mysqld lets go of the InnoDB file locks.
"%MYSQLADMIN%" -u %MYSQL_USER% -p%MYSQL_PASSWORD% -h 127.0.0.1 --protocol=TCP -P %MYSQL_PORT% shutdown >nul 2>&1
call "%ENV%" wait_pid_free %SETUP_PID% 60
if not "!GFS_OK!"=="1" taskkill /F /PID %SETUP_PID% >nul 2>&1
call "%ENV%" wait_port_free %MYSQL_PORT% 15 >nul
echo initialized> "%DB_INIT_FLAG%"

echo.
echo ============================================
echo  Initialization complete.
echo  Admin login : admin / admin
if not defined SILENT echo  Next step   : bin\start.bat
echo ============================================
goto :finish

:fail
if defined SILENT exit /b 1
echo.
echo Initialization aborted - fix the problem above and run init.bat again.
pause
exit /b 1

:done
if defined SILENT exit /b 0

:finish
if not defined SILENT pause
exit /b 0
