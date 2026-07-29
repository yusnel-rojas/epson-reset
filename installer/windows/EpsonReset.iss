; Inno Setup script for the Windows installer.
;
; Compose Desktop's own Windows target is an .msi, which needs the WiX toolset on the build
; machine; Inno Setup installs from Chocolatey in one line and produces a smaller, friendlier
; installer. The workflow builds the jpackage app image with `createDistributable` and points this
; script at it.
;
; Built by .github/workflows/build.yml. Locally:
;   ./gradlew createDistributable
;   iscc installer\windows\EpsonReset.iss /DAppVersion=1.2.0

#define AppName "Epson Reset"
#define AppPublisher "redlabs"
#define AppExeName "EpsonReset.exe"
#define AppId "{{ED1ADA46-A1B1-469C-BDDF-E998262C10F0}"

#ifndef AppVersion
#define AppVersion "1.0.0"
#endif

; jpackage's app image. "main" (not "main-release") — this project has no ProGuard build type.
#ifndef SourceDir
#define SourceDir "..\..\build\compose\binaries\main\app\EpsonReset"
#endif

#ifndef OutputDir
#define OutputDir "..\..\build\installer\windows"
#endif

[Setup]
AppId={#AppId}
AppName={#AppName}
AppVersion={#AppVersion}
AppVerName={#AppName} {#AppVersion}
AppPublisher={#AppPublisher}
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=no
OutputDir={#OutputDir}
OutputBaseFilename=EpsonReset-windows-x64
SetupIconFile=..\..\src\main\icons\windows\EpsonReset.ico
UninstallDisplayIcon={app}\{#AppExeName}
Compression=lzma2/ultra64
SolidCompression=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
WizardStyle=modern
CloseApplications=yes
RestartApplications=no

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "startmenuicon"; Description: "Create a Start Menu shortcut"; GroupDescription: "Shortcuts:"; Flags: checkedonce
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Shortcuts:"; Flags: unchecked

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\{#AppName}"; Filename: "{app}\{#AppExeName}"; WorkingDir: "{app}"; Tasks: startmenuicon
Name: "{group}\Uninstall {#AppName}"; Filename: "{uninstallexe}"; Tasks: startmenuicon
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExeName}"; WorkingDir: "{app}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExeName}"; Description: "{cm:LaunchProgram,{#AppName}}"; Flags: nowait postinstall skipifsilent unchecked
