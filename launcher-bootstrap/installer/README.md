# Native installer packaging

The bootstrap installer scripts are Windows-first and free to run for private
servers. Cross-platform packaging starts from the same staged app image:

```bash
./gradlew :launcher-bootstrap:assembleAppImage -Pversion=1.0.0
```

This produces:

- `launcher-bootstrap/build/app-image/runtime/`
- `launcher-bootstrap/build/app-image/app/launcher-bootstrap.jar`
- `launcher-bootstrap/build/app-image/bootstrap/launcher/<timestamp>.jar`
- `launcher-bootstrap/build/app-image/bootstrap/natives/swt/<version>/<artifact>/`
- `launcher-bootstrap/build/app-image/bootstrap/natives/flatlaf/<version>/<platformId>/`
- launcher wrappers (`launcher-bootstrap.cmd`, `launcher-bootstrap`)

On Windows, build a native launcher image before creating installers:

```bash
./gradlew :launcher-bootstrap:createWindowsLauncher -Pversion=1.0.0
```

This produces `launcher-bootstrap/build/windows-app-image/<packageAppName>/`
with `<packageAppName>.exe` as the executable launcher. `packageAppName` in
`bootstrap.properties` is the **display name** and may contain spaces (for
example `Example Launcher`). A sanitized install-dir id is derived automatically
from `packageAppName` (for example `ExampleLauncher` on Windows,
`examplelauncher` on Linux). Override the display name with `-PappName=...` or
the install-dir id with `-PinstallDirName=...`.

## Directory layout

jpackage install artifacts stay at the install root. Bootstrap-managed data
(launcher JAR cache, SWT natives, FlatLaf natives, game runtimes, instances,
config) lives under a `bootstrap/` subfolder.

Windows installed layout:

```
%LOCALAPPDATA%\<installDirName>/
├── <packageAppName>.exe
├── runtime/                         # jpackage JDK (runs the bootstrap UI)
├── app/
│   ├── launcher-bootstrap.jar
│   └── <packageAppName>.cfg
├── install.log
├── uninstall.exe
└── bootstrap/                       # launcher data root (--dir)
    ├── launcher/
    ├── natives/
    │   ├── swt/
    │   └── flatlaf/
    ├── runtimes/                    # Mojang game JVMs
    ├── instances/
    ├── config.json
    └── logs/bootstrap.log
```

This keeps jpackage `runtime/` separate from game `runtimes/`.

## Prerequisites

- JDK 17 with `jlink` and `jpackage`
- SWT platform JARs are resolved automatically from Maven Central and staged
  as native libraries under `bootstrap/natives/swt/<version>/<artifact>/`
- FlatLaf natives are staged under `bootstrap/natives/flatlaf/<version>/<platformId>/`

### Windows

- NSIS 3 (`makensis.exe` on PATH or installed in Program Files)
- WebView2 Evergreen bootstrapper is downloaded automatically when building the Windows installer (`downloadWebView2Bootstrapper`)

Build Setup EXE:

```powershell
gradlew.bat :launcher-bootstrap:packageWindows -Pversion=1.0.0
```

Outputs:

