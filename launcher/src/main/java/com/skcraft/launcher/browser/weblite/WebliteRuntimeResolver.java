/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.browser.weblite;

import ca.weblite.webview.nativelib.JniExtractor;
import ca.weblite.webview.nativelib.NativeLoader;

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
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Resolves, verifies, and loads the host JNI bridge used by weblite.
 */
public final class WebliteRuntimeResolver {

    private static final Logger log = Logger.getLogger(WebliteRuntimeResolver.class.getName());

    private static final String MAVEN_BASE_URL = "https://repo.maven.apache.org/maven2";
    private static final String WEBVIEW_GROUP_PATH = "ca/weblite/webview";
    private static final String VERSION_PROPERTY = "version";
    private static final String PATH_PROPERTY = "path";
    private static final String CHECKSUMS_RESOURCE = "META-INF/skcraft/weblite-checksums.properties";
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 10 * 60 * 1000;

    private static Properties checksums;
    private static boolean loaded;

    private WebliteRuntimeResolver() {
    }

    public static synchronized Path resolveAndLoad(Path baseDir, String platform)
            throws IOException, InterruptedException {
        if (loaded) {
            return resolveNativePath(baseDir, platform);
        }

        Path nativePath = resolveNativePath(baseDir, platform);
        System.load(nativePath.toAbsolutePath().toString());
        installVerifiedExtractor(nativePath);

        try {
            Class.forName("ca.weblite.webview.WebViewNative", true,
                    WebliteRuntimeResolver.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IOException("Weblite native bootstrap class is unavailable", e);
        }

        loaded = true;
        log.info("Loaded weblite native bridge from " + nativePath.toAbsolutePath());
        return nativePath;
    }

    private static Path resolveNativePath(Path baseDir, String platform)
            throws IOException, InterruptedException {
        Properties metadata = loadChecksums();
        String version = metadata.getProperty(VERSION_PROPERTY);
        String entry = requiredProperty(metadata, platform + ".entry");
        String expectedSha256 = requiredProperty(metadata, platform + ".sha256");
        String fileName = Path.of(entry).getFileName().toString();
        Path target = baseDir.resolve(metadata.getProperty(PATH_PROPERTY))
                .resolve(version)
                .resolve(platform)
                .resolve(fileName);

        if (isValidNative(target, expectedSha256)) {
            return target;
        }

        log.info("Weblite native bridge " + version + " is missing or invalid.");
        return downloadHostNative(target, version, entry, expectedSha256);
    }

    private static Path downloadHostNative(Path target, String version, String entry, String expectedSha256)
            throws IOException, InterruptedException {
        Files.createDirectories(target.getParent());
        URL jarUrl = new URL(mavenArtifactUrl(version));
        Path jarTemp = Files.createTempFile(target.getParent(), "webview-", ".jar.tmp");
        Path nativeTemp = Files.createTempFile(target.getParent(), "webview-", ".native.tmp");

        try {
            log.info("Downloading weblite " + version + " from Maven Central");
            downloadTo(jarUrl, jarTemp);
            extractEntry(jarTemp, entry, nativeTemp);
            if (!expectedSha256.equalsIgnoreCase(sha256Hex(nativeTemp))) {
                throw new IOException("Weblite native checksum mismatch for " + entry);
            }
            moveAtomically(nativeTemp, target);
            log.info("Installed weblite native bridge " + version);
            return target;
        } finally {
            Files.deleteIfExists(jarTemp);
            Files.deleteIfExists(nativeTemp);
        }
    }

    private static void downloadTo(URL url, Path target) throws IOException, InterruptedException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);

        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("Unexpected HTTP " + code + " for " + url);
            }
            try (InputStream input = connection.getInputStream();
                 OutputStream output = Files.newOutputStream(target)) {
                copyInterruptibly(input, output);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static void extractEntry(Path jar, String expectedEntry, Path target)
            throws IOException, InterruptedException {
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(jar));
             OutputStream output = Files.newOutputStream(target)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (expectedEntry.equals(entry.getName()) && !entry.isDirectory()) {
                    copyInterruptibly(input, output);
                    return;
                }
            }
        }
        throw new IOException("Weblite native entry not found: " + expectedEntry);
    }

    private static void copyInterruptibly(InputStream input, OutputStream output)
            throws IOException, InterruptedException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            output.write(buffer, 0, read);
        }
    }

    private static void installVerifiedExtractor(final Path nativePath) {
        final java.io.File nativeFile = nativePath.toAbsolutePath().toFile();
        NativeLoader.setJniExtractor(new JniExtractor() {
            @Override
            public java.io.File extractJni(String path, String libraryName) {
                return nativeFile;
            }

            @Override
            public void extractRegistered() {
            }
        });
    }

    private static synchronized Properties loadChecksums() throws IOException {
        if (checksums != null) {
            return checksums;
        }

        Properties metadata = new Properties();
        try (InputStream input = WebliteRuntimeResolver.class.getClassLoader()
                .getResourceAsStream(CHECKSUMS_RESOURCE)) {
            if (input == null) {
                throw new IOException("Missing weblite checksum resource: " + CHECKSUMS_RESOURCE);
            }
            metadata.load(input);
        }

        String version = requiredProperty(metadata, VERSION_PROPERTY);
        String runtimeRoot = requiredProperty(metadata, PATH_PROPERTY);
        Path path = Path.of(runtimeRoot);
        if (path.isAbsolute() || path.normalize().startsWith("..")) {
            throw new IOException("Invalid weblite runtime path in " + CHECKSUMS_RESOURCE + ": " + runtimeRoot);
        }
        metadata.setProperty(VERSION_PROPERTY, version);
        metadata.setProperty(PATH_PROPERTY, path.normalize().toString());
        checksums = metadata;
        return checksums;
    }

    private static String requiredProperty(Properties metadata, String name) throws IOException {
        String value = metadata.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IOException("Missing " + name + " in " + CHECKSUMS_RESOURCE);
        }
        return value.trim();
    }

    private static boolean isValidNative(Path path, String expectedSha256) {
        try {
            return Files.isRegularFile(path) && expectedSha256.equalsIgnoreCase(sha256Hex(path));
        } catch (IOException e) {
            return false;
        }
    }

    private static String sha256Hex(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path);
                 DigestInputStream digestInput = new DigestInputStream(input, digest)) {
                byte[] buffer = new byte[8192];
                while (digestInput.read(buffer) != -1) {
                }
            }
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest()) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
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

    private static String mavenArtifactUrl(String version) {
        return MAVEN_BASE_URL + "/" + WEBVIEW_GROUP_PATH + "/" + version
                + "/webview-" + version + ".jar";
    }
}
