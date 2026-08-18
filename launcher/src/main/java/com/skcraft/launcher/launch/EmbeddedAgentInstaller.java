package com.skcraft.launcher.launch;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class EmbeddedAgentInstaller {

    private EmbeddedAgentInstaller() {
    }

    static File install(File agentsDir, String resourcePath, String filePrefix) throws IOException {
        byte[] agent = readResource(resourcePath);
        String digest = sha256(agent);
        Path dir = agentsDir.toPath();
        Files.createDirectories(dir);
        Path target = dir.resolve(filePrefix + digest.substring(0, 16) + ".jar");

        if (!matches(target, digest)) {
            writeAtomically(dir, target, filePrefix, agent);
        }
        pruneOthers(dir, target.getFileName().toString(), filePrefix);
        return target.toFile();
    }

    private static boolean matches(Path target, String digest) throws IOException {
        return Files.isRegularFile(target) && digest.equals(sha256(Files.readAllBytes(target)));
    }

    private static void writeAtomically(
            Path dir, Path target, String filePrefix, byte[] bytes) throws IOException {
        Path staging = Files.createTempFile(dir, filePrefix, ".tmp");
        try {
            Files.write(staging, bytes);
            try {
                Files.move(staging, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staging);
        }
    }

    private static void pruneOthers(Path dir, String keepName, String filePrefix)
            throws IOException {
        try (DirectoryStream<Path> stream =
                     Files.newDirectoryStream(dir, filePrefix + "*.jar")) {
            for (Path candidate : stream) {
                if (!keepName.equals(candidate.getFileName().toString())) {
                    Files.deleteIfExists(candidate);
                }
            }
        }
    }

    private static byte[] readResource(String resourcePath) throws IOException {
        try (InputStream input =
                     EmbeddedAgentInstaller.class.getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IOException("Embedded agent is missing: " + resourcePath);
            }
            return input.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable", e);
        }
    }
}
