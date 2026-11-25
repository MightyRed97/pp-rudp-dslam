@echo off
rem Builds and tests the Android app without the internet. The JDK, Gradle and every library come from
rem this folder; the Android SDK from offline\android-sdk if it is there, otherwise from ANDROID_HOME.
rem Extra arguments go to Gradle, for example: build-app.bat lintDebug
setlocal
set "KIT=%~dp0"
set "JAVA_HOME=%KIT%jdk"
if exist "%KIT%android-sdk\platforms\android-35" set "ANDROID_HOME=%KIT%android-sdk"
if not defined ANDROID_HOME (
  echo The Android SDK was not found. Put it in %KIT%android-sdk or set ANDROID_HOME; see %KIT%README.md.
  exit /b 1
)
call "%KIT%gradle\bin\gradle.bat" -p "%KIT%..\android" --offline testDebugUnitTest assembleDebug %*
if errorlevel 1 exit /b 1
if not exist "%KIT%app" mkdir "%KIT%app"
copy /y "%KIT%..\android\app\build\outputs\apk\debug\app-debug.apk" "%KIT%app\IrudpChat-debug.apk" >nul
echo.
echo The app is in %KIT%app\IrudpChat-debug.apk
endlocal
