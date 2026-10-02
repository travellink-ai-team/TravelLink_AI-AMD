@echo off
REM Start the TravelLinkAI backend proxy if it is not already listening.
REM Used by the "TravelLinkAI Proxy Autostart" scheduled task (at logon) and
REM can also be double-clicked. Safe to run repeatedly: it exits if port 3001
REM already has a listener.
REM
REM ASCII ONLY + CRLF - see the note in crawler/run-food-crawl.bat.
setlocal
set "PROXY_DIR=C:\Users\USER\Desktop\UIUX\server"
set "LOG_DIR=%PROXY_DIR%\logs"
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"
netstat -ano | findstr ":3001" | findstr "LISTENING" >nul
if not errorlevel 1 (
  echo [%date% %time%] already listening on 3001, skip>> "%LOG_DIR%\autostart.log"
  exit /b 0
)
cd /d "%PROXY_DIR%"
echo [%date% %time%] starting node server.js>> "%LOG_DIR%\autostart.log"
REM Redirect stdout/stderr to files: without this a crash at boot leaves no trace.
start "" /B node server.js >> "%LOG_DIR%\proxy.out.log" 2>> "%LOG_DIR%\proxy.err.log"
endlocal
