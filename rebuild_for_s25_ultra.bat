@echo off
setlocal
echo ============================================================
echo  The Unbound Launcher: Rebuild from Extracted APK
echo  Target: XREAL xbx a01 / a01+ ^& Samsung Galaxy S25 Ultra
echo ============================================================

set SCRIPT_DIR=%~dp0
cd /d "%SCRIPT_DIR%"

:: Check for local portable Python in tools\python
if exist "%SCRIPT_DIR%tools\python\python.exe" (
    echo [INFO] Using portable Python from tools\python
    set "PATH=%SCRIPT_DIR%tools\python;%SCRIPT_DIR%tools\python\Scripts;%PATH%"
)

:: Check for local portable JDK 17 in tools\jdk-17
if exist "%SCRIPT_DIR%tools\jdk-17\bin\javac.exe" (
    echo [INFO] Using portable JDK 17 from tools\jdk-17
    set "JAVA_HOME=%SCRIPT_DIR%tools\jdk-17"
    set "PATH=%SCRIPT_DIR%tools\jdk-17\bin;%PATH%"
)

:: Check for local portable Android SDK in tools\android-sdk
if exist "%SCRIPT_DIR%tools\android-sdk" (
    echo [INFO] Using portable Android SDK from tools\android-sdk
    set "ANDROID_HOME=%SCRIPT_DIR%tools\android-sdk"
)

if not exist "%SCRIPT_DIR%the-unbound-launcher-0.1.2-alpha.apk" (
    echo [ERROR] Released APK 'the-unbound-launcher-0.1.2-alpha.apk' not found in %SCRIPT_DIR%
    pause
    exit /b 1
)


python tools\rebuild_from_extracted_apk.py --base "%SCRIPT_DIR%the-unbound-launcher-0.1.2-alpha.apk" --output "%SCRIPT_DIR%unbound-launcher-0.1.2-xbx.apk"
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] Rebuild failed. Please check the error messages above.
    echo Make sure JDK 17 and Android SDK build-tools are installed.
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo ============================================================
echo  Done! Output APK: unbound-launcher-0.1.2-xbx.apk
echo ============================================================
pause

