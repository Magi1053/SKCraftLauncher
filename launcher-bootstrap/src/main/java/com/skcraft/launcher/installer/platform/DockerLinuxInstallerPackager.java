package com.skcraft.launcher.installer.platform;

import com.skcraft.launcher.installer.InstallerPackager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class DockerLinuxInstallerPackager extends InstallerPackager.Platform {
    /** Matches gradle/wrapper/gradle-wrapper.properties (JDK 17). */
    private static final String DEFAULT_IMAGE = "gradle:8.14.0-jdk17";
    private static final String CONTAINER_WORKSPACE = "/workspace";
    private static final String CONTAINER_SCRIPT = "/tmp/skcraft-package-linux.sh";

    @Override
    public void run(String[] args) throws Exception {
        if (args.length < 5) {
            throw new IllegalArgumentException(
                    "Usage: InstallerPackager docker-linux <repoDir> <version> <buildDeb> <displayAppName> <installDirName> [image]");
        }
        packageLinuxFromDocker(Paths.get(args[0]), args[1], parseBooleanFlag(args[2]),
                normalizePackageAppName(args[3]), normalizeInstallDirName(args[4]),
                args.length >= 6 ? args[5] : "");
    }

    private void packageLinuxFromDocker(Path repoDir, String version, boolean buildDeb, String displayAppName,
            String linuxInstallDirName, String image) throws Exception {
        String docker = findDocker();
        if (docker == null) {
            throw new IllegalStateException("docker was not found on PATH. Install Docker Desktop or Docker Engine first.");
        }

        String dockerImage = (image == null || image.isBlank()) ? DEFAULT_IMAGE : image.trim();
        String hostRepo = repoDir.toAbsolutePath().normalize().toString();
        String mountSource = hostRepo.replace('\\', '/');

        Path outputDir = repoDir.resolve("launcher-bootstrap/build/installer/linux");
        Path scriptFile = Files.createTempFile("skcraft-package-linux-", ".sh");
        try {
            String script = ""
                    + "#!/usr/bin/env bash\n"
                    + "set -euo pipefail\n"
                    + "export DEBIAN_FRONTEND=noninteractive\n"
                    + "export APPIMAGE_EXTRACT_AND_RUN=1\n"
                    + "missing=\n"
                    + "command -v file >/dev/null 2>&1 || missing=\"$missing file\"\n"
                    + (buildDeb ? "command -v fakeroot >/dev/null 2>&1 || missing=\"$missing fakeroot\"\n" : "")
                    + "if [ -n \"$missing\" ]; then\n"
                    + "  apt-get update -qq\n"
                    + "  apt-get install -y -qq $missing\n"
                    + "fi\n"
                    + "cd \"" + CONTAINER_WORKSPACE + "\"\n"
                    + "command -v gradle >/dev/null 2>&1 || { echo 'Missing gradle in Docker image.' >&2; exit 1; }\n"
                    + "command -v java >/dev/null 2>&1 || { echo 'Missing Java in Docker image.' >&2; exit 1; }\n"
                    + "gradle --no-daemon --project-cache-dir /tmp/skcraft-project-cache "
                    + remoteLinuxPackageGradleArgs(version, buildDeb, displayAppName, linuxInstallDirName) + "\n";
            Files.writeString(scriptFile, script, StandardCharsets.UTF_8);

            String scriptMount = scriptFile.toAbsolutePath().normalize().toString().replace('\\', '/');

            List<String> command = new ArrayList<>();
            command.add(docker);
            command.add("run");
            command.add("--rm");
            command.add("--user");
            command.add("root");
            command.add("--entrypoint");
            command.add("bash");
            command.add("-v");
            command.add(mountSource + ":" + CONTAINER_WORKSPACE);
            command.add("-v");
            command.add(scriptMount + ":" + CONTAINER_SCRIPT + ":ro");
            command.add("-w");
            command.add(CONTAINER_WORKSPACE);
            command.add(dockerImage);
            command.add(CONTAINER_SCRIPT);

            System.out.println("Using Docker image: " + dockerImage);
            System.out.println("Reusing host-built jars; Linux nested Gradle skips compile/shadow tasks.");
            runCommand(command, repoDir, Map.of());

            Path appImage = outputDir.resolve(installerFileName(displayAppName, ".AppImage"));
            Path tarball = outputDir.resolve(installerFileName(displayAppName, ".tar.gz"));
            ensureExists(appImage, "Docker Linux packaging finished without AppImage");
            ensureExists(tarball, "Docker Linux packaging finished without tarball");
            System.out.println("Linux artifacts written to launcher-bootstrap/build/installer/linux");
        } finally {
            Files.deleteIfExists(scriptFile);
        }
    }

    private String findDocker() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            String exe = findOnPath("docker.exe");
            if (exe != null) {
                return exe;
            }
        }
        return findOnPath("docker");
    }
}
