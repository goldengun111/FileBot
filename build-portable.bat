@echo off
setlocal EnableExtensions

set "ROOT=%~dp0"
set "BUILD_RELEASE=0"
if /i "%~1"=="release" set "BUILD_RELEASE=1"
set "ANT_HOME=%ANT_HOME%"
set "JAVA_HOME=%JAVA_HOME%"

if not defined ANT_HOME set "ANT_HOME=C:\tools\java\apache-ant-1.10.17"
if not exist "%ANT_HOME%\bin\ant.bat" (
    echo ERROR: Apache Ant was not found at:
    echo        %ANT_HOME%
    echo Set ANT_HOME to your Apache Ant installation directory.
    exit /b 1
)

if defined JAVA_HOME (
    echo %JAVA_HOME% | findstr /i "jdk-11" >nul
    if errorlevel 1 set "JAVA_HOME="
)

if not exist "%JAVA_HOME%\bin\java.exe" (
    set "JAVA_HOME="
    for /d %%D in ("C:\Program Files\Eclipse Adoptium\jdk-11*") do (
        if exist "%%~fD\bin\java.exe" set "JAVA_HOME=%%~fD"
    )
)

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo ERROR: JDK 11 was not found.
    echo Set JAVA_HOME to your JDK 11 installation directory.
    exit /b 1
)

set "PATH=%ANT_HOME%\bin;%JAVA_HOME%\bin;%PATH%"
set "ANT_OPTS=--add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.invoke=ALL-UNNAMED %ANT_OPTS%"

if exist "C:\Program Files (x86)\WiX Toolset v3.14\bin\heat.exe" set "PATH=C:\Program Files (x86)\WiX Toolset v3.14\bin;%PATH%"

if "%BUILD_RELEASE%"=="1" (
    where heat.exe >nul 2>&1
    if errorlevel 1 (
        echo ERROR: WiX Toolset 3 is required for release builds.
        echo Install WiXToolset.WiXToolset as Administrator, then reopen Git Bash.
        exit /b 1
    )
    where candle.exe >nul 2>&1
    if errorlevel 1 goto wix_missing
    where light.exe >nul 2>&1
    if errorlevel 1 goto wix_missing
)

pushd "%ROOT%"
call "%ANT_HOME%\bin\ant.bat" clean
if errorlevel 1 goto build_failed

call "%ANT_HOME%\bin\ant.bat" resolve
if errorlevel 1 goto build_failed
if exist "%ROOT%lib\ivy\source" rmdir /s /q "%ROOT%lib\ivy\source"

call "%ANT_HOME%\bin\ant.bat" jar
if errorlevel 1 goto build_failed

if "%BUILD_RELEASE%"=="1" (
    call "%ANT_HOME%\bin\ant.bat" msi
    if errorlevel 1 goto build_failed
)

if exist "%APPDATA%\FileBot\apikey\thetvdb.key" (
    if not exist "%ROOT%dist\zip\data\apikey" mkdir "%ROOT%dist\zip\data\apikey"
    copy /y "%APPDATA%\FileBot\apikey\thetvdb.key" "%ROOT%dist\zip\data\apikey\thetvdb.key" >nul
)

call "%ANT_HOME%\bin\ant.bat" zip
if errorlevel 1 (
    goto build_failed
)
popd

echo.
echo BUILD SUCCESSFUL.
if "%BUILD_RELEASE%"=="1" echo Installer package: %ROOT%dist\FileBot_4.8.5_x64.msi
echo Portable package: %ROOT%dist\FileBot_4.8.5-portable.zip
explorer.exe "%ROOT%dist"
exit /b 0

:wix_missing
echo ERROR: WiX Toolset 3 must provide heat.exe, candle.exe, and light.exe on PATH.
exit /b 1

:build_failed
echo.
echo BUILD FAILED. The dist folder was not opened.
popd
exit /b 1
