@echo off
set CHROME_PATH=C:\Users\46201\AppData\Local\Google\Chrome\Application\chrome.exe
set DEBUG_DIR=C:\chrome-debug-taobao

if not exist "%DEBUG_DIR%" mkdir "%DEBUG_DIR%"

start "Chrome-Taobao-Debug" "%CHROME_PATH%" --remote-debugging-port=9224 --user-data-dir="%DEBUG_DIR%" --no-first-run

echo Chrome 已启动，调试端口: 9224
echo 用户数据目录: %DEBUG_DIR%
pause
