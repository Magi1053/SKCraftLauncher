# Native installer packaging

The bootstrap installer scripts are Windows-first and free to run for private
servers. Cross-platform packaging starts from the same staged app image:

```bash
./gradlew :launcher-bootstrap:assembleAppImage -Pversion=1.0.0
```

This produces:

- `launcher-bootstrap/build/app-image/runtime/`
- `launcher-bootstrap/build/app-image/app/launcher-bootstrap.jar`
- launcher wrappers (`launcher-bootstrap.cmd`, `launcher-bootstrap`)

On Windows, build a native launcher image before creating installers:

```bash
./gradlew :launcher-bootstrap:createWindowsLauncher -Pversion=1.0.0
```

This produces `launcher-bootstrap/build/windows-app-image/SKCraft Launcher/`
with `SKCraft Launcher.exe` as the executable launcher.

## Prerequisites

- JDK 17 with `jlink` and `jpackage`
- OpenJFX jmods are resolved automatically from Maven Central

### Windows

- NSIS 3 (`makensis.exe` on PATH or installed in Program Files)

Build Setup EXE:

```powershell
gradlew.bat :launcher-bootstrap:packageWindows -Pversion=1.0.0
```

Outputs:

- `launcher-bootstrap/build/installer/windows/SKCraftLauncherSetup.exe`
- installed shortcuts and post-install launch target `SKCraft Launcher.exe` (not `javaw.exe`)
- Windows launcher data defaults to `%LOCALAPPDATA%\SKCraft Launcher\` (the install directory)

Windows uninstall behavior:

- default uninstall keeps instance data (`instances/`, `config.json`, `accounts.dat`, `assets/`)
- uninstall shows one confirm page with an optional **Delete instance data** checkbox (unchecked by default)
- runtime/app launcher binaries are always removed

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

- `launcher-bootstrap/build/installer/linux/launcher-bootstrap-<version>-x86_64.AppImage`
- `launcher-bootstrap/build/installer/linux/launcher-bootstrap-<version>-linux.tar.gz`
- optional `.deb` when `-PbuildDeb=true`

Build Linux artifacts from Windows via WSL:

```powershell
gradlew.bat :launcher-bootstrap:packageLinuxWsl -Pversion=1.0.0 -PwslDistro=Ubuntu
```

Build AppImage + DEB from Windows via WSL:

```powershell
gradlew.bat :launcher-bootstrap:packageLinuxWsl -Pversion=1.0.0 -PwslDistro=Ubuntu -PbuildDeb=true
```

### macOS

Build DMG + tarball:

```bash
./gradlew :launcher-bootstrap:packageMac -Pversion=1.0.0
```

The macOS output is unsigned by default. Notarization is intentionally out of
scope for this initial workflow.

## Private fork override

Bootstrap supports external property overrides:

- JVM property: `-Dcom.skcraft.launcher.bootstrap.propertiesFile=/path/to/bootstrap.properties`
- Sidecar file: place `bootstrap.properties` next to the bootstrap JAR

This lets private servers reuse official installers and only override update
URLs.
