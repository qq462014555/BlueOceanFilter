@echo off
title BlueOceanFilter Launcher

:: ========== CONFIG ==========
set PROJECT_DIR=G:\BlueOceanFilter
set JAR_FILE=%PROJECT_DIR%\target\BlueOceanFilter-1.0-SNAPSHOT.jar
set INPUT_DIR=%PROJECT_DIR%\input
set UPLOAD_SCRIPT=%PROJECT_DIR%\upload.js
set ENV_SCRIPT=%PROJECT_DIR%\startup-env.bat
:: ============================

echo =============================================
echo   BlueOceanFilter One-Click Launcher
echo =============================================
echo.

:: Step 1: Call environment startup script
echo [1/4] Starting environment...
if not exist "%ENV_SCRIPT%" (
    echo       ERROR: startup-env.bat not found at %ENV_SCRIPT%
    pause
    exit /b 1
)
call "%ENV_SCRIPT%"
if %errorlevel% neq 0 (
    echo       ERROR: Environment startup failed.
    pause
    exit /b 1
)
echo.

:: Step 2: Check JAR
echo [2/4] Checking JAR file...
if not exist "%JAR_FILE%" (
    echo       ERROR: JAR not found: %JAR_FILE%
    pause
    exit /b 1
)
echo       OK.
echo.

:: Step 3: Auto-detect Excel file
echo [3/4] Looking for Excel file in input folder...
set UPLOAD_FILE=
set UPLOAD_FILENAME=
for %%F in ("%INPUT_DIR%\*.xlsx") do (
    set UPLOAD_FILE=%%F
    set UPLOAD_FILENAME=%%~nxF
)
if not defined UPLOAD_FILE (
    echo       ERROR: No .xlsx file found in %INPUT_DIR%
    echo       Please put your Excel file in that folder.
    pause
    exit /b 1
)
echo       Found: %UPLOAD_FILENAME%
echo.

:: Step 4: Start JAR
echo [4/4] Starting BlueOceanFilter service...
start "BlueOceanFilter" cmd /k "cd /d %PROJECT_DIR% && java -jar %JAR_FILE%"
echo       Waiting for service to start (up to 60s)...
set COUNT=0
:WAIT_JAR
timeout /t 2 /nobreak >nul
set /a COUNT+=2
curl -s --max-time 2 http://localhost:8080/index.html >nul 2>&1
if %errorlevel%==0 goto JAR_READY
if %COUNT% GEQ 60 (
    echo       ERROR: Service did not start in 60s.
    echo       Check the BlueOceanFilter window for errors.
    pause
    exit /b 1
)
echo       Waited %COUNT%s...
goto WAIT_JAR

:JAR_READY
echo       Service is ready!
echo.

:: Step 5: Upload file
echo [5/5] Uploading: %UPLOAD_FILENAME%
if not exist "%UPLOAD_SCRIPT%" (
    echo       WARNING: upload.js not found
    echo       Please open: http://localhost:8080/index.html
    goto DONE
)
cd /d %PROJECT_DIR%
node upload.js "input\%UPLOAD_FILENAME%"
echo       Upload submitted!

:DONE
echo.
echo =============================================
echo   All done!
echo   Results: %PROJECT_DIR%\output\
echo   Browser: http://localhost:8080/index.html
echo =============================================
echo.
pause
