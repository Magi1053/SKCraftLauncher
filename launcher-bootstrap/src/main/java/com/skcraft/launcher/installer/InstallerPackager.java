package com.skcraft.launcher.installer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class InstallerPackager {
    private InstallerPackager() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            throw new IllegalArgumentException("Usage: InstallerPackager <windows|linux|mac|wsl-linux> ...");
        }

        String mode = args[0].toLowerCase(Locale.ROOT);
        switch (mode) {
            case "windows":
                if (args.length < 3) {
                    throw new IllegalArgumentException("Usage: InstallerPackager windows <projectDir> <version>");
                }
                packageWindows(Paths.get(args[1]), args[2]);
                break;
            case "linux":
                if (args.length < 4) {
                    throw new IllegalArgumentException("Usage: InstallerPackager linux <projectDir> <version> <buildDeb>");
                }
                packageLinux(Paths.get(args[1]), args[2], parseBooleanFlag(args[3]));
                break;
            case "mac":
                if (args.length < 3) {
                    throw new IllegalArgumentException("Usage: InstallerPackager mac <projectDir> <version>");
                }
                packageMac(Paths.get(args[1]), args[2]);
                break;
            case "wsl-linux":
                if (args.length < 4) {
                    throw new IllegalArgumentException("Usage: InstallerPackager wsl-linux <repoDir> <version> <buildDeb> [distro]");
                }
                packageLinuxFromWsl(Paths.get(args[1]), args[2], parseBooleanFlag(args[3]), args.length >= 5 ? args[4] : "");
                break;
            default:
                throw new IllegalArgumentException("Unsupported mode: " + mode);
        }
    }

    private static void packageWindows(Path projectDir, String version) throws Exception {
        Path appImageDir = projectDir.resolve("build/windows-app-image/SKCraft Launcher");
        Path outputDir = projectDir.resolve("build/installer/windows");
        Path iconPng = projectDir.resolve("src/main/resources/com/skcraft/launcher/bootstrapper_icon.png");
        Path iconIco = projectDir.resolve("build/tmp/windows/icon.ico");
        Path setupScript = projectDir.resolve("installer/windows/setup.nsi");

        ensureExists(appImageDir, "Windows app image not found");
        ensureExists(iconPng, "Launcher icon PNG not found");
        ensureExists(setupScript, "NSIS setup script not found");
        Files.createDirectories(outputDir);
        Files.deleteIfExists(outputDir.resolve("icon.ico"));

        writeIcoFromPng(iconPng, iconIco);

        String makensis = findWindowsTool("makensis.exe",
                List.of(
                        Paths.get(System.getenv("ProgramFiles(x86)"), "NSIS", "makensis.exe"),
                        Paths.get(System.getenv("ProgramFiles"), "NSIS", "makensis.exe")
                ));

        List<String> command = List.of(
                makensis,
                "/DMyAppVersion=" + version,
                "/DAppImageDir=" + appImageDir.toAbsolutePath(),
                "/DOutputDir=" + outputDir.toAbsolutePath(),
                "/DIconIco=" + iconIco.toAbsolutePath(),
                setupScript.toAbsolutePath().toString()
        );
        runCommand(command, projectDir, Map.of());
        System.out.println("Windows installer written to " + outputDir.resolve("SKCraftLauncherSetup.exe"));
    }

    private static void packageLinux(Path projectDir, String version, boolean buildDeb) throws Exception {
        Path appImageDir = projectDir.resolve("build/app-image");
        Path runtimeDir = appImageDir.resolve("runtime");
        Path appJar = appImageDir.resolve("app/launcher-bootstrap.jar");
        Path outputDir = projectDir.resolve("build/installer/linux");
        Path iconPng = projectDir.resolve("src/main/resources/com/skcraft/launcher/bootstrapper_icon.png");

        ensureExists(runtimeDir, "Missing app image runtime");
        ensureExists(appJar, "Missing app image jar");
        ensureExists(iconPng, "Missing launcher icon PNG");
        Files.createDirectories(outputDir);

        String appImageTool = System.getenv("APPIMAGE_TOOL");
        if (appImageTool == null || appImageTool.isBlank()) {
            appImageTool = "/tmp/appimagetool-x86_64.AppImage";
            if (!Files.isExecutable(Paths.get(appImageTool))) {
                runCommand(List.of(
                        "curl", "--fail", "--location",
                        "--output", appImageTool,
                        "https://github.com/AppImage/AppImageKit/releases/download/continuous/appimagetool-x86_64.AppImage"
                ), projectDir, Map.of());
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
                        "https://github.com/AppImage/type2-runtime/releases/download/continuous/runtime-x86_64"
                ), projectDir, Map.of());
                setExecutable(Paths.get(runtimeFile));
            }
        }

        String appImageName = "launcher-bootstrap-" + version + "-x86_64.AppImage";
        Path appImagePath = outputDir.resolve(appImageName);
        Path workDir = Files.createTempDirectory("skcraft-appimage-");
        try {
            Path appDir = workDir.resolve("AppDir");
            Files.createDirectories(appDir.resolve("usr/lib/launcher-bootstrap"));

            copyDirectory(runtimeDir, appDir.resolve("usr/lib/launcher-bootstrap/runtime"));
            copyDirectory(appImageDir.resolve("app"), appDir.resolve("usr/lib/launcher-bootstrap/app"));
            Files.copy(appImageDir.resolve("launcher-bootstrap"), appDir.resolve("usr/lib/launcher-bootstrap/launcher-bootstrap"),
                    StandardCopyOption.REPLACE_EXISTING);
            setExecutable(appDir.resolve("usr/lib/launcher-bootstrap/launcher-bootstrap"));

            Files.writeString(appDir.resolve("AppRun"),
                    "#!/bin/sh\n" +
                            "HERE=\"$(dirname \"$(readlink -f \"$0\")\")\"\n" +
                            "exec \"$HERE/usr/lib/launcher-bootstrap/launcher-bootstrap\" \"$@\"\n",
                    StandardCharsets.UTF_8);
            setExecutable(appDir.resolve("AppRun"));

            Files.writeString(appDir.resolve("launcher-bootstrap.desktop"),
                    "[Desktop Entry]\n" +
                            "Type=Application\n" +
                            "Name=SKCraft Launcher\n" +
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
            env.put("APPIMAGETOOL_APP_NAME", "SKCraft Launcher");
            if (appImageTool.endsWith(".AppImage")) {
                env.put("APPIMAGE_EXTRACT_AND_RUN", "1");
            }

            runCommand(List.of(
                    appImageTool,
                    "--runtime-file", runtimeFile,
                    appDir.toString(),
                    appImagePath.toString()
            ), projectDir, env);
            setExecutable(appImagePath);

            if (findOnPath("zsyncmake") != null) {
                runCommand(List.of(
                        "zsyncmake",
                        "-o", outputDir.resolve(appImageName + ".zsync").toString(),
                        appImagePath.toString()
                ), projectDir, Map.of());
            }
        } finally {
            deleteDirectory(workDir);
        }

        Path linuxTarball = outputDir.resolve("launcher-bootstrap-" + version + "-linux.tar.gz");
        runCommand(List.of(
                "tar",
                "-C", appImageDir.toString(),
                "-czf", linuxTarball.toString(),
                "."
        ), projectDir, Map.of());

        if (buildDeb) {
            runCommand(List.of(
                    "jpackage",
                    "--type", "deb",
                    "--dest", outputDir.toString(),
                    "--name", "launcher-bootstrap",
                    "--app-version", version,
                    "--vendor", "SKCraft",
                    "--input", appImageDir.resolve("app").toString(),
                    "--main-jar", "launcher-bootstrap.jar",
                    "--runtime-image", appImageDir.resolve("runtime").toString(),
                    "--icon", iconPng.toString(),
                    "--linux-shortcut",
                    "--linux-app-category", "Game"
            ), projectDir, Map.of());
            System.out.println("Built DEB package in " + outputDir);
        }

        System.out.println("Built AppImage: " + appImagePath);
        System.out.println("Built Linux tarball: " + linuxTarball);
    }

    private static void packageMac(Path projectDir, String version) throws Exception {
        Path appImageDir = projectDir.resolve("build/app-image");
        Path outputDir = projectDir.resolve("build/installer/macos");
        Path iconPng = projectDir.resolve("src/main/resources/com/skcraft/launcher/bootstrapper_icon.png");
        Path iconIcns = projectDir.resolve("build/tmp/macos/icon.icns");
        Files.createDirectories(outputDir);
        ensureExists(appImageDir.resolve("runtime"), "Missing app image runtime");
        ensureExists(appImageDir.resolve("app/launcher-bootstrap.jar"), "Missing app image jar");
        ensureExists(iconPng, "Missing launcher icon PNG");

        createMacIcnsFromPng(iconPng, iconIcns, projectDir);

        runCommand(List.of(
                "jpackage",
                "--type", "dmg",
                "--dest", outputDir.toString(),
                "--name", "SKCraft Launcher",
                "--app-version", version,
                "--vendor", "SKCraft",
                "--input", appImageDir.resolve("app").toString(),
                "--main-jar", "launcher-bootstrap.jar",
                "--runtime-image", appImageDir.resolve("runtime").toString(),
                "--icon", iconIcns.toString()
        ), projectDir, Map.of());

        runCommand(List.of(
                "tar",
                "-C", appImageDir.toString(),
                "-czf", outputDir.resolve("launcher-bootstrap-" + version + "-macos.tar.gz").toString(),
                "."
        ), projectDir, Map.of());

        System.out.println("Built macOS packages in " + outputDir);
    }

    private static void packageLinuxFromWsl(Path repoDir, String version, boolean buildDeb, String distro) throws Exception {
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

        String buildDebValue = buildDeb ? "true" : "false";
        String bashCommand = "set -euo pipefail; " +
                "cd \"" + wslRepo + "\"; " +
                "if ! command -v java >/dev/null 2>&1; then echo 'Missing Java in WSL distro. Install OpenJDK 17.' >&2; exit 1; fi; " +
                "trap 'rm -f ./gradlew-wsl' EXIT; " +
                "tr -d '\\r' < ./gradlew > ./gradlew-wsl; " +
                "chmod +x ./gradlew-wsl; " +
                "GRADLE_USER_HOME=/tmp/skcraft-gradle ./gradlew-wsl --no-daemon --project-cache-dir /tmp/skcraft-project-cache :launcher-bootstrap:packageLinux -Pversion=\"" + version + "\" -PbuildDeb=\"" + buildDebValue + "\"";

        runCommand(append(wslArgs, "bash", "-lc", bashCommand), repoDir, Map.of());
        System.out.println("Linux artifacts written to launcher-bootstrap/build/installer/linux");
    }

    private static void createMacIcnsFromPng(Path iconPng, Path iconIcns, Path workingDir) throws Exception {
        Files.createDirectories(iconIcns.getParent());
        Path iconsetDir = Files.createTempDirectory("skcraft-iconset-");
        try {
            int[] baseSizes = new int[] {16, 32, 128, 256, 512};
            for (int size : baseSizes) {
                Path normal = iconsetDir.resolve("icon_" + size + "x" + size + ".png");
                Path retina = iconsetDir.resolve("icon_" + size + "x" + size + "@2x.png");

                runCommand(List.of(
                        "sips", "-z", String.valueOf(size), String.valueOf(size),
                        iconPng.toString(),
                        "--out", normal.toString()
                ), workingDir, Map.of());
                runCommand(List.of(
                        "sips", "-z", String.valueOf(size * 2), String.valueOf(size * 2),
                        iconPng.toString(),
                        "--out", retina.toString()
                ), workingDir, Map.of());
            }

            runCommand(List.of(
                    "iconutil",
                    "-c", "icns",
                    iconsetDir.toString(),
                    "-o", iconIcns.toString()
            ), workingDir, Map.of());
        } finally {
            deleteDirectory(iconsetDir);
        }
    }

    private static List<String> append(List<String> base, String... extra) {
        List<String> merged = new ArrayList<>(base);
        for (String s : extra) {
            merged.add(s);
        }
        return merged;
    }

    private static boolean parseBooleanFlag(String raw) {
        if (raw == null) {
            return false;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return value.equals("1")
                || value.equals("true")
                || value.equals("yes")
                || value.equals("on");
    }

    private static String findWindowsTool(String executable, List<Path> fallbackCandidates) {
        String fromPath = findOnPath(executable);
        if (fromPath != null) {
            return fromPath;
        }
        for (Path candidate : fallbackCandidates) {
            if (candidate != null && Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
        }
        if ("makensis.exe".equalsIgnoreCase(executable)) {
            throw new IllegalStateException(
                    "NSIS (makensis.exe) not found.\n"
                            + "Install NSIS 3 and ensure makensis.exe is on PATH, or set NSIS_HOME.\n"
                            + "  winget install NSIS.NSIS\n"
                            + "  https://nsis.sourceforge.io/Download\n"
                            + "Or build without the installer: build.bat --no-installer");
        }
        throw new IllegalStateException(executable + " not found.");
    }

    private static String findOnPath(String executable) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return null;
        }
        for (String entry : path.split(java.io.File.pathSeparator)) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            Path candidate = Paths.get(entry).resolve(executable);
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return null;
    }

    private static void ensureExists(Path path, String message) {
        if (!Files.exists(path)) {
            throw new IllegalStateException(message + ": " + path);
        }
    }

    private static void runCommand(List<String> command, Path workingDirectory, Map<String, String> extraEnv) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        builder.inheritIO();
        builder.environment().putAll(extraEnv);
        Process process = builder.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("Command failed (" + exitCode + "): " + String.join(" ", command));
        }
    }

    private static String runAndCapture(List<String> command, Path workingDirectory, Map<String, String> extraEnv) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        builder.environment().putAll(extraEnv);
        Process process = builder.start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        copy(process.getInputStream(), out);
        copy(process.getErrorStream(), err);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            String errorText = err.toString(StandardCharsets.UTF_8);
            if (!errorText.isBlank()) {
                System.err.print(errorText);
            }
            throw new IllegalStateException("Command failed (" + exitCode + "): " + String.join(" ", command));
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            out.write(buffer, 0, read);
        }
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path rel = source.relativize(dir);
                Files.createDirectories(target.resolve(rel));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path rel = source.relativize(file);
                Files.copy(file, target.resolve(rel), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private static void setExecutable(Path file) throws IOException {
        try {
            Set<PosixFilePermission> perms = EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE
            );
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException ignored) {
            file.toFile().setExecutable(true, false);
        }
    }

    private static void writeIcoFromPng(Path sourcePng, Path targetIco) throws IOException {
        BufferedImage image = ImageIO.read(sourcePng.toFile());
        if (image == null) {
            throw new IllegalStateException("Failed to read PNG icon: " + sourcePng);
        }
        byte[] pngBytes = Files.readAllBytes(sourcePng);
        int width = Math.min(image.getWidth(), 256);
        int height = Math.min(image.getHeight(), 256);

        Files.createDirectories(targetIco.getParent());
        try (OutputStream out = Files.newOutputStream(targetIco)) {
            writeLeShort(out, 0);
            writeLeShort(out, 1);
            writeLeShort(out, 1);
            out.write(width >= 256 ? 0 : width);
            out.write(height >= 256 ? 0 : height);
            out.write(0);
            out.write(0);
            writeLeShort(out, 1);
            writeLeShort(out, 32);
            writeLeInt(out, pngBytes.length);
            writeLeInt(out, 22);
            out.write(pngBytes);
        }
    }

    private static void writeLeShort(OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    private static void writeLeInt(OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }
}
