@echo off
setlocal EnableDelayedExpansion

:: ============================================================================
::  GFS - pre-flight and health check
::
::  Usage:  verify.bat
::
::  Run it before the first start - it reports whether the package is complete,
::  how much room the drive has and whether the three ports are free - and run
::  it afterwards to have Redis pinged, MySQL queried and the home page fetched
::  over HTTP. It starts nothing and writes nothing but generated config files
::  and one scratch file under %TEMP%.
:: ============================================================================

set "ENV=%~dp0env.bat"
call "%ENV%"
call "%ENV%" ensure_configs

set "PASS=0"
set "WARN=0"
set "FAIL=0"

echo ============================================================
echo  GFS verification
echo ============================================================
echo  package root : %PKG_ROOT%
echo  ports        : app %SERVER_PORT% / mysql %MYSQL_PORT% / redis %REDIS_PORT%
echo.

:: ----------------------------------------------------------------------------
echo  -- package contents --
call :check_file "%JAVA_EXE%"          "lib\jdk\bin\java.exe"
call :check_file "%MYSQLD_EXE%"        "lib\mysql\bin\mysqld.exe"
call :check_file "%MYSQL_CLI%"         "lib\mysql\bin\mysql.exe"
call :check_file "%REDIS_SERVER%"      "lib\redis\redis-server.exe"
call :check_file "%JAR_FILE%"          "lib\fs-admin.jar"
call :check_file "%SQL_DIR%\init.sql"  "sql\init.sql"
call :check_file "%CONF_DIR%\application.yml"      "conf\application.yml"
call :check_file "%CONF_DIR%\application-prod.yml" "conf\application-prod.yml"
call :check_file "%MY_INI%"            "conf\my.ini          - generated"
call :check_file "%REDIS_CONF%"        "conf\redis.conf      - generated"
echo.
echo  -- versions --
"%JAVA_EXE%" -version > "%GFS_CAPFILE%" 2>&1
call "%ENV%" read_cap
echo    jdk    : !CAP!
"%MYSQLD_EXE%" --version > "%GFS_CAPFILE%" 2>&1
call "%ENV%" read_cap
echo    mysql  : !CAP!
"%REDIS_SERVER%" --version > "%GFS_CAPFILE%" 2>&1
call "%ENV%" read_cap
echo    redis  : !CAP!
if exist "%DB_INIT_FLAG%" (
    echo    schema : data\mysql initialised
    set /a PASS+=1
) else (
    echo    schema : not initialised - the first start.bat run does it
    set /a WARN+=1
)
echo.

:: ----------------------------------------------------------------------------
echo  -- ports --
for %%p in (%SERVER_PORT% %MYSQL_PORT% %REDIS_PORT%) do (
    call "%ENV%" port_pid %%p
    if defined GFS_PID (
        echo    port %%p : in use by PID !GFS_PID!
        for /f "delims=" %%x in ('tasklist /fi "PID eq !GFS_PID!" /nh 2^>nul') do echo             : %%x
    ) else (
        echo    port %%p : free
    )
)
echo.

:: ----------------------------------------------------------------------------
echo  -- free space for data ^& uploads --
set "PKG_DRIVE=%PKG_ROOT:~0,1%"
set "FREE_GB="
for /f "delims=" %%a in ('powershell -NoProfile -Command "if (Get-PSDrive %PKG_DRIVE%) { [math]::Floor((Get-PSDrive %PKG_DRIVE%).Free / 1GB) }" 2^>nul') do if not "%%a"=="" set "FREE_GB=%%a"
if not defined FREE_GB (
    echo    drive %PKG_DRIVE% : could not be read - check it manually
    set /a WARN+=1
    goto :space_done
)
if %FREE_GB% LSS 5 (
    echo    drive %PKG_DRIVE% : %FREE_GB% GB free
    echo    [!] less than 5 GB - uploads and the MySQL data files will not grow
    set /a WARN+=1
) else (
    echo    drive %PKG_DRIVE% : %FREE_GB% GB free
    set /a PASS+=1
)
:space_done
echo.

:: ----------------------------------------------------------------------------
echo  -- running services --
"%REDIS_CLI%" -h 127.0.0.1 -p %REDIS_PORT% ping 2>nul | findstr /i "PONG" >nul
if %ERRORLEVEL% EQU 0 (
    echo    [OK]   redis    : replies on %REDIS_PORT%
    set /a PASS+=1
) else (
    echo    [ ]    redis    : not answering on %REDIS_PORT%
)

"%MYSQL_CLI%" -u %MYSQL_USER% -p%MYSQL_PASSWORD% -h 127.0.0.1 -P %MYSQL_PORT% --protocol=TCP -N -B -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=database()" %MYSQL_DB% > "%GFS_CAPFILE%" 2>nul
call "%ENV%" read_cap
set "MYSQL_Q=!CAP!"
if defined MYSQL_Q (
    echo    [OK]   mysql    : replies on %MYSQL_PORT%, database %MYSQL_DB% has %MYSQL_Q% tables
    set /a PASS+=1
) else (
    if exist "%DB_INIT_FLAG%" (
        echo    [ ]    mysql    : not answering on %MYSQL_PORT%
    ) else (
        echo    [ ]    mysql    : unreachable - expected before the first init
    )
)

set "HTTP="
if exist "%SystemRoot%\System32\curl.exe" (
    "%SystemRoot%\System32\curl.exe" -s -m 10 -o nul -w "%%{http_code}" http://127.0.0.1:%SERVER_PORT%/ > "%GFS_CAPFILE%" 2>nul
    call "%ENV%" read_cap
    set "HTTP=!CAP!"
)
if "!HTTP!"=="200" (
    echo    [OK]   app      : GET / on %SERVER_PORT% -^> 200
    set /a PASS+=1
) else (
    if defined HTTP (echo    [ ]    app      : GET / on %SERVER_PORT% -^> !HTTP!) else (echo    [ ]    app      : not listening on %SERVER_PORT%)
)

schtasks /query /tn "%TASK_NAME%" >nul 2>&1
if %ERRORLEVEL% EQU 0 (
    echo    [OK]   boot task: '%TASK_NAME%' registered
    set /a PASS+=1
) else (
    echo    [ ]    boot task: none - run bin\install-service.bat to add one
)
echo.

:: ----------------------------------------------------------------------------
echo ============================================================
echo  %PASS% checks passed, %WARN% to be aware of, %FAIL% missing
if %FAIL% NEQ 0 (
    echo  The package is incomplete - restore lib\ and sql\ from the archive.
) else (
    echo  Package is complete. Next: bin\start.bat   ^(then reopen this script^)
)
echo ============================================================
if not defined GFS_QUIET pause
exit /b %FAIL%

:: ============================================================================
::  %~1 = file to test, %~2 = how to label it
:check_file
if exist "%~1" (
    for %%f in ("%~1") do echo    [OK]   %~2  ^(%%~zf bytes^)
    set /a PASS+=1
) else (
    echo    [FAIL] %~2  MISSING
    set /a FAIL+=1
)
goto :eof

