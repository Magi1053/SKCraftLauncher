; --- Uninstall functions ---

Function un.BulkRemoveDir
	Exch $0
	IfFileExists "$0" 0 bulkRemoveDirDone
	DetailPrint "Removing $0..."
	RMDir /r "$0"
	bulkRemoveDirDone:
	Pop $0
FunctionEnd

Function un.UninstallConfirmShow
	StrCpy $un.DeleteUserData 0
	!insertmacro MUI_HEADER_TEXT "Confirm Removal" "Remove ${AppName} from your computer."

	nsDialogs::Create 1018
	Pop $0

	${If} $0 == error
		Abort
	${EndIf}

	${NSD_CreateLabel} 0 0 100% 24u "Are you sure you want to completely remove ${AppName} and all of its components?"
	Pop $0

	${NSD_CreateCheckbox} 0 30u 100% 12u "Delete user data (instances, config, accounts, and logs; Minecraft game files are kept)"
	Pop $un.DeleteUserDataCheckbox
	${NSD_Uncheck} $un.DeleteUserDataCheckbox

	nsDialogs::Show
FunctionEnd

Function un.UninstallConfirmLeave
	${NSD_GetState} $un.DeleteUserDataCheckbox $0
	StrCpy $un.DeleteUserData $0
FunctionEnd
