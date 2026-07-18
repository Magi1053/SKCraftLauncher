; --- Legacy and layout migration functions ---

Function PrepareLegacyMigration
	SetShellVarContext current
	StrCpy $MigrateLegacyData 0
	StrCpy $MigrateLegacyUserDeclined 0
	StrCpy $MigrateLegacyAvailable 0
	StrCpy $MigrateLegacyBootstrapExists 0

	StrLen $0 "${LegacyHomeFolder}"
	IntCmp $0 0 prepareLegacyMigrationDone

	StrCpy $MigrateLegacyPath "$DOCUMENTS\${LegacyHomeFolder}"
	IfFileExists "$MigrateLegacyPath" 0 prepareLegacyMigrationDone

	StrCpy $MigrateLegacyAvailable 1

	IfFileExists "$INSTDIR\${BootstrapSubdir}" 0 prepareLegacyMigrationFreshInstall
	StrCpy $MigrateLegacyBootstrapExists 1
	Goto prepareLegacyMigrationDefaults

	prepareLegacyMigrationFreshInstall:
	StrCpy $MigrateLegacyData 1

	prepareLegacyMigrationDefaults:

	${If} ${Silent}
		${GetParameters} $0
		ClearErrors
		${GetOptions} $0 "/MIGRATELEGACY=0" $1
		IfErrors +3
		StrCpy $MigrateLegacyData 0
		StrCpy $MigrateLegacyUserDeclined 1
		Goto prepareLegacyMigrationDone
		ClearErrors
		${GetOptions} $0 "/MIGRATELEGACY=1" $1
		IfErrors prepareLegacyMigrationDone
		StrCpy $MigrateLegacyData 1
		StrCpy $MigrateLegacyUserDeclined 0
	${EndIf}

	prepareLegacyMigrationDone:
FunctionEnd

Function MigrateLegacyPageShow
	Call PrepareLegacyMigration
	IntCmp $MigrateLegacyAvailable 1 0 migratePageSkip

	Push $1
	!insertmacro MUI_HEADER_TEXT "Import Legacy Data" "Choose whether to import data from your Documents folder."

	nsDialogs::Create 1018
	Pop $0

	${If} $0 == error
		Pop $1
		Abort
	${EndIf}

	StrCpy $1 "Your Documents folder contains launcher data from a previous install.$\r$\n$\r$\n"
	StrCpy $1 "$1$MigrateLegacyPath$\r$\n$\r$\n"
	IntCmp $MigrateLegacyBootstrapExists 1 migratePageExistingBootstrap migratePageFreshInstall

	migratePageFreshInstall:
	StrCpy $1 "$1Import instances, config, accounts, and other files into this installation?"
	Goto migratePageCreateControls

	migratePageExistingBootstrap:
	StrCpy $1 "$1This installation already has data in $INSTDIR\${BootstrapSubdir}.$\r$\n$\r$\n"
	StrCpy $1 \
		"$1Importing will merge legacy files into the existing installation. Conflicting files and folders will be replaced."

	migratePageCreateControls:
	${NSD_CreateLabel} 0 0 100% 72u ""
	Pop $0
	${NSD_SetText} $0 $1

	${NSD_CreateCheckbox} 0 78u 100% 12u "Import data from Documents folder"
	Pop $MigrateLegacyCheckbox
	IntCmp $MigrateLegacyData 1 migratePageCheckImport migratePageUncheckImport

	migratePageCheckImport:
	${NSD_Check} $MigrateLegacyCheckbox
	Goto migratePageShowDialog

	migratePageUncheckImport:
	${NSD_Uncheck} $MigrateLegacyCheckbox

	migratePageShowDialog:
	nsDialogs::Show
	Pop $1
	Return

	migratePageSkip:
	Abort
FunctionEnd

Function MigrateLegacyPageLeave
	${NSD_GetState} $MigrateLegacyCheckbox $MigrateLegacyData
	StrCpy $MigrateLegacyUserDeclined 0
	IntCmp $MigrateLegacyData 1 +2
	StrCpy $MigrateLegacyUserDeclined 1
FunctionEnd

