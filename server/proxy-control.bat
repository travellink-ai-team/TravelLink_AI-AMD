@echo off
REM TravelLinkAI proxy control panel (double-click to open)
REM Chinese comments are deliberately avoided: cmd.exe reads .bat in the OEM
REM codepage (950 here), so UTF-8 text turns into garbage and breaks parsing.
powershell -NoProfile -ExecutionPolicy Bypass -STA -File "%~dp0proxy-control.ps1"
