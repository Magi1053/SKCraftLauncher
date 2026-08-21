@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "BUILD_INSTALLER=1"
set "BUILD_WINDOWS=1"
set "BUILD_LINUX=1"
set "VERSION="
set "RESOLVE_ARGS="
set "ROOT=%~dp0"

:parse
if "%~1"=="" goto parsed
set "A=%~1"
if /i "!A:~0,2!"=="--" set "A=!A:~2!"
if /i "!A!"=="no-installer" set "BUILD_INSTALLER=0"
if /i "!A!"=="windows" (set "BUILD_WINDOWS=1" & set "BUILD_LINUX=0")
if /i "!A!"=="linux" (set "BUILD_WINDOWS=0" & set "BUILD_LINUX=1")
if /i "!A:~0,8!"=="version=" (
    set "VERSION=!A:~8!"
) else if /i "!A!"=="version" (
    if "%~2"=="" (
        echo ERROR: --version requires a value.
        goto :fail
    )
    set "VERSION=%~2"
    shift
)
shift
goto parse
:parsed

if "%BUILD_INSTALLER%"=="1" if not defined VERSION (
    echo ERROR: --version is required to package installers ^(e.g. --version 1.0.0^).
    goto :fail
)
if defined VERSION if "!VERSION!"=="" (
    echo ERROR: --version requires a value.
    goto :fail
)

if "%BUILD_WINDOWS%%BUILD_LINUX%"=="00" (
    echo ERROR: Nothing to package.
    goto :fail
)

if "%BUILD_LINUX%"=="1" if "%BUILD_INSTALLER%"=="1" (
    where wsl >nul 2>&1
    if errorlevel 1 (
        echo ERROR: Linux packaging requires WSL.
        goto :fail
    )
    wsl -- echo WSL_OK >nul 2>&1
    if errorlevel 1 (
        echo ERROR: Linux packaging requires a working WSL distro.
        goto :fail
    )
)
if "%BUILD_LINUX%"=="1" if "%BUILD_INSTALLER%"=="1" (
    set "WSL_MISSING="
    for %%T in (java curl file fakeroot flatpak flatpak-builder) do (
        wsl -- bash -lc "type %%T" >nul 2>&1
        if errorlevel 1 set "WSL_MISSING=!WSL_MISSING! %%T"
    )
    if defined WSL_MISSING (
        set "INSTALL_WSL_DEPS="
        set /p "INSTALL_WSL_DEPS=Missing WSL packaging tools:!WSL_MISSING!. Install now? [y/N] "
        if /i not "!INSTALL_WSL_DEPS!"=="y" (
            echo Linux packaging skipped because dependency installation was declined.
            set "SKIP_LINUX_PACKAGE=1"
        ) else (
            echo Installing WSL packaging dependencies...
            wsl -u root -- apt-get update
            if errorlevel 1 goto :fail
            wsl -u root -- apt-get install -y openjdk-17-jdk curl file fakeroot flatpak flatpak-builder
            if errorlevel 1 goto :fail
        )
    )
    if "!SKIP_LINUX_PACKAGE!"=="1" (
        set "BUILD_LINUX=0"
    )
)

if "%BUILD_INSTALLER%"=="0" (
    set "RESOLVE_ARGS=--no-installer"
) else if "%BUILD_WINDOWS%"=="0" (
    set "RESOLVE_ARGS=--no-nsis"
)
call "%ROOT%gradle\resolve-build-env.bat" %RESOLVE_ARGS%
if errorlevel 1 goto :fail
echo Using JAVA_HOME=%JAVA_HOME%
if defined VERSION echo Using version=!VERSION!
call :printSigningStatus
if errorlevel 1 goto :fail

set "TASKS="
set "VERSION_ARG="
if defined VERSION set "VERSION_ARG=-Pversion=!VERSION!"
if "%BUILD_INSTALLER%"=="1" (
    if "%BUILD_WINDOWS%"=="1" set "TASKS=:launcher-bootstrap:packageWindows"
    if "%BUILD_LINUX%"=="1" (
        if defined TASKS (set "TASKS=!TASKS! :launcher-bootstrap:packageLinuxWsl") else set "TASKS=:launcher-bootstrap:packageLinuxWsl"
    )
    echo Packaging tasks: !TASKS!
)

call "%ROOT%gradlew" clean build %TASKS% !VERSION_ARG!
exit /b %ERRORLEVEL%

:fail
exit /b 1

:printSigningStatus
if not "%BUILD_INSTALLER%"=="1" (
    echo Using signing=n/a
    exit /b 0
)
if not "%BUILD_WINDOWS%"=="1" (
    echo Using signing=n/a
    exit /b 0
)
set "SIGN_SET=0"
if defined WINDOWS_SIGN_AZURE_ENDPOINT if not "!WINDOWS_SIGN_AZURE_ENDPOINT!"=="" set /a SIGN_SET+=1
if defined WINDOWS_SIGN_AZURE_ACCOUNT if not "!WINDOWS_SIGN_AZURE_ACCOUNT!"=="" set /a SIGN_SET+=1
if defined WINDOWS_SIGN_AZURE_PROFILE if not "!WINDOWS_SIGN_AZURE_PROFILE!"=="" set /a SIGN_SET+=1
if !SIGN_SET! EQU 0 (
    if defined WINDOWS_SIGN_AZURE_TOKEN if not "!WINDOWS_SIGN_AZURE_TOKEN!"=="" (
        echo ERROR: WINDOWS_SIGN_AZURE_TOKEN is set, but WINDOWS_SIGN_AZURE_ENDPOINT, WINDOWS_SIGN_AZURE_ACCOUNT, and WINDOWS_SIGN_AZURE_PROFILE are also required.
        exit /b 1
    )
    echo Using signing=off
    exit /b 0
)
if not !SIGN_SET! EQU 3 (
    echo ERROR: Windows Artifact Signing requires WINDOWS_SIGN_AZURE_ENDPOINT, WINDOWS_SIGN_AZURE_ACCOUNT, and WINDOWS_SIGN_AZURE_PROFILE together.
    exit /b 1
)
echo Using signing=Azure Artifact Signing ^(!WINDOWS_SIGN_AZURE_ACCOUNT!/!WINDOWS_SIGN_AZURE_PROFILE! @ !WINDOWS_SIGN_AZURE_ENDPOINT!^)
exit /b 0
