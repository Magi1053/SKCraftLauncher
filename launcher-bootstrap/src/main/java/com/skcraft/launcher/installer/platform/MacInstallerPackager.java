package com.skcraft.launcher.installer.platform;

import com.skcraft.launcher.installer.InstallerPackager;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MacInstallerPackager extends InstallerPackager.Platform {
    /**
     * macOS Dock/Finder icons sit in ~80% of the canvas; edge-to-edge art reads
     * oversized.
     */
    private static final double MAC_ICON_CONTENT_SCALE = 0.80;
    private static final int MAC_ICON_MASTER_SIZE = 1024;

    @Override
    public void run(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException(
                    "Usage: InstallerPackager mac <projectDir> <version> <appName>");
        }
        packageMac(Paths.get(args[0]), args[1], normalizePackageAppName(args[2]));
    }

    private void packageMac(Path projectDir, String version, String appName) throws Exception {
        Path appImageDir = projectDir.resolve("build/app-image");
        Path outputDir = projectDir.resolve("build/installer/macos");
        Path iconPng = projectDir.resolve("src/main/resources/com/skcraft/launcher/bootstrapper_icon.png");
        Path iconIcns = projectDir.resolve("build/tmp/macos/icon.icns");
        Files.createDirectories(outputDir);
        ensureAppImageReady(appImageDir);
        ensureExists(iconPng, "Missing launcher icon PNG");

        createMacIcnsFromPng(iconPng, iconIcns, projectDir);

        String macPackageIdentifier = toMacPackageIdentifier(appName);
        String macDataDir = "Library/Application Support/"
                + getBootstrapPropertyWithFallback(projectDir, "packageAppNameMac", "packageAppName");

        List<String> commonJpackageArgs = List.of(
                "--name", appName,
                "--app-version", version,
                "--vendor", "SKCraft",
                "--main-jar", "launcher-bootstrap.jar",
                "--runtime-image", appImageDir.resolve(RUNTIME_DIR).toString(),
                "--java-options", "-splash:$APPDIR/splash.png",
                "--icon", iconIcns.toString(),
                "--mac-package-identifier", macPackageIdentifier,
                "--mac-app-category", "games");

        // DMG: bootstrap-only (no bundled launcher/natives — bootstrap downloads on
        // first run)
        Path dmgInputDir = createDmgInput(appImageDir, "skcraft-mac-dmg-");
        try {
            List<String> dmgCommand = new ArrayList<>();
            dmgCommand.add("jpackage");
            dmgCommand.add("--type");
            dmgCommand.add("dmg");
            dmgCommand.add("--dest");
            dmgCommand.add(outputDir.toString());
            dmgCommand.add("--input");
            dmgCommand.add(dmgInputDir.toString());
            dmgCommand.addAll(commonJpackageArgs);
            runCommand(dmgCommand, projectDir, Map.of());
            // jpackage names DMG as "{name}-{version}.dmg"; match Windows/Linux (no version).
            renamePackagedFile(outputDir, appName + "-" + version + ".dmg", installerFileName(appName, ".dmg"));
        } finally {
            deleteDirectory(dmgInputDir);
        }

        // PKG: includes bundled launcher jar + natives, seeded by postinstall
        Path pkgInputDir = createBundledAppInput(appImageDir, "skcraft-mac-pkg-");
        try {
            Path resourceDir = prepareJpackageResourceDir(projectDir, "macos", Map.of(
                    "@MAC_DATA_DIR@", macDataDir,
                    "@MAC_APP_NAME@", appName));

            List<String> pkgCommand = new ArrayList<>();
            pkgCommand.add("jpackage");
            pkgCommand.add("--type");
            pkgCommand.add("pkg");
            pkgCommand.add("--dest");
            pkgCommand.add(outputDir.toString());
            pkgCommand.add("--input");
            pkgCommand.add(pkgInputDir.toString());
            pkgCommand.addAll(commonJpackageArgs);
            pkgCommand.add("--resource-dir");
            pkgCommand.add(resourceDir.toString());
            runCommand(pkgCommand, projectDir, Map.of());
            renamePackagedFile(outputDir, appName + "-" + version + ".pkg", installerFileName(appName, ".pkg"));
        } finally {
            deleteDirectory(pkgInputDir);
        }

        Path macTarball = outputDir.resolve(installerFileName(appName, ".tar.gz"));
        runCommand(List.of(
                "tar",
                "-C", appImageDir.toString(),
                "-czf", macTarball.toString(),
                "."), projectDir, Map.of());

        System.out.println("Built macOS DMG: " + outputDir.resolve(installerFileName(appName, ".dmg")));
        System.out.println("Built macOS PKG: " + outputDir.resolve(installerFileName(appName, ".pkg")));
        System.out.println("Built macOS tarball: " + macTarball);
    }

    private String toMacPackageIdentifier(String appName) {
        String sanitized = appName == null ? "" : appName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
        if (sanitized.isBlank()) {
            sanitized = "launcher";
        }
        return "com.skcraft." + sanitized;
    }

    private Path createDmgInput(Path appImageDir, String prefix) throws IOException {
        Path inputDir = Files.createTempDirectory(prefix);
        copyDirectory(appImageDir.resolve(APP_DIR), inputDir);
        return inputDir;
    }

    private void createMacIcnsFromPng(Path iconPng, Path iconIcns, Path workingDir) throws Exception {
        Files.createDirectories(iconIcns.getParent());
        // iconutil requires the directory name to end with ".iconset"
        Path iconsetParent = Files.createTempDirectory("skcraft-iconset-");
        Path iconsetDir = iconsetParent.resolve("AppIcon.iconset");
        Files.createDirectories(iconsetDir);
        try {
            // Pad before sips so Dock optical size matches system apps; high-quality
            // 1024 master keeps 512@2x sharp (no upsample from a smaller asset).
            Path masterPng = iconsetParent.resolve("mac-icon-master.png");
            writeMacPaddedIconPng(iconPng, masterPng);

            int[] baseSizes = new int[] { 16, 32, 128, 256, 512 };
            for (int size : baseSizes) {
                Path normal = iconsetDir.resolve("icon_" + size + "x" + size + ".png");
                Path retina = iconsetDir.resolve("icon_" + size + "x" + size + "@2x.png");

                // Capture (don't inherit) so sips path spam stays out of Gradle logs.
                runAndCapture(List.of(
                        "sips", "-z", String.valueOf(size), String.valueOf(size),
                        masterPng.toString(),
                        "--out", normal.toString()), workingDir, Map.of());
                if (size * 2 == MAC_ICON_MASTER_SIZE) {
                    Files.copy(masterPng, retina, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    runAndCapture(List.of(
                            "sips", "-z", String.valueOf(size * 2), String.valueOf(size * 2),
                            masterPng.toString(),
                            "--out", retina.toString()), workingDir, Map.of());
                }
            }

            runAndCapture(List.of(
                    "iconutil",
                    "-c", "icns",
                    iconsetDir.toString(),
                    "-o", iconIcns.toString()), workingDir, Map.of());
        } finally {
            deleteDirectory(iconsetParent);
        }
    }

    /**
     * Centers the source art on a transparent 1024 canvas at
     * {@link #MAC_ICON_CONTENT_SCALE}
     * so macOS squircle + shadow optical size matches typical Dock/Finder icons.
     */
    private void writeMacPaddedIconPng(Path sourcePng, Path destPng) throws IOException {
        BufferedImage source = ImageIO.read(sourcePng.toFile());
        if (source == null) {
            throw new IOException("Unable to read icon PNG: " + sourcePng);
        }

        int canvas = MAC_ICON_MASTER_SIZE;
        int content = (int) Math.round(canvas * MAC_ICON_CONTENT_SCALE);
        BufferedImage out = new BufferedImage(canvas, canvas, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int x = (canvas - content) / 2;
            int y = (canvas - content) / 2;
            g.drawImage(source, x, y, content, content, null);
        } finally {
            g.dispose();
        }

        if (!ImageIO.write(out, "png", destPng.toFile())) {
            throw new IOException("Unable to write padded macOS icon PNG: " + destPng);
        }
    }
}
