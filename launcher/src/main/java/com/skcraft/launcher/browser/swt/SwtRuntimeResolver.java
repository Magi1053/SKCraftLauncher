/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.browser.swt;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Resolves and verifies the Windows/Linux SWT runtime beside launcher data.
 */
public final class SwtRuntimeResolver {

    private static final Logger log = Logger.getLogger(SwtRuntimeResolver.class.getName());

    private static final String MAVEN_BASE_URL = "https://repo.maven.apache.org/maven2";
    private static final String SWT_GROUP_PATH = "org/eclipse/platform";
    private static final String VERSION_PROPERTY = "version";
    private static final String PATH_PROPERTY = "path";
    private static final String SWT_CHECKSUMS_RESOURCE = "META-INF/skcraft/swt-checksums.properties";
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 10 * 60 * 1000;

    private static Properties swtChecksums;

    private SwtRuntimeResolver() {
    }

    public static Path resolveSwtJar(Path baseDir) throws IOException, InterruptedException {
        Properties checksums = loadSwtChecksums();
        String swtVersion = checksums.getProperty(VERSION_PROPERTY);
        String swtRuntimeRoot = checksums.getProperty(PATH_PROPERTY);
        String swtArtifact = resolveHostSwtArtifact();
        Path swtJar = swtPath(baseDir, swtArtifact, swtVersion, swtRuntimeRoot);
        String expectedFileName = swtFileName(swtArtifact, swtVersion);
        String expectedSha1 = expectedSwtSha1(checksums, swtArtifact);

        if (isValidSwtJar(swtJar, expectedFileName, expectedSha1)) {
            log.info("Using local SWT runtime " + swtVersion);
            return swtJar;
        }

        log.info("SWT runtime " + swtVersion + " is missing or invalid.");
        return downloadHostSwt(swtJar, swtArtifact, swtVersion, expectedSha1);
    }

    public static String resolveHostSwtArtifact() throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String swtArch = arch.contains("aarch64") || arch.contains("arm64") ? "aarch64" : "x86_64";

        if (os.contains("win")) {
            return "org.eclipse.swt.win32.win32." + swtArch;
        }
        if (os.contains("linux")) {
            return "org.eclipse.swt.gtk.linux." + swtArch;
        }
        throw new IOException("Unsupported platform for SWT loader: os=" + os + ", arch=" + arch);
    }

    private static Path swtPath(Path baseDir, String swtArtifact, String swtVersion, String swtRuntimeRoot) {
        return baseDir.resolve(swtRuntimeRoot)
                .resolve(swtVersion)
                .resolve(swtArtifact)
                .resolve(swtFileName(swtArtifact, swtVersion));
    }

    private static boolean isValidSwtJar(Path path, String expectedFileName, String expectedSha1) {
        try {
            return Files.isRegularFile(path)
                    && expectedFileName.equals(path.getFileName().toString())
                    && expectedSha1.equalsIgnoreCase(sha1Hex(path));
        } catch (IOException e) {
            return false;
        }
    }

    private static Path downloadHostSwt(Path targetPath, String swtArtifact, String swtVersion, String expectedSha1)
            throws IOException, InterruptedException {
        Files.createDirectories(targetPath.getParent());
        URL jarUrl = new URL(mavenArtifactUrl(swtArtifact, swtVersion));
        log.info("Downloading SWT runtime " + swtVersion + " from Maven Central");

        Path tempPath = Files.createTempFile(targetPath.getParent(), swtArtifact + "-", ".tmp");
        try {
            downloadTo(jarUrl, tempPath);
            if (!expectedSha1.equalsIgnoreCase(sha1Hex(tempPath))) {
                throw new IOException("SWT checksum mismatch for " + jarUrl);
            }
            moveAtomically(tempPath, targetPath);
            log.info("Installed SWT runtime " + swtVersion);
            return targetPath;
        } finally {
            Files.deleteIfExists(tempPath);
        }
    }

    private static void downloadTo(URL url, Path target) throws IOException, InterruptedException {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setInstanceFollowRedirects(true);

        try {
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("Unexpected HTTP " + code + " for " + url);
            }

            try (InputStream in = conn.getInputStream();
                 OutputStream out = Files.newOutputStream(target)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (Thread.interrupted()) {
                        throw new InterruptedException();
                    }
                    out.write(buffer, 0, read);
                }
            }
        } finally {
            conn.disconnect();
        }
    }

    private static synchronized Properties loadSwtChecksums() throws IOException {
        if (swtChecksums == null) {
            Properties properties = new Properties();
            try (InputStream input = SwtRuntimeResolver.class.getClassLoader()
                    .getResourceAsStream(SWT_CHECKSUMS_RESOURCE)) {
                if (input == null) {
                    throw new IOException("Missing SWT checksum resource: " + SWT_CHECKSUMS_RESOURCE);
                }
                properties.load(input);
            }

            String version = properties.getProperty(VERSION_PROPERTY);
            if (version == null || version.isBlank()) {
                throw new IOException("Missing SWT version in " + SWT_CHECKSUMS_RESOURCE);
            }

            String runtimeRoot = properties.getProperty(PATH_PROPERTY);
            if (runtimeRoot == null || runtimeRoot.isBlank()) {
                throw new IOException("Missing SWT runtime path in " + SWT_CHECKSUMS_RESOURCE);
            }

            Path path = Path.of(runtimeRoot);
            if (path.isAbsolute() || path.normalize().startsWith("..")) {
                throw new IOException("Invalid SWT runtime path in " + SWT_CHECKSUMS_RESOURCE + ": " + runtimeRoot);
            }

            properties.setProperty(PATH_PROPERTY, path.normalize().toString());
            swtChecksums = properties;
        }
        return swtChecksums;
    }

    private static String expectedSwtSha1(Properties checksums, String swtArtifact) throws IOException {
        String checksum = checksums.getProperty(swtArtifact);
        if (checksum == null || checksum.isBlank()) {
            throw new IOException("Missing SWT checksum for " + swtArtifact);
        }
        return checksum;
    }

    private static String sha1Hex(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            try (InputStream in = Files.newInputStream(path);
                 DigestInputStream digestIn = new DigestInputStream(in, digest)) {
                byte[] buffer = new byte[8192];
                while (digestIn.read(buffer) != -1) {
                }
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 not available", e);
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            log.log(Level.FINE, "Atomic move failed; falling back to replace", ignored);
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String mavenArtifactUrl(String swtArtifact, String swtVersion) {
        return MAVEN_BASE_URL + "/" + SWT_GROUP_PATH + "/" + swtArtifact + "/" + swtVersion + "/"
                + swtFileName(swtArtifact, swtVersion);
    }

    private static String swtFileName(String swtArtifact, String swtVersion) {
        return swtArtifact + "-" + swtVersion + ".jar";
    }
}
