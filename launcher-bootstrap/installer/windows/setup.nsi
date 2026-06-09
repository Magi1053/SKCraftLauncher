!include "MUI2.nsh"
!include "nsDialogs.nsh"
!include "LogicLib.nsh"

!ifndef MyAppVersion
  !define MyAppVersion "1.0.0"
!endif

!ifndef AppImageDir
  !define AppImageDir "${__FILEDIR__}\..\..\build\windows-app-image\SKCraft Launcher"
!endif

!ifndef OutputDir
  !define OutputDir "${__FILEDIR__}\..\..\build\installer\windows"
!endif
!ifndef IconIco
  !define IconIco "${__FILEDIR__}\..\..\build\tmp\windows\icon.ico"
!endif

!define AppName "SKCraft Launcher"
!define AppId "SKCraftLauncher"

Unicode True
Name "${AppName}"
OutFile "${OutputDir}\SKCraftLauncherSetup.exe"
InstallDir "$LOCALAPPDATA\${AppName}"
RequestExecutionLevel user
SetCompressor /SOLID lzma
ShowInstDetails show
ShowUninstDetails show

!define MUI_ABORTWARNING
!define MUI_ICON "${IconIco}"
!define MUI_UNICON "${IconIco}"
!define MUI_FINISHPAGE_RUN "$INSTDIR\SKCraft Launcher.exe"
!define MUI_FINISHPAGE_RUN_TEXT "Launch SKCraft Launcher"
!define MUI_FINISHPAGE_SHOWREADME ""
!define MUI_FINISHPAGE_SHOWREADME_TEXT "Create a desktop shortcut"
!define MUI_FINISHPAGE_SHOWREADME_FUNCTION CreateDesktopShortcut

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH

UninstPage custom un.UninstallConfirmShow un.UninstallConfirmLeave
!insertmacro MUI_UNPAGE_INSTFILES

!insertmacro MUI_LANGUAGE "English"

Section "SKCraft Launcher" SEC_MAIN
  SectionIn RO
  SetShellVarContext current

  SetOutPath "$INSTDIR"
  File /r "${AppImageDir}\*"
  File "${IconIco}"

  WriteUninstaller "$INSTDIR\uninstall.exe"

  CreateDirectory "$SMPROGRAMS\SKCraft Launcher"
  SetOutPath "$INSTDIR"
  CreateShortcut "$SMPROGRAMS\SKCraft Launcher\SKCraft Launcher.lnk" "$INSTDIR\SKCraft Launcher.exe" "" "$INSTDIR\icon.ico"
  CreateShortcut "$SMPROGRAMS\SKCraft Launcher\Uninstall SKCraft Launcher.lnk" "$INSTDIR\uninstall.exe" "" "$INSTDIR\icon.ico"

  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${AppId}" "DisplayName" "${AppName}"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${AppId}" "DisplayVersion" "${MyAppVersion}"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${AppId}" "DisplayIcon" "$INSTDIR\icon.ico"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${AppId}" "UninstallString" '"$INSTDIR\uninstall.exe"'
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${AppId}" "NoModify" 1
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${AppId}" "NoRepair" 1
SectionEnd

Function CreateDesktopShortcut
  SetShellVarContext current
  SetOutPath "$INSTDIR"
  CreateShortcut "$DESKTOP\SKCraft Launcher.lnk" "$INSTDIR\SKCraft Launcher.exe" "" "$INSTDIR\icon.ico"
FunctionEnd

Var un.DeleteUserDataCheckbox
Var un.DeleteUserData

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

  ${NSD_CreateCheckbox} 0 30u 100% 12u "Delete instance data (config, accounts, instances, assets)"
  Pop $un.DeleteUserDataCheckbox
  ${NSD_Uncheck} $un.DeleteUserDataCheckbox

  nsDialogs::Show
FunctionEnd

Function un.UninstallConfirmLeave
  ${NSD_GetState} $un.DeleteUserDataCheckbox $0
  StrCpy $un.DeleteUserData $0
FunctionEnd

Section "Uninstall" un.SEC_MAIN
  SectionIn RO
  SetShellVarContext current
  Delete "$DESKTOP\SKCraft Launcher.lnk"
  Delete "$SMPROGRAMS\SKCraft Launcher\SKCraft Launcher.lnk"
  Delete "$SMPROGRAMS\SKCraft Launcher\Uninstall SKCraft Launcher.lnk"
  RMDir "$SMPROGRAMS\SKCraft Launcher"

  Delete "$INSTDIR\SKCraft Launcher.exe"
  Delete "$INSTDIR\SKCraft Launcher.cfg"
  Delete "$INSTDIR\icon.ico"
  Delete "$INSTDIR\uninstall.exe"
  RMDir /r "$INSTDIR\runtime"
  RMDir /r "$INSTDIR\app"
  RMDir /r "$INSTDIR\launcher"

  Call un.DeleteUserDataIfSelected
  RMDir "$INSTDIR"

  DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\${AppId}"
SectionEnd

Function un.DeleteUserDataIfSelected
  IntCmp $un.DeleteUserData 1 0 done
    RMDir /r "$INSTDIR\instances"
    Delete "$INSTDIR\config.json"
    Delete "$INSTDIR\accounts.dat"
    RMDir /r "$INSTDIR\assets"
done:
FunctionEnd