Function PrintLegacyMigrationSkipReason
	IntCmp $MigrateLegacyUserDeclined 1 skipByUser
	Goto skipReasonDone

	skipByUser:
	!insertmacro DetailPrintLog "Legacy Documents migration skipped by user."

	skipReasonDone:
FunctionEnd

Function MergeLegacyPath
	; In: dest path on stack, then source path. Deep-merge with overwrite.
	Pop $R9
	Pop $R8

	Push $0
	Push $1
	Push $2
	Push $R0
	Push $R1
	Push $R2

	IfFileExists "$R9" 0 mergeLegacyPathDone

	IfFileExists "$R9\*" 0 mergeLegacyPathFile
	CreateDirectory "$R8"
	FindFirst $R2 $1 "$R9\*"
	IfErrors mergeLegacyPathRemoveSourceDir
	mergeLegacyPathLoop:
	StrCmp $1 "." mergeLegacyPathNext
	StrCmp $1 ".." mergeLegacyPathNext
	Push "$R8\$1"
	Push "$R9\$1"
	Call MergeLegacyPath
	mergeLegacyPathNext:
	ClearErrors
	FindNext $R2 $1
	IfErrors mergeLegacyPathClose
	Goto mergeLegacyPathLoop
	mergeLegacyPathClose:
	FindClose $R2
	mergeLegacyPathRemoveSourceDir:
	RMDir "$R9"
	Goto mergeLegacyPathDone

	mergeLegacyPathFile:
	IfFileExists "$R8" 0 mergeLegacyPathRenameFile
	Delete "$R8"
	mergeLegacyPathRenameFile:
	Rename "$R9" "$R8"

	mergeLegacyPathDone:
	Pop $R2
	Pop $R1
	Pop $R0
	Pop $2
	Pop $1
	Pop $0
FunctionEnd

Function MigrateLegacyDocuments
	Push $0
	Push $1
	Push $R0
	Push $R1
	Push $R2

	IntCmp $MigrateLegacyData 1 migrateContinue
	Call PrintLegacyMigrationSkipReason
	Goto migrateDone

	migrateContinue:
	StrLen $0 "${LegacyHomeFolder}"
	IntCmp $0 0 migrateDone

	StrCpy $R1 "$DOCUMENTS\${LegacyHomeFolder}"
	IfFileExists "$R1" 0 migrateDone

	!insertmacro DetailPrintLogSuffix "Checking legacy launcher data at " "$R1"

	!insertmacro DetailPrintLogSuffix "Migrating legacy launcher data from " "$R1"
	CreateDirectory "$DataDir"

	FindFirst $R2 $1 "$R1\*"
	IfErrors migrateCleanup
	migrateLoop:
	StrCmp $1 "." migrateLoopNext
	StrCmp $1 ".." migrateLoopNext
	StrCmp $1 "launcher" migrateDeleteEntry
	StrCmp $1 "swt" migrateDeleteEntry
	!insertmacro DetailPrintLogSuffix "Merging legacy entry " "$1"
	Push "$DataDir\$1"
	Push "$R1\$1"
	Call MergeLegacyPath
	Goto migrateLoopNext
	migrateDeleteEntry:
	!insertmacro DetailPrintLogSuffix "Deleting obsolete legacy entry " "$1"
	IfFileExists "$R1\$1\*" 0 migrateDeleteFile
	RMDir /r "$R1\$1"
	Goto migrateLoopNext
	migrateDeleteFile:
	Delete "$R1\$1"
	migrateLoopNext:
	ClearErrors
	FindNext $R2 $1
	IfErrors migrateLoopClose
	Goto migrateLoop
	migrateLoopClose:
	FindClose $R2

	migrateCleanup:
	RMDir "$R1"
	!insertmacro DetailPrintLog "Legacy data migration completed."

	migrateDone:
	Pop $R2
	Pop $R1
	Pop $R0
	Pop $1
	Pop $0
FunctionEnd

