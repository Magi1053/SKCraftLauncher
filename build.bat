@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "BUILD_INSTALLER=1"
set "BUILD_WINDOWS=1"
set "BUILD_LINUX=1"
set "LINUX_BACKEND=auto"
set "RESOLVE_ARGS="
set "ROOT=%~dp0"
set "DOCKER_IMAGE=gradle:8.14.0-jdk17"

:parse
if "%~1"=="" goto parsed
set "A=%~1"
if /i "!A:~0,2!"=="--" set "A=!A:~2!"
if /i "!A!"=="no-installer" set "BUILD_INSTALLER=0"
if /i "!A!"=="windows" (set "BUILD_WINDOWS=1" & set "BUILD_LINUX=0")
if /i "!A!"=="linux" (set "BUILD_WINDOWS=0" & set "BUILD_LINUX=1")
if /i "!A!"=="docker" set "LINUX_BACKEND=docker"
if /i "!A!"=="wsl" set "LINUX_BACKEND=wsl"
shift
goto parse
:parsed

if "%BUILD_WINDOWS%%BUILD_LINUX%"=="00" (
    echo ERROR: Nothing to package.
    goto :fail
)

rem Linux-only + Docker: compile and package fully in container (no host JDK).
if "%BUILD_WINDOWS%"=="0" if "%BUILD_LINUX%"=="1" (
    call :pick_linux
    if errorlevel 1 goto :fail
    if /i "!LINUX_BACKEND_KIND!"=="docker" (
        call :docker_linux_full
        set "EXIT_CODE=!ERRORLEVEL!"
        pause
        exit /b !EXIT_CODE!
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
        if not defined LINUX_TASK (
            call :pick_linux
            if errorlevel 1 goto :fail
        )
        if defined TASKS (set "TASKS=!TASKS! !LINUX_TASK!") else set "TASKS=!LINUX_TASK!"
    )
    echo Packaging tasks: !TASKS!
)

call "%ROOT%gradlew" clean build %TASKS%
set "EXIT_CODE=%ERRORLEVEL%"
pause
exit /b %EXIT_CODE%

:pick_linux
if "%LINUX_BACKEND%"=="docker" (
    call :docker_ok
    if errorlevel 1 (
        echo ERROR: --docker set, but Docker unavailable. No WSL fallback.
        exit /b 1
    )
    set "LINUX_TASK=:launcher-bootstrap:packageLinuxDocker"
    set "LINUX_BACKEND_KIND=docker"
    echo Linux backend: Docker
    exit /b 0
)
if "%LINUX_BACKEND%"=="wsl" (
    call :wsl_ok
    if errorlevel 1 (
        echo ERROR: --wsl set, but WSL unavailable. No Docker fallback.
        exit /b 1
    )
    set "LINUX_TASK=:launcher-bootstrap:packageLinuxWsl"
    set "LINUX_BACKEND_KIND=wsl"
    echo Linux backend: WSL
    exit /b 0
)
call :docker_ok
if not errorlevel 1 (
    set "LINUX_TASK=:launcher-bootstrap:packageLinuxDocker"
    set "LINUX_BACKEND_KIND=docker"
    echo Linux backend: Docker
    exit /b 0
)
call :wsl_ok
if not errorlevel 1 (
    set "LINUX_TASK=:launcher-bootstrap:packageLinuxWsl"
    set "LINUX_BACKEND_KIND=wsl"
    echo Linux backend: WSL
    exit /b 0
)
echo ERROR: Linux packaging needs Docker or WSL. Use --windows to skip.
exit /b 1

:docker_linux_full
call :docker_ok
if errorlevel 1 (
    echo ERROR: Docker unavailable.
    exit /b 1
)

set "REPO_MOUNT=%ROOT:\=/%"
if "%REPO_MOUNT:~-1%"=="/" set "REPO_MOUNT=%REPO_MOUNT:~0,-1%"
set "SCRIPT_MOUNT=%ROOT%gradle\docker-linux-build.sh"
set "SCRIPT_MOUNT=%SCRIPT_MOUNT:\=/%"

set "BUILD_DEB=false"
set "SKIP_PACKAGE=false"
if "%BUILD_INSTALLER%"=="0" set "SKIP_PACKAGE=true"

echo Packaging Linux fully in Docker ^(no host JDK^)...
echo Using Docker image: %DOCKER_IMAGE%
docker run --rm --user root --entrypoint bash ^
    -e "BUILD_DEB=%BUILD_DEB%" ^
    -e "SKIP_PACKAGE=%SKIP_PACKAGE%" ^
    -v "%REPO_MOUNT%:/workspace" ^
    -v "%SCRIPT_MOUNT%:/tmp/docker-linux-build.sh:ro" ^
    -w /workspace ^
    "%DOCKER_IMAGE%" ^
    -c "tr -d '\r' </tmp/docker-linux-build.sh | bash"
exit /b %ERRORLEVEL%

:docker_ok
where docker >nul 2>&1 || exit /b 1
docker info >nul 2>&1
exit /b %ERRORLEVEL%

:wsl_ok
where wsl >nul 2>&1 || exit /b 1
wsl -- echo WSL_OK >nul 2>&1
exit /b %ERRORLEVEL%

:fail
pause
exit /b 1
