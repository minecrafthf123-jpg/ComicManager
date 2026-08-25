@echo off
setlocal
cd /d "%~dp0"

call "%~dp0gradlew.bat" assembleDebug --console=plain
if errorlevel 1 exit /b 1

where adb >nul 2>&1
if errorlevel 1 (
    echo adb was not found. Add Android SDK platform-tools to PATH and try again.
    exit /b 1
)

adb install -r -t "%~dp0app\build\outputs\apk\debug\app-debug.apk"
exit /b %ERRORLEVEL%