; Moves root-level data from pre-bootstrap flat installs into bootstrap/.
Function MigrateFlatDataLayout
	Push $0
	Push $1

	IfFileExists "$DataDir" flatMigrateDone

	StrCpy $0 0
	IfFileExists "$INSTDIR\launcher" 0 +2
	StrCpy $0 1
	IfFileExists "$INSTDIR\config.json" 0 +2
	StrCpy $0 1
	IfFileExists "$INSTDIR\instances" 0 +2
	StrCpy $0 1
	IntCmp $0 1 0 flatMigrateDone

	!insertmacro DetailPrintLogSuffix "Migrating flat launcher data into " "$DataDir"
	CreateDirectory "$DataDir"

	IfFileExists "$INSTDIR\launcher" 0 flatMigrateRuntimes
	Rename "$INSTDIR\launcher" "$DataDir\launcher"
	flatMigrateRuntimes:
	IfFileExists "$INSTDIR\runtimes" 0 flatMigrateInstances
	Rename "$INSTDIR\runtimes" "$DataDir\runtimes"
	flatMigrateInstances:
	IfFileExists "$INSTDIR\instances" 0 flatMigrateAssets
	Rename "$INSTDIR\instances" "$DataDir\instances"
	flatMigrateAssets:
	IfFileExists "$INSTDIR\assets" 0 flatMigrateConfig
	Rename "$INSTDIR\assets" "$DataDir\assets"
	flatMigrateConfig:
	IfFileExists "$INSTDIR\config.json" 0 flatMigrateAccounts
	Rename "$INSTDIR\config.json" "$DataDir\config.json"
	flatMigrateAccounts:
	IfFileExists "$INSTDIR\accounts.dat" 0 flatMigrateLogs
	Rename "$INSTDIR\accounts.dat" "$DataDir\accounts.dat"
	flatMigrateLogs:
	IfFileExists "$INSTDIR\logs" 0 flatMigrateDone
	IfFileExists "$DataDir\logs" 0 flatMigrateLogsDir
	Rename "$INSTDIR\logs" "$DataDir\logs"
	Goto flatMigrateDone
	flatMigrateLogsDir:
	CreateDirectory "$DataDir\logs"
	IfFileExists "$INSTDIR\logs\bootstrap.log" 0 flatMigrateLogsCleanup
	Rename "$INSTDIR\logs\bootstrap.log" "$DataDir\logs\bootstrap.log"
	flatMigrateLogsCleanup:
	RMDir "$INSTDIR\logs"

	flatMigrateDone:
	Pop $1
	Pop $0
FunctionEnd

Function DeleteObsoleteNativeCaches
	IfFileExists "$INSTDIR\swt" 0 deleteObsoleteRootFlatLaf
	!insertmacro DetailPrintLogSuffix "Deleting obsolete SWT native cache " "$INSTDIR\swt"
	RMDir /r "$INSTDIR\swt"

	deleteObsoleteRootFlatLaf:
	IfFileExists "$INSTDIR\flatlaf" 0 deleteObsoleteBootstrapSwt
	!insertmacro DetailPrintLogSuffix "Deleting obsolete FlatLaf native cache " "$INSTDIR\flatlaf"
	RMDir /r "$INSTDIR\flatlaf"

	deleteObsoleteBootstrapSwt:
	IfFileExists "$INSTDIR\${BootstrapSubdir}\swt" 0 deleteObsoleteDataFlatLaf
	!insertmacro DetailPrintLogSuffix "Deleting obsolete SWT native cache " "$INSTDIR\${BootstrapSubdir}\swt"
	RMDir /r "$INSTDIR\${BootstrapSubdir}\swt"

	deleteObsoleteDataFlatLaf:
	IfFileExists "$DataDir\flatlaf" 0 deleteObsoleteNatives
	!insertmacro DetailPrintLogSuffix "Deleting obsolete FlatLaf native cache " "$DataDir\flatlaf"
	RMDir /r "$DataDir\flatlaf"

	deleteObsoleteNatives:
	IfFileExists "$DataDir\${NativesSubdir}" 0 deleteObsoleteNativeCachesDone
	!insertmacro DetailPrintLogSuffix "Deleting obsolete native cache " "$DataDir\${NativesSubdir}"
	RMDir /r "$DataDir\${NativesSubdir}"

	deleteObsoleteNativeCachesDone:
FunctionEnd
