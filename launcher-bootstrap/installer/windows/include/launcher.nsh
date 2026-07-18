; --- Launcher version gating functions ---

Function ReadTrimmedVersionFile
	; Input: file path on stack. Output: trimmed first line in $R0, or empty on error.
	Exch $0
	Push $1
	StrCpy $R0 ""
	ClearErrors
	FileOpen $1 $0 r
	IfErrors readTrimmedVersionDone
	FileRead $1 $R0
	FileClose $1

	readTrimmedVersionTrimLoop:
	StrCpy $1 $R0 1 -1
	StrCmp $1 "$\r" 0 +3
	StrCpy $R0 $R0 -1
	Goto readTrimmedVersionTrimLoop
	readTrimmedVersionTrimLoop2:
	StrCpy $1 $R0 1 -1
	StrCmp $1 "$\n" 0 readTrimmedVersionDone
	StrCpy $R0 $R0 -1
	Goto readTrimmedVersionTrimLoop2

	readTrimmedVersionDone:
	Pop $1
	Pop $0
FunctionEnd

Function ShouldInstallBundledLauncher
	Push $0
	Push $1
	Push $2

	StrCpy $ShouldInstallLauncher 1

	IfFileExists "$DataDir\launcher" 0 shouldInstallLauncherDone
	IfFileExists "$DataDir\launcher\launcher.version" 0 checkExistingLauncherJars

	Push "$DataDir\launcher\launcher.version"
	Call ReadTrimmedVersionFile

	StrCmp $R0 "" checkExistingLauncherJars
	${VersionCompare} "${MyAppVersion}" $R0 $2
	IntCmp $2 2 keepExistingLauncher shouldInstallLauncherDone shouldInstallLauncherDone

	keepExistingLauncher:
	StrCpy $ShouldInstallLauncher 0
	Goto shouldInstallLauncherDone

	checkExistingLauncherJars:
	FindFirst $0 $1 "$DataDir\launcher\*.jar"
	IfErrors shouldInstallLauncherDone
	FindClose $0
	StrCpy $ShouldInstallLauncher 0

	shouldInstallLauncherDone:
	Pop $2
	Pop $1
	Pop $0
FunctionEnd
