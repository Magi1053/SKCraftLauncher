# Native installer packaging

The bootstrap installer scripts are Windows-first and free to run for private servers. Cross-platform packaging starts from the same staged app image:

```bash
./gradlew :launcher-bootstrap:assembleAppImage -Pversion=1.0.0
```

This produces:

- `launcher-bootstrap/build/app-image/runtime/`
- `launcher-bootstrap/build/app-image/app/launcher-bootstrap.jar`
- `launcher-bootstrap/build/app-image/bootstrap/launcher/<timestamp>.jar`
- `launcher-bootstrap/build/app-image/bootstrap/natives/weblite/<version>/<platform>/`
- `launcher-bootstrap/build/app-image/bootstrap/natives/flatlaf/<version>/<platformId>/`
- launcher wrappers (`launcher-bootstrap.cmd`, `launcher-bootstrap`)

On Windows, build a native launcher image before creating installers:

```bash
./gradlew :launcher-bootstrap:createWindowsLauncher -Pversion=1.0.0
```

This produces `launcher-bootstrap/build/windows-app-image/<packageAppName>/` with `<packageAppName>.exe` as the executable launcher. `packageAppName` in `bootstrap.properties` is the **display name** and may contain spaces (for example `Example Launcher`). A sanitized install-dir id is derived automatically from `packageAppName` (for example `ExampleLauncher` on Windows, `examplelauncher` on Linux). Override the display name with `-PappName=...` or the install-dir id with `-PinstallDirName=...`.

## Directory layout

jpackage install artifacts stay at the install root. Bootstrap-managed data (launcher JAR cache, weblite native bridge, FlatLaf natives, game runtimes, instances, config) lives under a `bootstrap/` subfolder.

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
    │   ├── weblite/
    │   └── flatlaf/
    ├── runtimes/                    # Mojang game JVMs
    ├── instances/
    ├── config.json
    └── logs/bootstrap.log
```

This keeps jpackage `runtime/` separate from game `runtimes/`.

## Prerequisites

- JDK 17 with `jlink` and `jpackage`
- the host JNI bridge from `ca.weblite:webview` is resolved from Maven Central and staged under `bootstrap/natives/weblite/<version>/<platform>/`
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
- required `installBaseDirWindows` in `bootstrap.properties` sets the base directory (`%LOCALAPPDATA%` by default) and supports any `%NAME%` environment variable expanded on the target machine; override it with `-PinstallBaseDir="D:\Launchers"` at build/run time
- the installer directory page allows this location to be changed
- `gradlew.bat :launcher-bootstrap:run` uses the same configured default base
- on install/upgrade, the NSIS installer can migrate **all** legacy data from `%USERPROFILE%\Documents\<legacyHomeFolderWindows>` into `%LOCALAPPDATA%\<installDirName>\bootstrap\`, including unknown files/folders (enable it by uncommenting `legacyHomeFolderWindows` in `installer/installer.properties`)
- the import page is shown only when legacy Documents data exists; if bootstrap already exists, import is unchecked by default and the page explains that import deep-merges legacy data and overwrites conflicting files/folders
- import is optional via an **Import data from Documents folder** checkbox (checked by default for fresh installs, unchecked by default when installation data already exists)
- silent install (`/S`) imports legacy data by default when bootstrap is missing; when bootstrap already exists, import is skipped by default — pass `/MIGRATELEGACY=1` to import or `/MIGRATELEGACY=0` to skip
- legacy `launcher/` (and `swt/` if present) in Documents are deleted, not moved; the installer deploys fresh bundled copies under `bootstrap/`
- the legacy Documents folder is fully removed after migration completes
- upgrading from a flat-layout install moves existing root-level data into `bootstrap/` automatically
- the installer replaces `%LOCALAPPDATA%\<installDirName>\bootstrap\launcher\` when the bundled launcher version is **newer or equal** to what is already installed (preserves self-updated JARs only when installed version is newer)
- the installer refreshes `%LOCALAPPDATA%\<installDirName>\bootstrap\natives\weblite\` with the bundled host bridge; the launcher can restore a missing or invalid bridge from Maven Central
- managed caches are installed via **selective extraction**: the installer copies `runtime/`, `app`, the exe, and host weblite bridge on every install; launcher JAR replacement still follows the self-update version policy
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
- managed bootstrap caches (`launcher/`, `natives/weblite/`, `natives/flatlaf/`, `agents/`, `temp/`, `webview2/`, `runtimes/`) are always removed
- launcher `cache/` (instance icons) and Minecraft game files (`assets/`, `libraries/`, `versions/`) are kept
- when **Delete user data** is checked, `instances/`, `config.json`, `accounts.dat`, and `logs/` are removed

### Linux

- `curl` and `file`
- `flatpak` and `flatpak-builder`
- `fakeroot` for DEB packaging
- optional `zsyncmake` for AppImage update metadata

Build AppImage + Flatpak + DEB:

```bash
./gradlew :launcher-bootstrap:packageLinux -Pversion=1.0.0
```

From Windows, `build.bat --linux` builds all three formats through WSL.

Linux outputs:

- `launcher-bootstrap/build/installer/linux/<packageAppName>.AppImage`
- `launcher-bootstrap/build/installer/linux/<packageAppName>.flatpak`
- `.deb` (installs to `/opt/<installDirName>/`, lowercase on Linux)

**WebKitGTK / news browser**

| Format | How WebKit is provided |
|--------|------------------------|
| **DEB** | Custom `control` lists WebKit/GTK as **Recommends** (not hard Depends), so `sudo dpkg -i ./….deb` configures even when WebKit is missing. Prefer `sudo apt install ./….deb` to also pull Recommends. News-panel Install button installs WebKit later. `postinstall` only seeds XDG data. |
| **AppImage** | Host must already have WebKitGTK (or news panel falls back). No AppRun package install. Seed on first launch via Bootstrap. |
| **Flatpak** | Sandbox runtime `org.gnome.Platform//50` (includes WebKitGTK). Exports desktop entry + app icon. Linux weblite uses lightweight offscreen rendering; `WEBKIT_DISABLE_COMPOSITING_MODE=1` avoids compositor issues under X11/xrdp. |

