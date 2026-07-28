@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "BUILD_INSTALLER=1"
set "BUILD_WINDOWS=1"
set "BUILD_LINUX=1"
set "RESOLVE_ARGS="
set "ROOT=%~dp0"

:parse
if "%~1"=="" goto parsed
set "A=%~1"
if /i "!A:~0,2!"=="--" set "A=!A:~2!"
if /i "!A!"=="no-installer" set "BUILD_INSTALLER=0"
if /i "!A!"=="windows" (set "BUILD_WINDOWS=1" & set "BUILD_LINUX=0")
if /i "!A!"=="linux" (set "BUILD_WINDOWS=0" & set "BUILD_LINUX=1")
shift
goto parse
:parsed

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

set "TASKS="
if "%BUILD_INSTALLER%"=="1" (
    if "%BUILD_WINDOWS%"=="1" set "TASKS=:launcher-bootstrap:packageWindows"
    if "%BUILD_LINUX%"=="1" (
        if defined TASKS (set "TASKS=!TASKS! :launcher-bootstrap:packageLinuxWsl") else set "TASKS=:launcher-bootstrap:packageLinuxWsl"
    )
    echo Packaging tasks: !TASKS!
)

call "%ROOT%gradlew" clean build %TASKS%
exit /b %ERRORLEVEL%

:fail
exit /b 1
