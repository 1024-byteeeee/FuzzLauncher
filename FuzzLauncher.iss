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
DefaultDirName={localappdata}\Programs\FuzzLauncher
DefaultGroupName=FuzzLauncher
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir={#OutputDir}
OutputBaseFilename=FuzzLauncher-{#AppVersion}-windows-x64-setup
Compression=lzma2
SolidCompression=yes
WizardStyle=classic
DisableWelcomePage=no
SetupIconFile={#MyAppIcon}
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

[Dirs]
Name: "{app}\baseVerisons"
Name: "{app}\game"
Name: "{app}\data"

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "运行 {#MyAppName}"; Flags: nowait postinstall skipifsilent

[Code]
// Use the classic Windows control rendering without changing the user's theme.
function SetWindowTheme(hwnd: HWND; subAppName, subIdList: String): Integer;
  external 'SetWindowTheme@uxtheme.dll stdcall';
function DwmSetWindowAttribute(hwnd: HWND; attribute: Integer;
  var value: Cardinal; valueSize: Integer): Integer;
  external 'DwmSetWindowAttribute@dwmapi.dll stdcall';

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
  SetWindowTheme(WizardForm.ProgressGauge.Handle, ' ', ' ');
end;

procedure InitializeUninstallProgressForm;
begin
  ClassicWindow(UninstallProgressForm.Handle);
  SetWindowTheme(UninstallProgressForm.CancelButton.Handle, ' ', ' ');
  SetWindowTheme(UninstallProgressForm.ProgressBar.Handle, ' ', ' ');
end;
