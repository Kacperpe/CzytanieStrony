@echo off
setlocal

set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
set "PATH=%JAVA_HOME%\bin;%PATH%"
set "APK_OUT=app\build\outputs\apk\debug"
set "APK_NAME=CzytanieStrony-v1.0-debug.apk"

call gradlew.bat --stop
call gradlew.bat --no-daemon --no-watch-fs assembleDebug
if errorlevel 1 exit /b %errorlevel%

if exist "%APK_OUT%\%APK_NAME%" del "%APK_OUT%\%APK_NAME%"
if exist "%APK_OUT%\app-debug.apk" ren "%APK_OUT%\app-debug.apk" "%APK_NAME%"
