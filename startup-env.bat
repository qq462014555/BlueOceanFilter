@echo off
title Start Environment

:: ========== CONFIG ==========
set MYSQL_EXE=D:\phpstudy_pro\Extensions\MySQL5.7.26\bin\mysqld.exe
set MYSQL_CNF=D:\phpstudy_pro\Extensions\MySQL5.7.26\my.ini
set MYSQL_PORT=3306
:: ============================

echo =============================================
echo   Start MySQL
echo =============================================
echo.

:: Check if MySQL already running
netstat -ano | find ":%MYSQL_PORT% " | find "LISTENING" >nul 2>&1
if %errorlevel%==0 (
    echo [OK] MySQL is already running.
    goto DONE
)

:: Check mysqld.exe exists
if not exist "%MYSQL_EXE%" (
    echo [ERROR] mysqld.exe not found at %MYSQL_EXE%
    exit /b 1
)

:: Start MySQL in background
echo [1/2] Starting MySQL in background...
start "MySQL" /min "%MYSQL_EXE%" --defaults-file="%MYSQL_CNF%"

:: Wait for MySQL
echo [2/2] Waiting for MySQL to start...
set COUNT=0
:WAIT_MYSQL
timeout /t 2 /nobreak >nul
set /a COUNT+=2
netstat -ano | find ":%MYSQL_PORT% " | find "LISTENING" >nul 2>&1
if %errorlevel%==0 goto MYSQL_READY
if %COUNT% GEQ 30 (
    echo [ERROR] MySQL did not start in 30s.
    exit /b 1
)
echo       Waited %COUNT%s...
goto WAIT_MYSQL

:MYSQL_READY
echo [OK] MySQL is running!

:DONE
echo.
exit /b 0
