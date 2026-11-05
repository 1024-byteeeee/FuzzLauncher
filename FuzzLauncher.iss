#define MyAppName "FuzzLauncher"
#ifndef AppVersion
  #define AppVersion "0.2.0"
#endif
#ifndef SrcDir
  #define SrcDir "dist\FuzzLauncher"
#endif
#ifndef OutputDir
  #define OutputDir "dist"
#endif
#define MyAppPublisher "1024_byteeeee"
#define MyAppExeName "FuzzLauncher.exe"
#define MyAppIcon "src\main\resources\com\fuzzlauncher\launcher.ico"

[Setup]
AppId={{F9EBDB38-42A1-4BDB-8756-D66737E29DDC}
AppName={#MyAppName}
AppVersion={#AppVersion}
AppPublisher={#MyAppPublisher}
DefaultDirName={autopf}\FuzzLauncher
DefaultGroupName=FuzzLauncher
PrivilegesRequired=admin
PrivilegesRequiredOverridesAllowed=commandline
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir={#OutputDir}
OutputBaseFilename=FuzzLauncher-{#AppVersion}-windows-x64-setup
Compression=lzma2
SolidCompression=yes
WizardStyle=classic
DisableWelcomePage=no
SetupIconFile={#MyAppIcon}
WizardImageFile=packaging\wizard-banner.png
WizardSmallImageFile=packaging\wizard-logo.png
UninstallDisplayIcon={app}\{#MyAppExeName}
VersionInfoVersion={#AppVersion}
VersionInfoCompany={#MyAppPublisher}
VersionInfoProductName={#MyAppName}
VersionInfoProductVersion={#AppVersion}
AppPublisherURL=https://github.com/1024-byteeeee/FuzzLauncher
AppSupportURL=https://github.com/1024-byteeeee/FuzzLauncher

[Languages]
Name: "chinesesimplified"; MessagesFile: "packaging\ChineseSimplified.isl"

[Tasks]
Name: "desktopicon"; Description: "创建桌面快捷方式"; Flags: unchecked

[Files]
Source: "{#SrcDir}\*"; DestDir: "{app}"; Excludes: "data\*,game\*,baseVerisons\*"; Flags: ignoreversion recursesubdirs createallsubdirs
Source: "packaging\all-users.install"; DestDir: "{app}"; Flags: ignoreversion; Check: IsAdminInstallMode

[Dirs]
Name: "{app}\baseVerisons"; Check: not IsAdminInstallMode
Name: "{app}\game"; Check: not IsAdminInstallMode
Name: "{app}\data"; Check: not IsAdminInstallMode

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "运行 {#MyAppName}"; Flags: nowait postinstall skipifsilent

[Code]
const
  GWL_STYLE = -16;
  PBS_SMOOTH = 1;
  PBM_SETBARCOLOR = $0409;
  PBM_SETBKCOLOR = $2001;

function InitializeSetup: Boolean;
var
  Scope, ResultCode: Integer;
begin
  Result := True;
  if (not IsAdminInstallMode) or WizardSilent then
    Exit;

  Scope := TaskDialogMsgBox('选择安装范围', '安装器已获得管理员权限。请选择哪些用户可以使用 FuzzLauncher。',
    mbConfirmation, MB_YESNOCANCEL, ['为所有用户安装'#13#10'在这台电脑上为所有用户安装 FuzzLauncher。',
     '仅为当前用户安装'#13#10'安装到启动安装器的 Windows 用户帐户。', '取消'], 0);
  Result := Scope = IDYES;
  // Keep the elevated entry point while letting Inno register a per-user install
  // in the original user's profile, including after a credential prompt.
  if Scope = IDNO then
  begin
    if not ExecAsOriginalUser(ExpandConstant('{srcexe}'), '/CURRENTUSER', '', SW_SHOWNORMAL, ewNoWait, ResultCode) then
      MsgBox('无法继续当前用户安装：' + SysErrorMessage(ResultCode), mbError, MB_OK);
  end;
end;

// Use the classic Windows control rendering without changing the user's theme.
function SetWindowTheme(hwnd: HWND; subAppName, subIdList: String): Integer;
  external 'SetWindowTheme@uxtheme.dll stdcall';
function DwmSetWindowAttribute(hwnd: HWND; attribute: Integer;
  var value: Cardinal; valueSize: Integer): Integer;
  external 'DwmSetWindowAttribute@dwmapi.dll stdcall';
function GetWindowLong(hwnd: HWND; index: Integer): LongInt;
  external 'GetWindowLongW@user32.dll stdcall';
function SetWindowLong(hwnd: HWND; index: Integer; value: LongInt): LongInt;
  external 'SetWindowLongW@user32.dll stdcall';

procedure ClassicProgress(Window: HWND);
begin
  SetWindowTheme(Window, ' ', ' ');
  // Inno creates smooth bars by default. The classic native control draws
  // segmented blocks when PBS_SMOOTH is cleared.
  SetWindowLong(Window, GWL_STYLE, GetWindowLong(Window, GWL_STYLE) and not PBS_SMOOTH);
  SendMessage(Window, PBM_SETBARCOLOR, 0, $00800000);
  SendMessage(Window, PBM_SETBKCOLOR, 0, $00C0C0C0);
end;

procedure ClassicWindow(Window: HWND);
var
  CaptionColor, TextColor: Cardinal;
begin
  // Caption colors are supported by DWM on Windows 11; older systems keep
  // their native title bars. COLORREF is stored in blue-green-red order.
  CaptionColor := $00800000;
  TextColor := $00FFFFFF;
  DwmSetWindowAttribute(Window, 35, CaptionColor, 4);
  DwmSetWindowAttribute(Window, 36, TextColor, 4);
end;

procedure InitializeWizard;
begin
  ClassicWindow(WizardForm.Handle);
  SetWindowTheme(WizardForm.BackButton.Handle, ' ', ' ');
  SetWindowTheme(WizardForm.NextButton.Handle, ' ', ' ');
  SetWindowTheme(WizardForm.CancelButton.Handle, ' ', ' ');
  SetWindowTheme(WizardForm.DirBrowseButton.Handle, ' ', ' ');
  SetWindowTheme(WizardForm.GroupBrowseButton.Handle, ' ', ' ');
  SetWindowTheme(WizardForm.TasksList.Handle, ' ', ' ');
  WizardForm.TasksList.Flat := False;
  SetWindowTheme(WizardForm.RunList.Handle, ' ', ' ');
  WizardForm.RunList.Flat := False;
  ClassicProgress(WizardForm.ProgressGauge.Handle);
end;

procedure InitializeUninstallProgressForm;
begin
  ClassicWindow(UninstallProgressForm.Handle);
  SetWindowTheme(UninstallProgressForm.CancelButton.Handle, ' ', ' ');
  ClassicProgress(UninstallProgressForm.ProgressBar.Handle);
end;
