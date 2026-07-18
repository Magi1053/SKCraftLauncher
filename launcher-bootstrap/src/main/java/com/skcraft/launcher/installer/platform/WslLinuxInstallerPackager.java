package com.skcraft.launcher.installer.platform;

import com.skcraft.launcher.installer.InstallerPackager;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class WslLinuxInstallerPackager extends InstallerPackager.Platform {

    @Override
    public void run(String[] args) throws Exception {
        if (args.length < 5) {
            throw new IllegalArgumentException(
                    "Usage: InstallerPackager wsl-linux <repoDir> <version> <buildDeb> <displayAppName> <installDirName> [distro]");
        }
        packageLinuxFromWsl(Paths.get(args[0]), args[1], parseBooleanFlag(args[2]),
                normalizePackageAppName(args[3]), normalizeInstallDirName(args[4]),
                args.length >= 6 ? args[5] : "");
    }

    private void packageLinuxFromWsl(Path repoDir, String version, boolean buildDeb, String displayAppName,
            String linuxInstallDirName, String distro) throws Exception {
        String wsl = findOnPath("wsl.exe");
        if (wsl == null) {
            throw new IllegalStateException("wsl.exe was not found on PATH. Install WSL first.");
        }

        List<String> wslArgs = new ArrayList<>();
        wslArgs.add(wsl);
        if (distro != null && !distro.isBlank()) {
            wslArgs.add("-d");
            wslArgs.add(distro);
            runCommand(append(wslArgs, "--", "echo", "WSL_OK"), repoDir, Map.of());
        }

        String repoForWsl = repoDir.toAbsolutePath().toString().replace('\\', '/');
        String wslRepo = runAndCapture(append(wslArgs, "wslpath", "-a", repoForWsl), repoDir, Map.of()).trim();
        if (wslRepo.isBlank() || !wslRepo.startsWith("/")) {
            throw new IllegalStateException("Failed to resolve valid WSL path for repo directory: " + repoDir);
        }

        String gradleArgs = remoteLinuxPackageGradleArgs(version, buildDeb, displayAppName, linuxInstallDirName);
        String bashCommand = "set -euo pipefail; " +
                "cd \"" + wslRepo + "\"; " +
                "if ! command -v java >/dev/null 2>&1; then echo 'Missing Java in WSL distro. Install OpenJDK 17.' >&2; exit 1; fi; "
                +
                "trap 'rm -f ./gradlew-wsl' EXIT; " +
                "tr -d '\\r' < ./gradlew > ./gradlew-wsl; " +
                "chmod +x ./gradlew-wsl; " +
                "GRADLE_USER_HOME=/tmp/skcraft-gradle ./gradlew-wsl --no-daemon --project-cache-dir /tmp/skcraft-project-cache "
                + gradleArgs;

        System.out.println("Reusing host-built jars; Linux nested Gradle skips compile/shadow tasks.");
        runCommand(append(wslArgs, "bash", "-lc", bashCommand), repoDir, Map.of());
        System.out.println("Linux artifacts written to launcher-bootstrap/build/installer/linux");
    }
}
