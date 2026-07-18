; --- Install functions ---

!addplugindir "${WindowsInstallerDir}\plugins\WinShell\x86-unicode"

!macro CreateAppShortcut SHORTCUT_PATH
	SetOutPath "$INSTDIR"
	CreateShortcut "${SHORTCUT_PATH}" "$INSTDIR\${AppExeName}" "" "$INSTDIR\${AppExeName}"
	WinShell::SetLnkAUMI "${SHORTCUT_PATH}" "${AppUserModelId}"
!macroend

Function InitInstallLog
	ClearErrors
	FileOpen $InstallLogFile "$INSTDIR\install.log" w
	IfErrors initInstallLogDone
	FileWrite $InstallLogFile "Output folder: $INSTDIR$\r$\n"
	FileClose $InstallLogFile
	initInstallLogDone:
FunctionEnd

Function AppendInstallLogLine
	Exch $InstallLogLine
	ClearErrors
	FileOpen $InstallLogFile "$INSTDIR\install.log" a
	IfErrors appendInstallLogLineDone
	FileSeek $InstallLogFile 0 END
	FileWrite $InstallLogFile "$InstallLogLine$\r$\n"
	FileClose $InstallLogFile
	appendInstallLogLineDone:
	Pop $InstallLogLine
FunctionEnd

Function AppendInstallLogSuffix
	Pop $InstallLogPrefix
	Pop $InstallLogSuffix
	ClearErrors
	FileOpen $InstallLogFile "$INSTDIR\install.log" a
	IfErrors appendInstallLogSuffixDone
	FileSeek $InstallLogFile 0 END
	FileWrite $InstallLogFile "$InstallLogPrefix$InstallLogSuffix$\r$\n"
	FileClose $InstallLogFile
	appendInstallLogSuffixDone:
FunctionEnd

Function LogInstalledPath
	Exch $0

	IfFileExists "$0\*" 0 logInstalledPathFile
	Push "$0"
	Push "Extracted directory: "
	Call AppendInstallLogSuffix
	Push "$0"
	Call LogInstalledTree
	Goto logInstalledPathDone

	logInstalledPathFile:
	IfFileExists "$0" 0 logInstalledPathDone
	Push "$0"
	Push "Extracted file: "
	Call AppendInstallLogSuffix

	logInstalledPathDone:
	Pop $0
FunctionEnd

Function LogInstalledTree
	Exch $R0
	Push $R1
	Push $R2
	Push $R3

	FindFirst $R1 $R2 "$R0\*"
	IfErrors logInstalledTreeDone

	logInstalledTreeLoop:
	StrCmp $R2 "." logInstalledTreeNext
	StrCmp $R2 ".." logInstalledTreeNext

	StrCpy $R3 "$R0\$R2"
	IfFileExists "$R3\*" 0 logInstalledTreeFile

	Push "$R3"
	Push "Extracted directory: "
	Call AppendInstallLogSuffix
	Push "$R3"
	Call LogInstalledTree
	Goto logInstalledTreeNext

	logInstalledTreeFile:
	Push "$R3"
	Push "Extracted file: "
	Call AppendInstallLogSuffix

	logInstalledTreeNext:
	ClearErrors
	FindNext $R1 $R2
	IfErrors logInstalledTreeClose
	Goto logInstalledTreeLoop

	logInstalledTreeClose:
	FindClose $R1

	logInstalledTreeDone:
	Pop $R3
	Pop $R2
	Pop $R1
	Pop $R0
FunctionEnd

Function DirectoryPageShow
	Push $0
	GetDlgItem $0 $HWNDPARENT 1
	SendMessage $0 ${WM_SETTEXT} 0 "STR:Install"
	Pop $0
FunctionEnd

Function CreateDesktopShortcut
	SetShellVarContext current
	!insertmacro CreateAppShortcut "$DESKTOP\${AppName}.lnk"
FunctionEnd
