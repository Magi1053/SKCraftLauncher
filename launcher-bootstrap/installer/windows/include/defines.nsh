; --- Compile-time defines & installer metadata ---

!ifndef WindowsInstallerDir
	!define WindowsInstallerDir "${__FILEDIR__}\.."
!endif

!ifndef MyAppVersion
	!define MyAppVersion "1.0.0"
!endif

!ifndef INSTALLER_DEFINES
	!ifndef AppName
		!define AppName "Example App"
	!endif

	!ifndef InstallDirName
		!define InstallDirName "${AppName}"
	!endif

	!ifndef LegacyHomeFolder
		!define LegacyHomeFolder ""
	!endif

!else
	!include "${INSTALLER_DEFINES}"
!endif

!ifndef AppName
	!error "AppName must be defined"
!endif

!ifndef InstallDirName
	!define InstallDirName "${AppName}"
!endif

!ifndef InstallBaseDir
	!error "InstallBaseDir must be defined"
!endif

!ifndef AppId
	!define AppId "${InstallDirName}"
!endif

!ifndef AppUserModelId
	!define AppUserModelId "SKCraft.${InstallDirName}"
!endif

!ifndef AppImageDir
	!define AppImageDir "${WindowsInstallerDir}\..\..\build\windows-app-image\${AppName}"
!endif

!ifndef OutputDir
	!define OutputDir "${WindowsInstallerDir}\..\..\build\installer\windows"
!endif

!ifndef IconIco
	!define IconIco "${WindowsInstallerDir}\..\..\build\tmp\windows\icon.ico"
!endif

!ifndef AppExeName
	!define AppExeName "${AppName}.exe"
!endif

!ifndef SetupFileName
	!define SetupFileName "${AppName} Setup.exe"
!endif

!ifndef WebView2Bootstrapper
	!define WebView2Bootstrapper "${WindowsInstallerDir}\..\..\build\webview2-runtime\MicrosoftEdgeWebview2Setup.exe"
!endif

!define BootstrapSubdir "bootstrap"
!define NativesSubdir "natives"
!define WebView2ClientGuid "{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}"
!define WebView2BootstrapperFileName "MicrosoftEdgeWebview2Setup.exe"

Unicode true
Name "${AppName}"
OutFile "${OutputDir}\${SetupFileName}"
InstallDir "${InstallBaseDir}\${InstallDirName}"
RequestExecutionLevel user
SetCompressor /SOLID lzma
ShowInstDetails show
ShowUninstDetails show
