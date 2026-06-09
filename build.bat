@echo off
setlocal EnableExtensions

set "BUILD_INSTALLER=1"
set "RESOLVE_ARGS="
if /i "%~1"=="--no-installer" set "BUILD_INSTALLER=0"
if /i "%~1"=="no-installer" set "BUILD_INSTALLER=0"
if "%BUILD_INSTALLER%"=="0" set "RESOLVE_ARGS=--no-installer"

call "%~dp0gradle\resolve-build-env.bat" %RESOLVE_ARGS%
if errorlevel 1 (
    pause
    exit /b 1
)
echo Using JAVA_HOME=%JAVA_HOME%

set "ADDITIONAL_TASKS="
if "%BUILD_INSTALLER%"=="1" set "ADDITIONAL_TASKS=package"

call gradlew clean build %ADDITIONAL_TASKS%
set "EXIT_CODE=%ERRORLEVEL%"
pause
exit /b %EXIT_CODE%
