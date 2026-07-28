package com.skcraft.launcher.installer.platform;

import com.skcraft.launcher.installer.InstallerPackager;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class WindowsInstallerPackager extends InstallerPackager.Platform {
    private static final String WEBVIEW2_BOOTSTRAPPER_FILE = "MicrosoftEdgeWebview2Setup.exe";

    @Override
    public void run(String[] args) throws Exception {
        if (args.length < 5) {
            throw new IllegalArgumentException(
                    "Usage: InstallerPackager windows <projectDir> <version> <displayAppName> <installDirName> <installBaseDir>");
        }
        packageWindows(Paths.get(args[0]), args[1], args[2], args[3], args[4]);
    }

    private void packageWindows(Path projectDir, String version, String displayAppName, String installDirName,
            String installBaseDir) throws Exception {
        String appName = normalizeWindowsAppName(displayAppName);
        String installDir = normalizeInstallDirName(installDirName);
        String defaultInstallBaseDir = normalizeWindowsInstallBaseDir(installBaseDir);
        String setupFileName = installerFileName(appName, " Setup.exe");
        Path appImageDir = projectDir.resolve("build/windows-app-image").resolve(appName);
        Path outputDir = projectDir.resolve("build/installer/windows");
        Path iconPng = projectDir.resolve("src/main/resources/com/skcraft/launcher/bootstrapper_icon.png");
        Path iconIco = projectDir.resolve("build/tmp/windows/icon.ico");
        Path setupScript = projectDir.resolve("installer/windows/setup.nsi");
        Path webView2Bootstrapper = projectDir.resolve("build/webview2-runtime").resolve(WEBVIEW2_BOOTSTRAPPER_FILE);

        ensureExists(appImageDir, "Windows app image not found");
        ensureExists(appImageDir.resolve(DATA_SUBDIR).resolve(LAUNCHER_DIR), "Missing bundled launcher directory");
        ensureExists(appImageDir.resolve(DATA_SUBDIR).resolve("natives").resolve("weblite"),
                "Missing bundled weblite host bridge");
        ensureExists(iconPng, "Launcher icon PNG not found");
        ensureExists(setupScript, "NSIS setup script not found");
        ensureExists(webView2Bootstrapper, "Missing WebView2 bootstrapper");
        ensureExists(projectDir.resolve("installer/installer.properties"),
                "Installer properties not found");
        Files.createDirectories(outputDir);
        Path setupOutput = outputDir.resolve(setupFileName);
        try {
            Files.deleteIfExists(setupOutput);
        } catch (IOException e) {
            throw new IOException("Unable to replace existing Windows installer. Close any running installer or file "
                    + "viewer using " + setupOutput + " and try again.", e);
        }

        writeIcoFromPng(iconPng, iconIco);

        String legacyHomeFolder = readLegacyHomeFolder(projectDir, "legacyHomeFolderWindows");
        Path installerDefines = projectDir.resolve("build/tmp/windows/installer-defines.nsh");
        writeInstallerDefines(installerDefines, legacyHomeFolder, appName, installDir, defaultInstallBaseDir,
                setupFileName, appImageDir.toAbsolutePath().toString(),
                webView2Bootstrapper.toAbsolutePath().toString());

        String makensis = findWindowsTool("makensis.exe",
                List.of(
                        Paths.get(System.getenv("ProgramFiles(x86)"), "NSIS", "makensis.exe"),
                        Paths.get(System.getenv("ProgramFiles"), "NSIS", "makensis.exe")));

        List<String> command = new ArrayList<>();
        command.add(makensis);
        command.add("/DMyAppVersion=" + version);
        command.add("/DOutputDir=" + outputDir.toAbsolutePath());
        command.add("/DIconIco=" + iconIco.toAbsolutePath());
        command.add("/DINSTALLER_DEFINES=" + installerDefines.toAbsolutePath());
        command.add(setupScript.toAbsolutePath().toString());
        runCommand(command, projectDir, Map.of());
        System.out.println("Windows installer written to " + setupOutput);
    }

    private String normalizeWindowsAppName(String appName) {
        if (appName == null || appName.isBlank()) {
            throw new IllegalArgumentException("Windows app name must not be blank.");
        }
        return appName.trim();
    }

    private void writeInstallerDefines(Path output, String legacyHomeFolder,
            String displayAppName, String installDirName, String installBaseDir, String setupFileName,
            String appImageDir,
            String webView2Bootstrapper)
            throws IOException {
        Files.createDirectories(output.getParent());
        String content = "!define LegacyHomeFolder \"" + escapeNsisDefineValue(legacyHomeFolder) + "\"\r\n"
                + "!define AppName \"" + escapeNsisDefineValue(displayAppName) + "\"\r\n"
                + "!define InstallDirName \"" + escapeNsisDefineValue(installDirName) + "\"\r\n"
                + "!define InstallBaseDir \"" + escapeNsisDefineValue(installBaseDir) + "\"\r\n"
                + "!define SetupFileName \"" + escapeNsisDefineValue(setupFileName) + "\"\r\n"
                + "!define AppImageDir \"" + toNsisPath(appImageDir) + "\"\r\n"
                + "!define WebView2Bootstrapper \"" + escapeNsisDefinePath(webView2Bootstrapper) + "\"\r\n";
        Files.writeString(output, content, StandardCharsets.UTF_8);
    }

    private String toNsisPath(String path) {
        return escapeNsisDefineValue(path.replace('\\', '/'));
    }

    private String escapeNsisDefinePath(String path) {
        return path.replace("\"", "$\"");
    }

    private String escapeNsisDefineValue(String value) {
        return value.replace("\\", "$\\").replace("\"", "$\"");
    }

    private String normalizeWindowsInstallBaseDir(String installBaseDir) {
        if (installBaseDir == null || installBaseDir.isBlank()) {
            throw new IllegalArgumentException("Windows install base dir must not be blank.");
        }
        return installBaseDir.trim();
    }

    private String findWindowsTool(String executable, List<Path> fallbackCandidates) {
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

    private void writeIcoFromPng(Path sourcePng, Path targetIco) throws IOException {
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

    private void writeLeShort(OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    private void writeLeInt(OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }
}
