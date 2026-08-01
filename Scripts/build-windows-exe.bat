@echo off
setlocal EnableExtensions

set "SCRIPT_DIR=%~dp0"
for %%I in ("%SCRIPT_DIR%..") do set "PROJECT_DIR=%%~fI"
set "GRADLEW=%PROJECT_DIR%\gradlew.bat"
set "OUTPUT_DIR=%PROJECT_DIR%\app\build\distributions\native\windows"

if /I "%~1"=="--help" goto :usage
if /I "%~1"=="-h" goto :usage
if not "%~1"=="" goto :unknown_option

if not exist "%GRADLEW%" (
  echo Error: Gradle wrapper is missing: %GRADLEW% 1>&2
  exit /b 1
)

where candle.exe >nul 2>&1
if errorlevel 1 (
  echo Error: WiX Toolset 3.x is required and candle.exe is not on PATH. 1>&2
  exit /b 1
)

where light.exe >nul 2>&1
if errorlevel 1 (
  echo Error: WiX Toolset 3.x is required and light.exe is not on PATH. 1>&2
  exit /b 1
)

echo MiMiTrends Windows package
echo   Project: %PROJECT_DIR%
echo   Format:  self-contained EXE installer

pushd "%PROJECT_DIR%" || exit /b 1
call "%GRADLEW%" :app:packageWindowsExe
set "BUILD_EXIT=%ERRORLEVEL%"
popd
if not "%BUILD_EXIT%"=="0" exit /b %BUILD_EXIT%

set "INSTALLER="
for /f "delims=" %%F in ('dir /b /a-d /o-d "%OUTPUT_DIR%\*.exe" 2^>nul') do if not defined INSTALLER set "INSTALLER=%OUTPUT_DIR%\%%F"
if not defined INSTALLER (
  echo Error: Gradle completed but no EXE was found in %OUTPUT_DIR% 1>&2
  exit /b 1
)

echo.
echo Created: %INSTALLER%
for %%F in ("%INSTALLER%") do echo Size:    %%~zF bytes
certutil -hashfile "%INSTALLER%" SHA256
exit /b 0

:usage
echo Usage: build-windows-exe.bat
echo.
echo Builds a self-contained MiMiTrends EXE installer using WiX Toolset 3.x.
exit /b 0

:unknown_option
echo Error: unknown option: %~1 1>&2
exit /b 1
