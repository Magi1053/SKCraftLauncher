@echo off
rem Resolve JDK 17 (required) and optionally NSIS for Windows installer packaging.
rem Usage: resolve-build-env.bat [--no-installer]
rem   --no-installer  Skip NSIS lookup and set BUILD_INSTALLER=0
rem Exit code 1 if JDK 17 is not found; 0 otherwise.
rem Sets JAVA_HOME, BUILD_INSTALLER, and optionally MAKENSIS.

if not defined BUILD_INSTALLER set "BUILD_INSTALLER=1"
if /i "%~1"=="--no-installer" set "BUILD_INSTALLER=0"
if /i "%~1"=="no-installer" set "BUILD_INSTALLER=0"

call :resolve_java_home
if errorlevel 1 goto missing_jdk

if "%BUILD_INSTALLER%"=="0" goto skip_installer

call :resolve_nsis
if errorlevel 1 goto missing_nsis
echo Using NSIS=%MAKENSIS%
exit /b 0

:missing_jdk
echo.
echo Could not find JDK 17.
echo Set JAVA_HOME or JDK17_HOME to a JDK 17 install, then run build.bat again.
echo.
exit /b 1

:missing_nsis
echo.
echo ====================================================================
echo  NSIS not found - Windows installer packaging will be skipped
echo ====================================================================
echo.
echo  JARs and other build outputs will still be produced.
echo.
echo  To build SKCraftLauncherSetup.exe, install NSIS 3:
echo    winget install NSIS.NSIS
echo  Or download from:
echo    https://nsis.sourceforge.io/Download
echo.
echo  Then run build.bat again.
echo  To skip this message, run: build.bat --no-installer
echo.
set "BUILD_INSTALLER=0"
exit /b 0

:skip_installer
echo Skipping native installer packaging.
exit /b 0

:resolve_java_home
if defined JDK17_HOME if exist "%JDK17_HOME%\bin\java.exe" (
    set "JAVA_HOME=%JDK17_HOME%"
    exit /b 0
)

if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" (
    exit /b 0
)

for /d %%J in ("C:\Program Files\Amazon Corretto\jdk17*") do (
    if exist "%%~J\bin\java.exe" (
        set "JAVA_HOME=%%~J"
        exit /b 0
    )
)

for /d %%J in ("C:\Program Files\Java\jdk-17*") do (
    if exist "%%~J\bin\java.exe" (
        set "JAVA_HOME=%%~J"
        exit /b 0
    )
)

for /d %%J in ("C:\Program Files\Eclipse Adoptium\jdk-17*") do (
    if exist "%%~J\bin\java.exe" (
        set "JAVA_HOME=%%~J"
        exit /b 0
    )
)

for /d %%J in ("C:\Program Files\Microsoft\jdk-17*") do (
    if exist "%%~J\bin\java.exe" (
        set "JAVA_HOME=%%~J"
        exit /b 0
    )
)

exit /b 1

:resolve_nsis
if defined MAKENSIS if exist "%MAKENSIS%" exit /b 0

if defined NSIS_HOME if exist "%NSIS_HOME%\makensis.exe" (
    set "MAKENSIS=%NSIS_HOME%\makensis.exe"
    exit /b 0
)

where makensis >nul 2>&1
if not errorlevel 1 (
    for /f "delims=" %%F in ('where makensis 2^>nul') do (
        set "MAKENSIS=%%F"
        exit /b 0
    )
)

set "NSIS_X86=%ProgramFiles(x86)%\NSIS\makensis.exe"
if exist "%NSIS_X86%" (
    set "MAKENSIS=%NSIS_X86%"
    exit /b 0
)

set "NSIS_X64=%ProgramFiles%\NSIS\makensis.exe"
if exist "%NSIS_X64%" (
    set "MAKENSIS=%NSIS_X64%"
    exit /b 0
)

exit /b 1