- `launcher-bootstrap/build/installer/windows/<packageAppName> Setup.exe` (for example `Example Launcher Setup.exe`)
- installed shortcuts and post-install launch target `<packageAppName>.exe` (not `javaw.exe`)
- launcher data defaults to `%LOCALAPPDATA%\<installDirName>\bootstrap\`
- required `installBaseDirWindows` in `bootstrap.properties` sets the base
  directory (`%LOCALAPPDATA%` by default) and supports any `%NAME%` environment
  variable expanded on the target machine; override it with
  `-PinstallBaseDir="D:\Launchers"` at build/run time
- the installer directory page allows this location to be changed
- `gradlew.bat :launcher-bootstrap:run` uses the same configured default base
- on install/upgrade, the NSIS installer can migrate **all** legacy data from `%USERPROFILE%\Documents\<legacyHomeFolderWindows>` into `%LOCALAPPDATA%\<installDirName>\bootstrap\`, including unknown files/folders (`legacyHomeFolderWindows` is set in `installer/windows/installer.properties`)
- the import page is shown only when legacy Documents data exists; if bootstrap already exists, import is unchecked by default and the page explains that import deep-merges legacy data and overwrites conflicting files/folders
- import is optional via an **Import data from Documents folder** checkbox (checked by default for fresh installs, unchecked by default when installation data already exists)
- silent install (`/S`) imports legacy data by default when bootstrap is missing; when bootstrap already exists, import is skipped by default — pass `/MIGRATELEGACY=1` to import or `/MIGRATELEGACY=0` to skip
- legacy `launcher/` (and `swt/` if present) in Documents are deleted, not moved; the installer deploys fresh bundled copies under `bootstrap/`
- the legacy Documents folder is fully removed after migration completes
- upgrading from a flat-layout install moves existing root-level data into `bootstrap/` automatically
- the installer replaces `%LOCALAPPDATA%\<installDirName>\bootstrap\launcher\` when the bundled launcher version is **newer or equal** to what is already installed (preserves self-updated JARs only when installed version is newer)
- the installer replaces `%LOCALAPPDATA%\<installDirName>\bootstrap\natives\swt\` **only when the bundled SWT version is newer** than what is already installed (preserves launcher self-updated SWT when re-running an older installer)
- managed caches are installed via **selective extraction**: the installer copies `runtime/`, `app/`, and the exe on every install, and copies `bootstrap/launcher/`, `bootstrap/natives/swt/`, and `bootstrap/natives/flatlaf/` only when gating logic allows (launcher when bundled is newer/equal; SWT and FlatLaf when bundled versions are newer; existing caches are left untouched otherwise)
- installer diagnostics are written to `%LOCALAPPDATA%\<installDirName>\install.log` (copied from the install details list at end of setup; for a full NSIS log including file extraction, run `Setup.exe /LOG="%LOCALAPPDATA%\<installDirName>\install.log"`)
- when WebView2 is not already installed, the installer shows a **WebView2 Runtime** page with an **Install Microsoft Edge WebView2 Runtime (recommended)** checkbox (checked by default); unchecking skips WebView2 installation and the embedded news panel remains unavailable until WebView2 is installed
- the bundled WebView2 bootstrapper runs interactively (`/install`) so users see Microsoft's installer UI, after all launcher files, shortcuts, and registry entries are in place
- silent install (`/S`) installs WebView2 when missing by default; pass `/SKIPWEBVIEW2=1` to skip or `/INSTALLWEBVIEW2=1` to force installation
- WebView2 installation is non-blocking: bootstrapper failure or UAC cancellation logs a warning and setup continues
- WebView2 is a shared system component and is **not** removed during uninstall; only app-specific WebView2 profile data under `bootstrap/webview2/` (or `$INSTDIR\webview2\` for flat layouts) is removed with managed bootstrap data

Windows uninstall behavior:

- default uninstall keeps user data under `bootstrap/` (`instances/`, `logs/`, `config.json`, `accounts.dat`, etc.)
- uninstall shows one confirm page with an optional **Delete user data** checkbox (unchecked by default)
- jpackage binaries (`runtime/`, `app/`, exe) are always removed
- managed bootstrap caches (`launcher/`, `natives/swt/`, `natives/flatlaf/`, `temp/`, `webview2/`, `runtimes/`) are always removed
- Minecraft game files (`assets/`, `libraries/`, `versions/`) are kept
- when **Delete user data** is checked, `instances/`, `config.json`, `accounts.dat`, and `logs/` are removed

### Linux

- `curl`
- `appimagetool`
- Optional: `zsyncmake` for `.zsync` metadata

Build AppImage:

```bash
./gradlew :launcher-bootstrap:packageLinux -Pversion=1.0.0
```

Build AppImage + DEB:

```bash
./gradlew :launcher-bootstrap:packageLinux -Pversion=1.0.0 -PbuildDeb=true
```

Linux outputs always include:

- `launcher-bootstrap/build/installer/linux/<packageAppName>.AppImage`
- `launcher-bootstrap/build/installer/linux/<packageAppName>.tar.gz`
- optional `.deb` when `-PbuildDeb=true` (installs to `/opt/<installDirName>/`, lowercase on Linux)

The DEB package uses a `postinstall` script to replace the installing user's
XDG data directory launcher cache
(`~/.local/share/<homeFolderLinux>/bootstrap/launcher/` by default) and SWT
native cache (`~/.local/share/<homeFolderLinux>/bootstrap/natives/swt/` by default),
plus FlatLaf native cache
(`~/.local/share/<homeFolderLinux>/bootstrap/natives/flatlaf/` by default), from the
installed app payload. It also declares GTK/WebKitGTK package
dependencies for SWT Browser
(`libgtk-3-0` and `libwebkit2gtk-4.1-0 | libwebkit2gtk-4.0-37`).

AppImage and tar.gz include `bootstrap/launcher/`, `bootstrap/natives/swt/`, and
`bootstrap/natives/flatlaf/` as normal app payload directories, but they do not have an
install hook and do not copy them into the user data directory automatically.

Build Linux artifacts from Windows via WSL:

```powershell
gradlew.bat :launcher-bootstrap:packageLinuxWsl -Pversion=1.0.0 -PwslDistro=Ubuntu
```

Build AppImage + DEB from Windows via WSL:

```powershell
gradlew.bat :launcher-bootstrap:packageLinuxWsl -Pversion=1.0.0 -PwslDistro=Ubuntu -PbuildDeb=true
```

Build Linux artifacts via Docker (any host with Docker):

```powershell
gradlew.bat :launcher-bootstrap:packageLinuxDocker -Pversion=1.0.0
```

From Windows with **no host JDK** (full compile inside Docker):

```bat
build.bat --linux
```

That uses the official `gradle:8.14.0-jdk17` image for `clean`, `build`, and `packageLinux`.
Hybrid Windows+Linux builds still use the host JDK for Windows packaging and reuse
host jars inside Docker for the Linux step.

Build AppImage + DEB via Docker:

```powershell
gradlew.bat :launcher-bootstrap:packageLinuxDocker -Pversion=1.0.0 -PbuildDeb=true
```

Override the container image (default `gradle:8.14.0-jdk17`):

```powershell
gradlew.bat :launcher-bootstrap:packageLinuxDocker -PdockerImage=gradle:8.14.0-jdk17
```

Or from `build.bat`:

```bat
build.bat --docker
```

### macOS

Build DMG + PKG + tarball:

```bash
./gradlew :launcher-bootstrap:packageMac -Pversion=1.0.0
```

macOS packages use `packageAppName` for the `.app` bundle name (spaces allowed,
for example `/Applications/SKCraft Launcher.app`). Outputs:

- `launcher-bootstrap/build/installer/macos/<packageAppName>.dmg`
- `launcher-bootstrap/build/installer/macos/<packageAppName>.pkg`
- `launcher-bootstrap/build/installer/macos/<packageAppName>.tar.gz`

The PKG `postinstall` script
replaces bundled launcher, SWT, and FlatLaf data in `~/<homeFolder>/bootstrap/`
from `bootstrap.properties`.
The macOS output is unsigned by default. Notarization is intentionally out of
scope for this initial workflow.

## Private fork override

Bootstrap supports external property overrides:

- JVM property: `-Dcom.skcraft.launcher.bootstrap.propertiesFile=/path/to/bootstrap.properties`
- Sidecar file: place `bootstrap.properties` next to the bootstrap JAR

This lets private servers reuse official installers and only override update
URLs.
