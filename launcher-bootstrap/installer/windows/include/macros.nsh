; --- Shared macros ---

!macro UninstallBulkRemoveDir PATH
	Push "${PATH}"
	Call un.BulkRemoveDir
!macroend

!macro DeleteManagedBootstrapDir DIRNAME
	!insertmacro UninstallBulkRemoveDir "$DataDir\${DIRNAME}"
!macroend

!macro DeleteManagedBootstrapData
	!insertmacro DeleteManagedBootstrapDir "launcher"
	!insertmacro DeleteManagedBootstrapDir "runtimes"
	!insertmacro DeleteManagedBootstrapDir "natives"
	!insertmacro DeleteManagedBootstrapDir "agents"
	!insertmacro DeleteManagedBootstrapDir "temp"
	!insertmacro DeleteManagedBootstrapDir "webview2"
!macroend

!macro DeleteUserBootstrapData
	!insertmacro DeleteManagedBootstrapDir "instances"
	Delete "$DataDir\config.json"
	Delete "$DataDir\accounts.dat"
	!insertmacro DeleteManagedBootstrapDir "logs"
!macroend

!macro DetailPrintLog LINE
	DetailPrint "${LINE}"
	Push "${LINE}"
	Call AppendInstallLogLine
!macroend

!macro DetailPrintLogSuffix PREFIX VALUE
	DetailPrint "${PREFIX}${VALUE}"
	Push "${VALUE}"
	Push "${PREFIX}"
	Call AppendInstallLogSuffix
!macroend

!macro LogInstalledPath PATH
	Push "${PATH}"
	Call LogInstalledPath
!macroend
