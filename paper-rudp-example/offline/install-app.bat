@echo off
rem Installs the app on the phone (USB debugging on) or emulator that adb sees.
setlocal
set "KIT=%~dp0"
set "ADB=%KIT%android-sdk\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=adb"
"%ADB%" install -r "%KIT%app\IrudpChat-debug.apk"
endlocal
