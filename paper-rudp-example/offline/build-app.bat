@echo off
rem Builds and tests the Android app without the internet. The JDK, Gradle and every library come from
rem this folder; the Android SDK from offline\android-sdk if it has a platform, otherwise from ANDROID_HOME
rem (or ANDROID_SDK_ROOT). The SDK needs Platform 35, 36 or 34 and Build-Tools 34.0.0 or newer.
rem Extra arguments go to Gradle, for example: build-app.bat lintDebug
setlocal
set "KIT=%~dp0"
set "JAVA_HOME=%KIT%jdk"
dir /b /ad "%KIT%android-sdk\platforms\android-*" >nul 2>&1 && set "ANDROID_HOME=%KIT%android-sdk"
if not defined ANDROID_HOME if defined ANDROID_SDK_ROOT set "ANDROID_HOME=%ANDROID_SDK_ROOT%"
if not defined ANDROID_HOME (
  echo The Android SDK was not found. Put Platform 35, 36 or 34 and Build-Tools 34.0.0 or newer in
  echo %KIT%android-sdk, or set ANDROID_HOME; see %KIT%README.md.
  exit /b 1
)
call "%KIT%gradle\bin\gradle.bat" -p "%KIT%..\android" --offline testDebugUnitTest assembleDebug %*
if errorlevel 1 exit /b 1
if not exist "%KIT%app" mkdir "%KIT%app"
copy /y "%KIT%..\android\app\build\outputs\apk\debug\app-debug.apk" "%KIT%app\IrudpChat-debug.apk" >nul
echo.
echo The app is in %KIT%app\IrudpChat-debug.apk
endlocal
