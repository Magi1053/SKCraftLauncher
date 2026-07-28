package com.skcraft.launcher.installer.platform;

import com.skcraft.launcher.bootstrap.BootstrapUtils;
import com.skcraft.launcher.installer.InstallerPackager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class LinuxInstallerPackager extends InstallerPackager.Platform {
        private static final String LINUX_APP_DIR = "usr/lib/launcher-bootstrap";
        private static final String FLATPAK_APP_ID = "com.skcraft.Launcher";
        private static final String FLATPAK_RUNTIME_VERSION = "50";
        private static final String FLATHUB_REPOSITORY = "https://dl.flathub.org/repo/flathub.flatpakrepo";

        @Override
        public void run(String[] args) throws Exception {
                if (args.length < 4) {
                        throw new IllegalArgumentException(
                                        "Usage: InstallerPackager linux <projectDir> <version> <displayAppName> <installDirName>");
                }
                packageLinux(Paths.get(args[0]), args[1],
                                normalizePackageAppName(args[2]), normalizeInstallDirName(args[3]));
        }

        private void packageLinux(Path projectDir, String version, String displayAppName,
                        String linuxInstallDirName) throws Exception {
                Path appImageDir = projectDir.resolve("build/app-image");
                Path outputDir = projectDir.resolve("build/installer/linux");
                Path iconPng = projectDir.resolve("src/main/resources/com/skcraft/launcher/bootstrapper_icon.png");

                ensureAppImageReady(appImageDir);
                ensureExists(iconPng, "Missing launcher icon PNG");
                Files.createDirectories(outputDir);

                String appImageTool = System.getenv("APPIMAGE_TOOL");
                if (appImageTool == null || appImageTool.isBlank()) {
                        appImageTool = "/tmp/appimagetool-x86_64.AppImage";
                        if (!Files.isExecutable(Paths.get(appImageTool))) {
                                runCommand(List.of(
                                                "curl", "--fail", "--location",
                                                "--output", appImageTool,
                                                "https://github.com/AppImage/AppImageKit/releases/download/continuous/appimagetool-x86_64.AppImage"),
                                                projectDir, Map.of());
                                setExecutable(Paths.get(appImageTool));
                        }
                }

                String runtimeFile = System.getenv("APPIMAGE_RUNTIME");
                if (runtimeFile == null || runtimeFile.isBlank()) {
                        runtimeFile = "/tmp/appimage-runtime-x86_64";
                        if (!Files.exists(Paths.get(runtimeFile))) {
                                runCommand(List.of(
                                                "curl", "--fail", "--location",
                                                "--output", runtimeFile,
                                                "https://github.com/AppImage/type2-runtime/releases/download/continuous/runtime-x86_64"),
                                                projectDir, Map.of());
                                setExecutable(Paths.get(runtimeFile));
                        }
                }

                String appImageName = installerFileName(displayAppName, ".AppImage");
                Path appImagePath = outputDir.resolve(appImageName);
                Path workDir = Files.createTempDirectory("skcraft-appimage-");
                try {
                        Path appDir = workDir.resolve("AppDir");
                        Path appLibDir = appDir.resolve(LINUX_APP_DIR);
                        Files.createDirectories(appLibDir);
                        copyLinuxAppPayload(appImageDir, appLibDir);

                        Files.writeString(appDir.resolve("AppRun"),
                                        "#!/bin/sh\n" +
                                                        "HERE=\"$(dirname \"$(readlink -f \"$0\")\")\"\n" +
                                                        "exec \"$HERE/usr/lib/launcher-bootstrap/launcher-bootstrap\" \"$@\"\n",
                                        StandardCharsets.UTF_8);
                        setExecutable(appDir.resolve("AppRun"));

                        String desktopEntry = "[Desktop Entry]\n" +
                                        "Type=Application\n" +
                                        "Name=" + displayAppName + "\n" +
                                        "Comment=" + APP_DESCRIPTION + "\n" +
                                        "Exec=AppRun %F\n" +
                                        "Icon=launcher-bootstrap\n" +
                                        "StartupWMClass=com.skcraft.launcher.Bootstrap\n" +
                                        "Categories=Game;\n";
                        Files.writeString(appDir.resolve("launcher-bootstrap.desktop"),
                                        desktopEntry, StandardCharsets.UTF_8);
                        Path applicationsDir = appDir.resolve("usr/share/applications");
                        Files.createDirectories(applicationsDir);
                        Files.writeString(applicationsDir.resolve("launcher-bootstrap.desktop"),
                                        desktopEntry, StandardCharsets.UTF_8);

                        Files.copy(iconPng,
                                        appDir.resolve("launcher-bootstrap.png"), StandardCopyOption.REPLACE_EXISTING);

                        Path metainfoDir = appDir.resolve("usr/share/metainfo");
                        Files.createDirectories(metainfoDir);
                        Files.writeString(metainfoDir.resolve("com.skcraft.Launcher.metainfo.xml"),
                                        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                                                        "<component type=\"desktop-application\">\n" +
                                                        "  <id>com.skcraft.Launcher</id>\n" +
                                                        "  <name>" + escapeXml(displayAppName) + "</name>\n" +
                                                        "  <summary>" + APP_DESCRIPTION + "</summary>\n" +
                                                        "  <metadata_license>CC0-1.0</metadata_license>\n" +
                                                        "  <project_license>LGPL-3.0-only</project_license>\n" +
                                                        "  <description>\n" +
                                                        "    <p>" + APP_DESCRIPTION + "</p>\n" +
                                                        "  </description>\n" +
                                                        "  <developer id=\"com.skcraft\">\n" +
                                                        "    <name>SKCraft</name>\n" +
                                                        "  </developer>\n" +
                                                        "  <launchable type=\"desktop-id\">launcher-bootstrap.desktop</launchable>\n"
                                                        +
                                                        "  <content_rating type=\"oars-1.1\"/>\n" +
                                                        "</component>\n",
                                        StandardCharsets.UTF_8);

                        Map<String, String> env = new HashMap<>();
                        env.put("ARCH", "x86_64");
                        env.put("VERSION", version);
                        env.put("APPIMAGETOOL_APP_NAME", displayAppName);
                        if (appImageTool.endsWith(".AppImage")) {
                                env.put("APPIMAGE_EXTRACT_AND_RUN", "1");
                        }

                        runCommand(List.of(
                                        appImageTool,
                                        "--no-appstream",
                                        "--runtime-file", runtimeFile,
                                        appDir.toString(),
                                        appImagePath.toString()), projectDir, env);
                        setExecutable(appImagePath);

                        if (findOnPath("zsyncmake") != null) {
                                runCommand(List.of(
                                                "zsyncmake",
                                                "-o", outputDir.resolve(appImageName + ".zsync").toString(),
                                                appImagePath.toString()), projectDir, Map.of());
                        }
                } finally {
                        deleteDirectory(workDir);
                }

                Path flatpakPath = buildFlatpak(projectDir, outputDir, displayAppName);

                Path debInputDir = createBundledAppInput(appImageDir, "skcraft-deb-input-");
                try {
                        Path resourceDir = prepareJpackageResourceDir(projectDir, "linux", Map.of(
                                        "@LINUX_DATA_DIR@", BootstrapUtils.sanitizeLinuxDataDirName(
                                                        getBootstrapPropertyWithFallback(projectDir,
                                                                        "packageAppNameLinux",
                                                                        "packageAppName")),
                                        "@LINUX_INSTALL_DIR@", linuxInstallDirName,
                                        "@LEGACY_MIGRATION@",
                                        prepareLegacyMigrationScript(projectDir, "legacyHomeFolderLinux")));

                        // Browser libs are Recommends (see installer/linux/jpackage-resources/control),
                        // not hard Depends, so `dpkg -i` can configure without WebKit preinstalled.
                        // The news-panel Install button / apt recommends handle WebKit install.
                        runCommand(List.of(
                                        "jpackage",
                                        "--type", "deb",
                                        "--dest", outputDir.toString(),
                                        "--name", displayAppName,
                                        "--app-version", version,
                                        "--vendor", "SKCraft",
                                        "--description", APP_DESCRIPTION,
                                        "--input", debInputDir.toString(),
                                        "--main-jar", "launcher-bootstrap.jar",
                                        "--runtime-image", appImageDir.resolve(RUNTIME_DIR).toString(),
                                        "--icon", iconPng.toString(),
                                        "--resource-dir", resourceDir.toString(),
                                        "--install-dir", "/opt",
                                        "--linux-package-name", linuxInstallDirName,
                                        "--linux-shortcut",
                                        "--linux-app-category", "Game"), projectDir, Map.of());
                        System.out.println("Built DEB package in " + outputDir);
                } finally {
                        deleteDirectory(debInputDir);
                }

                System.out.println("Built AppImage: " + appImagePath);
                System.out.println("Built Flatpak: " + flatpakPath);
        }

        private Path buildFlatpak(Path projectDir, Path outputDir, String displayAppName) throws Exception {
                String flatpak = findOnPath("flatpak");
                if (flatpak == null) {
                        throw new IllegalStateException("flatpak was not found on PATH. Install Flatpak first.");
                }
                String flatpakBuilder = findOnPath("flatpak-builder");
                if (flatpakBuilder == null) {
                        throw new IllegalStateException(
                                        "flatpak-builder was not found on PATH. Install flatpak-builder first.");
                }

                Path manifestSourceDir = projectDir.resolve("installer/linux/flatpak");
                ensureExists(manifestSourceDir.resolve("com.skcraft.Launcher.yml"), "Missing Flatpak manifest");
                Path stagedManifestDir = prepareFlatpakManifestDir(projectDir, displayAppName);
                Path manifest = stagedManifestDir.resolve("com.skcraft.Launcher.yml");

                String configuredBuildRoot = System.getenv("SKCRAFT_FLATPAK_BUILD_ROOT");
                Path buildRoot = Paths.get(configuredBuildRoot == null || configuredBuildRoot.isBlank()
                                ? "/tmp/skcraft-flatpak-build"
                                : configuredBuildRoot).toAbsolutePath().normalize();
                Path buildDir = buildRoot.resolve("build");
                Path repositoryDir = buildRoot.resolve("repo");
                Path stateDir = buildRoot.resolve("state");
                deleteDirectory(buildDir);
                deleteDirectory(repositoryDir);
                Files.createDirectories(buildRoot);

                runCommand(List.of(
                                flatpak, "--user", "remote-add", "--if-not-exists",
                                "flathub", FLATHUB_REPOSITORY), projectDir, Map.of());
                runCommand(List.of(
                                flatpak, "--user", "install", "-y", "--noninteractive",
                                "flathub",
                                "org.gnome.Platform//" + FLATPAK_RUNTIME_VERSION,
                                "org.gnome.Sdk//" + FLATPAK_RUNTIME_VERSION), projectDir, Map.of());
                runCommand(List.of(
                                flatpakBuilder,
                                "--user",
                                "--force-clean",
                                "--disable-rofiles-fuse",
                                "--state-dir=" + stateDir,
                                "--repo=" + repositoryDir,
                                buildDir.toString(),
                                manifest.toString()), projectDir, Map.of());

                Path flatpakPath = outputDir.resolve(installerFileName(displayAppName, ".flatpak"));
                Files.deleteIfExists(flatpakPath);
                runCommand(List.of(
                                flatpak, "build-bundle",
                                "--runtime-repo=" + FLATHUB_REPOSITORY,
                                repositoryDir.toString(),
                                flatpakPath.toString(),
                                FLATPAK_APP_ID), projectDir, Map.of());
                ensureExists(flatpakPath, "Flatpak packaging finished without a bundle");
                return flatpakPath;
        }

        private Path prepareFlatpakManifestDir(Path projectDir, String displayAppName) throws IOException {
                Path sourceDir = projectDir.resolve("installer/linux/flatpak");
                Path targetDir = projectDir.resolve("build/tmp/flatpak");
                Path appImageDir = projectDir.resolve("build/app-image").toAbsolutePath().normalize();
                Path icon = projectDir.resolve(
                                "src/main/resources/com/skcraft/launcher/bootstrapper_icon.png")
                                .toAbsolutePath().normalize();
                ensureExists(sourceDir, "Missing Flatpak source directory");
                ensureExists(icon, "Missing Flatpak icon");
                deleteDirectory(targetDir);
                Files.createDirectories(targetDir);

                Map<String, String> replacements = Map.of(
                                "@PACKAGE_APP_NAME@", displayAppName,
                                "@APP_DESCRIPTION@", APP_DESCRIPTION,
                                "@FLATPAK_APP_IMAGE_DIR@", appImageDir.toString(),
                                "@FLATPAK_ICON@", icon.toString());

                try (var paths = Files.list(sourceDir)) {
                        for (Path file : paths.toList()) {
                                if (!Files.isRegularFile(file)) {
                                        continue;
                                }
                                String name = file.getFileName().toString();
                                Path target = targetDir.resolve(name);
                                if (name.endsWith(".desktop") || name.endsWith(".xml") || name.endsWith(".yml")) {
                                        String text = Files.readString(file, StandardCharsets.UTF_8)
                                                        .replace("\r\n", "\n")
                                                        .replace('\r', '\n');
                                        for (Map.Entry<String, String> entry : replacements.entrySet()) {
                                                text = text.replace(entry.getKey(), entry.getValue());
                                        }
                                        Files.writeString(target, text, StandardCharsets.UTF_8);
                                } else {
                                        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
                                        if ("flatpak-launcher".equals(name)) {
                                                setExecutable(target);
                                        }
                                }
                        }
                }
                return targetDir;
        }

        private void copyLinuxAppPayload(Path appImageDir, Path targetDir) throws IOException {
                copyDirectory(appImageDir.resolve(RUNTIME_DIR), targetDir.resolve(RUNTIME_DIR));
                copyDirectory(appImageDir.resolve(APP_DIR), targetDir.resolve(APP_DIR));
                copyDirectory(appImageDir.resolve(DATA_SUBDIR), targetDir.resolve(DATA_SUBDIR));
                Files.copy(appImageDir.resolve("launcher-bootstrap"), targetDir.resolve("launcher-bootstrap"),
                                StandardCopyOption.REPLACE_EXISTING);
                setExecutable(targetDir.resolve("launcher-bootstrap"));
        }

        private static String escapeXml(String value) {
                return value.replace("&", "&amp;")
                                .replace("<", "&lt;")
                                .replace(">", "&gt;")
                                .replace("\"", "&quot;")
                                .replace("'", "&apos;");
        }
}
