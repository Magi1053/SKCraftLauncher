/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.bootstrap;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class LegacyDocumentsMigrator {

    private static final Logger log = Logger.getLogger(LegacyDocumentsMigrator.class.getName());
    private static final String MARKER_FILE = ".legacy-documents-migrated";
    private static final List<String> MIGRATABLE_ENTRIES = Arrays.asList(
            "instances",
            "config.json",
            "accounts.dat",
            "assets",
            "libraries",
            "versions");

    private LegacyDocumentsMigrator() {
    }

    public static void migrateIfNeeded(File installDir, File legacyDir) throws IOException {
        if (installDir == null || legacyDir == null) {
            return;
        }

        Path installDirPath = installDir.toPath();
        Path legacyDirPath = legacyDir.toPath();
        if (installDirPath.toAbsolutePath().normalize().equals(legacyDirPath.toAbsolutePath().normalize())) {
            return;
        }

        if (hasMarker(installDirPath)) {
            return;
        }
        if (!hasLegacyData(legacyDirPath)) {
            return;
        }
        if (hasTargetConflicts(installDirPath, legacyDirPath)) {
            log.warning("Legacy data migration skipped because target data already exists in "
                    + installDir.getAbsolutePath() + ". Existing install data is preserved.");
            return;
        }

        log.info("Migrating legacy launcher data from " + legacyDir.getAbsolutePath()
                + " to " + installDir.getAbsolutePath());

        int movedEntries = 0;
        for (String entryName : MIGRATABLE_ENTRIES) {
            Path source = legacyDirPath.resolve(entryName);
            if (!Files.exists(source)) {
                continue;
            }

            Path target = installDirPath.resolve(entryName);
            log.info("Moving legacy launcher path " + source + " -> " + target);
            Files.move(source, target);
            movedEntries++;
        }

        log.info("Legacy data migration completed. Moved " + movedEntries + " item(s).");
        writeMarker(installDirPath);
        deleteDirectoryRecursively(legacyDirPath);
    }

    private static boolean hasMarker(Path installDirPath) {
        return Files.exists(installDirPath.resolve(MARKER_FILE));
    }

    private static boolean hasLegacyData(Path legacyDirPath) {
        if (!Files.isDirectory(legacyDirPath)) {
            return false;
        }

        for (String entryName : MIGRATABLE_ENTRIES) {
            if (Files.exists(legacyDirPath.resolve(entryName))) {
                return true;
            }
        }

        return false;
    }

    private static boolean hasTargetConflicts(Path installDirPath, Path legacyDirPath) {
        for (String entryName : MIGRATABLE_ENTRIES) {
            Path source = legacyDirPath.resolve(entryName);
            Path target = installDirPath.resolve(entryName);
            if (Files.exists(source) && Files.exists(target)) {
                log.warning("Legacy migration conflict detected for '" + entryName
                        + "' at " + target + "; keeping install directory data.");
                return true;
            }
        }

        return false;
    }

    private static void writeMarker(Path installDirPath) throws IOException {
        Files.write(
                installDirPath.resolve(MARKER_FILE),
                Instant.now().toString().getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
    }

    private static void deleteDirectoryRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }

        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    if (exc != null) {
                        throw exc;
                    }
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.log(Level.WARNING, "Failed to delete legacy launcher directory " + directory, e);
            throw e;
        }
    }
}
