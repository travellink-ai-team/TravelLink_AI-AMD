@echo off
REM Start the TravelLinkAI-AMD backend proxy if it is not already listening.
REM Serves travel-link-amd.duckdns.org (nginx /api/ -> 127.0.0.1:3011).
REM The old site (travel-link-ai, Desktop\UIUX) keeps its own proxy on 3001.
REM Safe to run repeatedly: it exits if the port already has a listener.
REM PORT must match PORT in server\.env.
REM
REM ASCII ONLY + CRLF - see the note in crawler/run-food-crawl.bat.
setlocal
set "PROXY_DIR=%~dp0"
if "%PROXY_DIR:~-1%"=="\" set "PROXY_DIR=%PROXY_DIR:~0,-1%"
set "PORT=3011"
set "LOG_DIR=%PROXY_DIR%\logs"
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"
netstat -ano | findstr ":%PORT%" | findstr "LISTENING" >nul
if not errorlevel 1 (
  echo [%date% %time%] already listening on %PORT%, skip>> "%LOG_DIR%\autostart.log"
  exit /b 0
)
cd /d "%PROXY_DIR%"
echo [%date% %time%] starting node server.js on %PORT%>> "%LOG_DIR%\autostart.log"
REM Redirect stdout/stderr to files: without this a crash at boot leaves no trace.
start "" /B node server.js >> "%LOG_DIR%\proxy.out.log" 2>> "%LOG_DIR%\proxy.err.log"
endlocal
