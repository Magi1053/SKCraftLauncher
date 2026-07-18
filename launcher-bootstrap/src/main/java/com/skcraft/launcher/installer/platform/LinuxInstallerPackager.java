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
        private static final String LINUX_BROWSER_DEPS = "libgtk-3-0, libwebkit2gtk-4.1-0 | libwebkit2gtk-4.0-37";
        private static final String LINUX_APP_DIR = "usr/lib/launcher-bootstrap";

        @Override
        public void run(String[] args) throws Exception {
                if (args.length < 5) {
                        throw new IllegalArgumentException(
                                        "Usage: InstallerPackager linux <projectDir> <version> <buildDeb> <displayAppName> <installDirName>");
                }
                packageLinux(Paths.get(args[0]), args[1], parseBooleanFlag(args[2]),
                                normalizePackageAppName(args[3]), normalizeInstallDirName(args[4]));
        }

        private void packageLinux(Path projectDir, String version, boolean buildDeb, String displayAppName,
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

                        Files.writeString(appDir.resolve("launcher-bootstrap.desktop"),
                                        "[Desktop Entry]\n" +
                                                        "Type=Application\n" +
                                                        "Name=" + displayAppName + "\n" +
                                                        "Exec=AppRun %F\n" +
                                                        "Icon=launcher-bootstrap\n" +
                                                        "StartupWMClass=com.skcraft.launcher.Bootstrap\n" +
                                                        "Categories=Game;\n",
                                        StandardCharsets.UTF_8);

                        Files.copy(iconPng,
                                        appDir.resolve("launcher-bootstrap.png"), StandardCopyOption.REPLACE_EXISTING);

                        Map<String, String> env = new HashMap<>();
                        env.put("ARCH", "x86_64");
                        env.put("VERSION", version);
                        env.put("APPIMAGETOOL_APP_NAME", displayAppName);
                        if (appImageTool.endsWith(".AppImage")) {
                                env.put("APPIMAGE_EXTRACT_AND_RUN", "1");
                        }

                        runCommand(List.of(
                                        appImageTool,
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

                Path linuxTarball = outputDir.resolve(installerFileName(displayAppName, ".tar.gz"));
                runCommand(List.of(
                                "tar",
                                "-C", appImageDir.toString(),
                                "-czf", linuxTarball.toString(),
                                "."), projectDir, Map.of());

                if (buildDeb) {
                        Path debInputDir = createBundledAppInput(appImageDir, "skcraft-deb-input-");
                        try {
                                Path resourceDir = prepareJpackageResourceDir(projectDir, "linux", Map.of(
                                                "@LINUX_DATA_DIR@", BootstrapUtils.sanitizeLinuxDataDirName(
                                                                getBootstrapPropertyWithFallback(projectDir,
                                                                                "packageAppNameLinux",
                                                                                "packageAppName")),
                                                "@LINUX_INSTALL_DIR@", linuxInstallDirName));

                                runCommand(List.of(
                                                "jpackage",
                                                "--type", "deb",
                                                "--dest", outputDir.toString(),
                                                "--name", linuxInstallDirName,
                                                "--app-version", version,
                                                "--vendor", "SKCraft",
                                                "--input", debInputDir.toString(),
                                                "--main-jar", "launcher-bootstrap.jar",
                                                "--runtime-image", appImageDir.resolve(RUNTIME_DIR).toString(),
                                                "--icon", iconPng.toString(),
                                                "--resource-dir", resourceDir.toString(),
                                                "--linux-package-deps", LINUX_BROWSER_DEPS,
                                                "--linux-shortcut",
                                                "--linux-app-category", "Game"), projectDir, Map.of());
                                System.out.println("Built DEB package in " + outputDir);
                        } finally {
                                deleteDirectory(debInputDir);
                        }
                }

                System.out.println("Built AppImage: " + appImagePath);
                System.out.println("Built Linux tarball: " + linuxTarball);
        }

        private void copyLinuxAppPayload(Path appImageDir, Path targetDir) throws IOException {
                copyDirectory(appImageDir.resolve(RUNTIME_DIR), targetDir.resolve(RUNTIME_DIR));
                copyDirectory(appImageDir.resolve(APP_DIR), targetDir.resolve(APP_DIR));
                copyDirectory(appImageDir.resolve(DATA_SUBDIR), targetDir.resolve(DATA_SUBDIR));
                Files.copy(appImageDir.resolve("launcher-bootstrap"), targetDir.resolve("launcher-bootstrap"),
                                StandardCopyOption.REPLACE_EXISTING);
                setExecutable(targetDir.resolve("launcher-bootstrap"));
        }
}
