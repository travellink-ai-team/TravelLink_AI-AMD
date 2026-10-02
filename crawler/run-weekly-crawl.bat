@echo off
chcp 65001 >nul
REM ===========================================================================
REM WanderAI daily crawl: spread verify:places across the 7 days of the week and
REM stop inside the API budget (unprocessed items resume in the next slice).
REM   - Slice: 1/7 .. 7/7 by weekday (Sunday=1 ... Saturday=7).
REM   - Budget: at most CRAWL_MAX_CALLS Google calls per day.
REM   - After verify, regenerate poi-data.js (export:local hits no Google API).
REM Run by Windows Task Scheduler at 03:00; output goes to weekly-crawl.log.
REM
REM ASCII ONLY - see the note in run-food-crawl.bat. Chinese comments here made
REM cmd.exe lose its byte offset and eat the start of a following line.
REM ===========================================================================
set "FIREBASE_SERVICE_ACCOUNT_PATH=C:\Users\USER\Desktop\UIUX\crawler\serviceAccount.json"
cd /d "C:\Users\USER\Desktop\UIUX\crawler"
if exist "crawler-key.local.bat" call "crawler-key.local.bat"
if not defined GOOGLE_MAPS_API_KEY echo [%date% %time%] WARNING: crawler key not loaded>> "weekly-crawl.log"

REM Daily API call cap. Adjust to your free quota (see README).
if not defined CRAWL_MAX_CALLS set "CRAWL_MAX_CALLS=120"

REM Which slice runs today (Sunday=0 -> 1, ... Saturday=6 -> 7).
for /f %%i in ('powershell -NoProfile -Command "[int]((Get-Date).DayOfWeek) + 1"') do set "DOW=%%i"

echo [%date% %time%] weekly verify slice %DOW%/7 (max %CRAWL_MAX_CALLS% calls) start>> "weekly-crawl.log"
node worker.js --verify-places --slice %DOW%/7 --max-calls %CRAWL_MAX_CALLS% >> "weekly-crawl.log" 2>&1
echo [%date% %time%] verify done (exit %errorlevel%), refreshing poi-data.js>> "weekly-crawl.log"
node worker.js --export-local >> "weekly-crawl.log" 2>&1
echo [%date% %time%] weekly crawl done (exit %errorlevel%)>> "weekly-crawl.log"
