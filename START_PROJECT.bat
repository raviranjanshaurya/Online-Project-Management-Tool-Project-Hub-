@echo off
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0AUTO_SETUP.ps1"
if errorlevel 1 (echo. & echo Setup failed. & pause)
