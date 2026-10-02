@echo off
chcp 65001 >nul
REM WanderAI restaurant cache crawler (run biweekly by Windows Task Scheduler).
REM
REM ASCII ONLY - do not put Chinese in this file. cmd.exe tracks its position in
REM a .bat by BYTE offset; with chcp 65001 active, multi-byte characters make it
REM lose sync and swallow the beginning of a later line. That is what silently
REM broke the "call crawler-key.local.bat" line below for every scheduled run,
REM so the crawler fell back to the browser-restricted key in weather.env.js.
REM
REM Key: prefer crawler-key.local.bat (crawler-only, no referrer restriction,
REM gitignored). Falling back to ..\weather.env.js gets 403 server-side.
set "FIREBASE_SERVICE_ACCOUNT_PATH=C:\Users\USER\Desktop\UIUX\crawler\serviceAccount.json"
cd /d "C:\Users\USER\Desktop\UIUX\crawler"
if exist "crawler-key.local.bat" call "crawler-key.local.bat"
if not defined GOOGLE_MAPS_API_KEY echo [%date% %time%] WARNING: crawler key not loaded>> "crawl-food.log"
echo [%date% %time%] crawl:food start>> "crawl-food.log"
node worker.js --crawl-food >> "crawl-food.log" 2>&1
echo [%date% %time%] crawl:food done (exit %errorlevel%)>> "crawl-food.log"
