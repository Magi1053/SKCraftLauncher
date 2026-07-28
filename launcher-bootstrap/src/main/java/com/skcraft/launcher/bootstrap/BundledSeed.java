package com.skcraft.launcher.bootstrap;

import java.io.IOException;
import java.net.URL;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.logging.Logger;

/**
 * Adds missing files from a packaged payload without replacing an existing
 * launcher installation or self-update. The bootstrap then selects the newest
 * launcher JAR by its timestamped filename. AppImage and Flatpak payloads keep
 * {@code app/launcher-bootstrap.jar} beside {@code bootstrap/launcher} and
 * {@code bootstrap/natives}; DEB uses the same layout but normally seeds it
 * from
 * its post-install script.
 */
public final class BundledSeed {

    private static final Logger log = Logger.getLogger(BundledSeed.class.getName());
    private static final int MAX_PARENT_LEVELS = 4;
    private static final Path FLATPAK_BUNDLED_ROOT = Path.of("/app/lib/skcraft-launcher");

    private BundledSeed() {
    }

    public static void copyIfNeeded(Class<?> anchor, Path dataDir, Path binariesDir) {
        Path bundledRoot = findBundledRoot(anchor);
        if (bundledRoot == null) {
            return;
        }

        Path bundledBootstrap = bundledRoot.resolve("bootstrap");
        Path bundledLauncher = bundledBootstrap.resolve("launcher");
        if (!hasLauncherBinary(bundledLauncher)) {
            return;
        }

        try {
            copyMissing(bundledLauncher, binariesDir);
            Path bundledNatives = bundledBootstrap.resolve("natives");
            if (Files.isDirectory(bundledNatives)) {
                copyMissing(bundledNatives, dataDir.resolve("natives"));
            }
            log.info("Seeded launcher cache from " + bundledRoot);
        } catch (IOException e) {
            log.warning("Unable to seed bundled launcher cache: " + e.getMessage());
        }
    }

    private static Path findBundledRoot(Class<?> anchor) {
        try {
            URL location = anchor.getProtectionDomain().getCodeSource().getLocation();
            if (location != null) {
                Path locationPath = Path.of(location.toURI());
                Path candidate = Files.isRegularFile(locationPath) ? locationPath.getParent() : locationPath;
                for (int level = 0; candidate != null && level <= MAX_PARENT_LEVELS; level++) {
                    if (isBundledRoot(candidate)) {
                        return candidate;
                    }
                    candidate = candidate.getParent();
                }
            }
        } catch (Exception e) {
            log.fine("Unable to locate bundled launcher cache: " + e.getMessage());
        }

        String flatpakId = System.getenv("FLATPAK_ID");
        if (flatpakId != null && !flatpakId.isBlank() && isBundledRoot(FLATPAK_BUNDLED_ROOT)) {
            return FLATPAK_BUNDLED_ROOT;
        }
        return null;
    }

    private static boolean isBundledRoot(Path candidate) {
        return Files.isDirectory(candidate.resolve("bootstrap/launcher"));
    }

    private static boolean hasLauncherBinary(Path directory) {
        if (!Files.isDirectory(directory)) {
            return false;
        }
        try (var files = Files.list(directory)) {
            return files.anyMatch(path -> LauncherBinary.PATTERN.matcher(path.getFileName().toString()).matches());
        } catch (IOException e) {
            return false;
        }
    }

    private static void copyMissing(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path destination = target.resolve(source.relativize(file));
                if (!Files.exists(destination)) {
                    Files.copy(file, destination);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
