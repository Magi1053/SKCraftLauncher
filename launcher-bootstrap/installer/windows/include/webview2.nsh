; --- WebView2 page, detection, and install ---

Function DetectWebView2Runtime
	Push $0
	StrCpy $R0 ""

	ClearErrors
	ReadRegStr $0 HKCU "Software\Microsoft\EdgeUpdate\Clients\${WebView2ClientGuid}" "pv"
	IfErrors detectWebView2Hklm32
	StrCmp $0 "" detectWebView2Hklm32
	StrCmp $0 "0.0.0.0" detectWebView2Hklm32
	StrCpy $R0 $0
	Goto detectWebView2Done

	detectWebView2Hklm32:
	ClearErrors
	ReadRegStr $0 HKLM "Software\Microsoft\EdgeUpdate\Clients\${WebView2ClientGuid}" "pv"
	IfErrors detectWebView2HklmWow64
	StrCmp $0 "" detectWebView2HklmWow64
	StrCmp $0 "0.0.0.0" detectWebView2HklmWow64
	StrCpy $R0 $0
	Goto detectWebView2Done

	detectWebView2HklmWow64:
	ClearErrors
	ReadRegStr $0 HKLM "Software\WOW6432Node\Microsoft\EdgeUpdate\Clients\${WebView2ClientGuid}" "pv"
	IfErrors detectWebView2Done
	StrCmp $0 "" detectWebView2Done
	StrCmp $0 "0.0.0.0" detectWebView2Done
	StrCpy $R0 $0

	detectWebView2Done:
	Pop $0
FunctionEnd

Function PrepareWebView2
	Push $0
	Push $1

	StrCpy $ShouldInstallWebView2 0
	StrCpy $ForceInstallWebView2 0
	StrCpy $WebView2Installed 0

	Call DetectWebView2Runtime
	StrCmp $R0 "" prepareWebView2Missing prepareWebView2Installed

	prepareWebView2Installed:
	StrCpy $WebView2Installed 1
	Goto prepareWebView2Options

	prepareWebView2Missing:
	StrCpy $ShouldInstallWebView2 1

	prepareWebView2Options:
	${GetParameters} $0
	ClearErrors
	${GetOptions} $0 "/SKIPWEBVIEW2=1" $1
	IfErrors +3
	StrCpy $ShouldInstallWebView2 0
	Goto prepareWebView2Done
	ClearErrors
	${GetOptions} $0 "/INSTALLWEBVIEW2=1" $1
	IfErrors prepareWebView2Done
	StrCpy $ShouldInstallWebView2 1
	StrCpy $ForceInstallWebView2 1

	prepareWebView2Done:
	Pop $1
	Pop $0
FunctionEnd

Function WebView2PageShow
	Call PrepareWebView2
	StrCmp $WebView2Installed 1 webView2PageSkip

	!insertmacro MUI_HEADER_TEXT "WebView2 Runtime" "Choose whether to install Microsoft Edge WebView2 Runtime."

	nsDialogs::Create 1018
	Pop $0

	${If} $0 == error
		Abort
	${EndIf}

	${NSD_CreateLabel} 0 0 100% 62u \
		"${AppName} uses Microsoft Edge WebView2 for the embedded news panel.$\r$\n$\r$\nWebView2 is not currently installed. Install it now, or skip this step and install it later from Microsoft."
	Pop $0

	${NSD_CreateCheckbox} 0 72u 100% 12u "Install Microsoft Edge WebView2 Runtime (recommended)"
	Pop $WebView2Checkbox
	IntCmp $ShouldInstallWebView2 1 webView2PageCheck webView2PageUncheck webView2PageUncheck

	webView2PageCheck:
	${NSD_Check} $WebView2Checkbox
	Goto webView2PageShowDialog

	webView2PageUncheck:
	${NSD_Uncheck} $WebView2Checkbox

	webView2PageShowDialog:
	nsDialogs::Show
	Return

	webView2PageSkip:
	Abort
FunctionEnd

Function WebView2PageLeave
	${NSD_GetState} $WebView2Checkbox $ShouldInstallWebView2
FunctionEnd

Function InstallWebView2IfRequested
	Push $0

	StrCmp $ShouldInstallWebView2 1 installWebView2CheckInstalled
	Goto installWebView2Done

	installWebView2CheckInstalled:
	StrCmp $ForceInstallWebView2 1 installWebView2Start
	Call DetectWebView2Runtime
	StrCmp $R0 "" installWebView2Start
	!insertmacro DetailPrintLogSuffix "Microsoft Edge WebView2 Runtime already installed: " "$R0"
	Goto installWebView2Done

	installWebView2Start:
	!insertmacro DetailPrintLog "Installing Microsoft Edge WebView2 Runtime."
	InitPluginsDir
	SetOutPath "$PLUGINSDIR"
	File "${WebView2Bootstrapper}"

	${If} ${Silent}
		ExecWait '"$PLUGINSDIR\${WebView2BootstrapperFileName}" /silent /install' $0
	${Else}
		ExecWait '"$PLUGINSDIR\${WebView2BootstrapperFileName}" /install' $0
	${EndIf}

	IfErrors installWebView2LaunchFailed
	StrCmp $0 0 installWebView2Installed installWebView2NonZero

	installWebView2Installed:
	!insertmacro DetailPrintLog "Microsoft Edge WebView2 Runtime installer completed."
	Goto installWebView2RestoreOutPath

	installWebView2NonZero:
	!insertmacro DetailPrintLogSuffix "Microsoft Edge WebView2 Runtime installer exited with code " "$0"
	Goto installWebView2RestoreOutPath

	installWebView2LaunchFailed:
	!insertmacro DetailPrintLog "Microsoft Edge WebView2 Runtime installer could not be started; continuing."

	installWebView2RestoreOutPath:
	SetOutPath "$INSTDIR"

	installWebView2Done:
	Pop $0
FunctionEnd