The DEB package uses a `postinstall` script to seed the installing user's XDG data directory (`~/.local/share/<packageAppNameLinux>/launcher/` and `…/natives/weblite/` by default) from the installed app payload's `bootstrap/` tree. Before seeding, it can migrate legacy data from `$HOME/<legacyHomeFolderLinux>` into that XDG data directory; enable it by uncommenting `legacyHomeFolderLinux` in `installer/installer.properties`. When omitted, the generated postinstall has no migration step. When enabled, migration deep-merges all files and folders, overwriting conflicts, while obsolete `launcher/` and `swt/` entries are deleted so the package can seed fresh copies. It runs by default only when the destination data directory does not yet exist. Set `MIGRATE_LEGACY=1` when installing to force migration into an existing data directory, or `MIGRATE_LEGACY=0` to skip it.

AppImage and Flatpak retain `bootstrap/` (launcher seed + natives). `BundledSeed` copies the seed into each format's XDG data directory on first launch without replacing later self-updates.

`packageLinux` requires `flatpak-builder` and the `org.gnome.Sdk//50` runtime. The build scripts can install missing tools on apt-based distributions; direct Gradle invocation requires the tools to already be installed. Gradle ensures the GNOME 50 SDK/runtime are installed from Flathub for the current user. It writes `launcher-bootstrap/build/installer/linux/<packageAppName>.flatpak`. Flatpak settings, launcher caches, instances, and game content live under `~/.var/app/com.skcraft.Launcher/`; Bootstrap seeds that sandbox from `/app` on first run. Remove both the app and its sandboxed data with:

```bash
flatpak uninstall --user --delete-data com.skcraft.Launcher
```

AppImage and DEB data remain under `~/.local/share/<packageAppNameLinux>/` and are unaffected by Flatpak removal.

Build AppImage + Flatpak + DEB from Windows via WSL:

```powershell
gradlew.bat :launcher-bootstrap:packageLinuxWsl -Pversion=1.0.0 -PwslDistro=Ubuntu
```

`build.bat --linux` uses the same hybrid path: Windows builds the launcher jars, then WSL packages them. It requires a Windows Java 17 JDK and WSL with Java 17, `curl`, `file`, `fakeroot`, `flatpak`, and `flatpak-builder`. Gradle dependencies persist at `~/.cache/skcraft-gradle` in the WSL distro. Before Gradle starts, `build.bat` prompts to install missing WSL dependencies; answering no skips Linux packaging.

Arch users can install either the AppImage or Flatpak; no separate AUR package is generated.

### macOS

Build DMG + PKG:

```bash
./gradlew :launcher-bootstrap:packageMac -Pversion=1.0.0
```

macOS packages use `packageAppName` for the `.app` bundle name (spaces allowed, for example `/Applications/SKCraft Launcher.app`). Outputs:

- `launcher-bootstrap/build/installer/macos/<packageAppName>.dmg` — bootstrap only (no seed; first run downloads)
- `launcher-bootstrap/build/installer/macos/<packageAppName>.pkg` — includes seed; `postinstall` copies launcher into Application Support

DMG packaging overrides jpackage's default `DMGsetup.scpt` with `installer/macos/jpackage-resources/dmg-setup.scpt` so Finder does not open the volume mid-build (default script calls `open theDisk`). The override still adds an Applications alias.

The PKG `postinstall` script replaces the bundled launcher in `~/Library/Application Support/<packageAppNameMac>/` from `bootstrap.properties`. Before seeding, it can migrate legacy data from `$HOME/<legacyHomeFolderMac>` into that Application Support directory; enable it by uncommenting `legacyHomeFolderMac` in `installer/installer.properties`. When omitted, the generated postinstall has no migration step. When enabled, migration deep-merges all files and folders, overwriting conflicts, while obsolete `launcher/` and `swt/` entries are deleted so the package can seed a fresh launcher. It runs by default only when the destination data directory does not yet exist. Set `MIGRATE_LEGACY=1` when installing to force migration into an existing data directory, or `MIGRATE_LEGACY=0` to skip it. The macOS output is unsigned by default. Notarization is intentionally out of scope for this initial workflow.

## Private fork override

Bootstrap supports external property overrides:

- JVM property: `-Dcom.skcraft.launcher.bootstrap.propertiesFile=/path/to/bootstrap.properties`
- Sidecar file: place `bootstrap.properties` next to the bootstrap JAR

This lets private servers reuse official installers and only override update URLs.
