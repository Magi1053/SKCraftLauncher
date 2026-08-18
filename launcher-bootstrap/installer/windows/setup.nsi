; --- Includes ---

!include "MUI2.nsh"
!include "nsDialogs.nsh"
!include "LogicLib.nsh"
!include "WordFunc.nsh"
!include "FileFunc.nsh"

!define WindowsInstallerDir "${__FILEDIR__}"

!include "include\defines.nsh"
!include "include\vars.nsh"
!include "include\macros.nsh"
!include "include\install.nsh"
!include "include\webview2.nsh"
!include "include\migration.nsh"
!include "include\launcher.nsh"
!include "include\uninstall.nsh"

; --- MUI pages & language ---

!define MUI_ABORTWARNING
!define MUI_ICON "${IconIco}"
!define MUI_UNICON "${IconIco}"
!define MUI_FINISHPAGE_RUN "$INSTDIR\${AppExeName}"
!define MUI_FINISHPAGE_RUN_TEXT "Launch ${AppName}"
!define MUI_FINISHPAGE_SHOWREADME ""
!define MUI_FINISHPAGE_SHOWREADME_TEXT "Create a desktop shortcut"
!define MUI_FINISHPAGE_SHOWREADME_FUNCTION CreateDesktopShortcut

!insertmacro MUI_PAGE_WELCOME
Page custom MigrateLegacyPageShow MigrateLegacyPageLeave
Page custom WebView2PageShow WebView2PageLeave
!define MUI_PAGE_CUSTOMFUNCTION_SHOW DirectoryPageShow
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH

UninstPage custom un.UninstallConfirmShow un.UninstallConfirmLeave
!insertmacro MUI_UNPAGE_INSTFILES

!insertmacro MUI_LANGUAGE "English"

Function .onInit
	SetShellVarContext current
	; Expand %NAME% placeholders on the target machine before the directory page.
	ExpandEnvStrings $INSTDIR "${InstallBaseDir}\${InstallDirName}"
FunctionEnd

; --- Install section ---

Section "${AppName}" SEC_MAIN
	SectionIn RO
	SetShellVarContext current

	SetOutPath "$INSTDIR"
	StrCpy $DataDir "$INSTDIR\${BootstrapSubdir}"
	Delete "$INSTDIR\install.log"
	SetDetailsPrint both
	Call InitInstallLog

	!insertmacro DetailPrintLog "Starting ${AppName} ${MyAppVersion} installation."

	${If} ${Silent}
		Call PrepareLegacyMigration
		Call PrepareWebView2
	${EndIf}

	Call MigrateLegacyDocuments
	Call MigrateFlatDataLayout
	Call DeleteObsoleteNativeCaches

	Call ShouldInstallBundledLauncher

	!insertmacro DetailPrintLog "Extracting application runtime."
	SetOutPath "$INSTDIR"
	File /r "${AppImageDir}\runtime"
	File /r "${AppImageDir}\app"
	File "${AppImageDir}\${AppExeName}"
	!insertmacro LogInstalledPath "$INSTDIR\runtime"
	!insertmacro LogInstalledPath "$INSTDIR\app"
	!insertmacro LogInstalledPath "$INSTDIR\${AppExeName}"

	IntCmp $ShouldInstallLauncher 1 installLauncher keepLauncher

	installLauncher:
	!insertmacro DetailPrintLog "Installing bundled launcher ${MyAppVersion}."
	RMDir /r "$DataDir\launcher"
	SetOutPath "$DataDir\launcher"
	File /r "${AppImageDir}\bootstrap\launcher\*"
	!insertmacro LogInstalledPath "$DataDir\launcher"
	Goto installWeblite

	keepLauncher:
	!insertmacro DetailPrintLog "Keeping existing launcher cache (installed version/update URL policy keeps local files)."

	installWeblite:
	!insertmacro DetailPrintLog "Installing weblite host bridge."
	RMDir /r "$DataDir\${NativesSubdir}\weblite"
	SetOutPath "$DataDir\${NativesSubdir}\weblite"
	File /r "${AppImageDir}\bootstrap\natives\weblite\*"
	!insertmacro LogInstalledPath "$DataDir\${NativesSubdir}\weblite"

	IfFileExists "$DataDir\launcher\*.*" 0 missingLauncherDir
	!insertmacro DetailPrintLogSuffix "Launcher jar present at " "$DataDir\launcher"
	IfFileExists "$DataDir\${NativesSubdir}\weblite\*.*" 0 missingWebliteDir
	!insertmacro DetailPrintLogSuffix "Weblite host bridge present at " "$DataDir\${NativesSubdir}\weblite"
	Goto appPayloadDone

	missingLauncherDir:
	!insertmacro DetailPrintLog "Bundled launcher directory was not found."
	Abort

	missingWebliteDir:
	!insertmacro DetailPrintLog "Weblite host bridge was not found."
	Abort

	appPayloadDone:
	!insertmacro DetailPrintLog "Writing uninstaller, shortcuts, and uninstall registry entries."

	WriteUninstaller "$INSTDIR\uninstall.exe"

	CreateDirectory "$SMPROGRAMS\${AppName}"
	!insertmacro CreateAppShortcut "$SMPROGRAMS\${AppName}\${AppName}.lnk"
	CreateShortcut "$SMPROGRAMS\${AppName}\Uninstall ${AppName}.lnk" "$INSTDIR\uninstall.exe" "" "$INSTDIR\${AppExeName}"

	WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${AppId}" "DisplayName" "${AppName}"
	WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${AppId}" "DisplayVersion" "${MyAppVersion}"
	WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${AppId}" "DisplayIcon" "$INSTDIR\${AppExeName}"
	WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${AppId}" "UninstallString" \
		'"$INSTDIR\uninstall.exe"'
	WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${AppId}" "NoModify" 1
	WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${AppId}" "NoRepair" 1

	Call InstallWebView2IfRequested

	!insertmacro DetailPrintLog "Installation completed successfully."
SectionEnd

; --- Uninstall section ---

Section "Uninstall" un.SEC_MAIN
	SectionIn RO
	SetShellVarContext current
	StrCpy $DataDir "$INSTDIR\${BootstrapSubdir}"
	Delete "$DESKTOP\${AppName}.lnk"
	Delete "$SMPROGRAMS\${AppName}\${AppName}.lnk"
	Delete "$SMPROGRAMS\${AppName}\Uninstall ${AppName}.lnk"
	RMDir "$SMPROGRAMS\${AppName}"

	!insertmacro UninstallBulkRemoveDir "$INSTDIR\app"
	Delete "$INSTDIR\install.log"
	Delete "$INSTDIR\${AppExeName}"
	!insertmacro UninstallBulkRemoveDir "$INSTDIR\runtime"
	!insertmacro UninstallBulkRemoveDir "$INSTDIR\webview2"
	Delete "$INSTDIR\uninstall.exe"

	!insertmacro DeleteManagedBootstrapData
	IntCmp $un.DeleteUserData 1 unDeleteUserData unBootstrapCleanupDone
	unDeleteUserData:
	!insertmacro DeleteUserBootstrapData
	unBootstrapCleanupDone:
	RMDir "$INSTDIR"

	DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${AppId}"
SectionEnd
