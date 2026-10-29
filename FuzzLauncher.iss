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
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir={#OutputDir}
OutputBaseFilename=FuzzLauncher-{#AppVersion}-windows-x64-setup
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
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
Source: "{#SrcDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Dirs]
Name: "{app}\baseVerisons"; Permissions: users-modify

[Icons]
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#MyAppExeName}"; Description: "运行 {#MyAppName}"; Flags: nowait postinstall skipifsilent
