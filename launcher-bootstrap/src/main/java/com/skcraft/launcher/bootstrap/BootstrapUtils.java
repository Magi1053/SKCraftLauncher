/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.bootstrap;

import com.skcraft.launcher.bootstrap.platform.PlatformSupport;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Properties;

public final class BootstrapUtils {

    public static final String DATA_SUBDIR = "bootstrap";

    private BootstrapUtils() {
    }

    public static void checkInterrupted() throws InterruptedException {
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
    }

    public static void closeQuietly(Closeable closeable) {
        try {
            if (closeable != null) {
                closeable.close();
            }
        } catch (IOException e) {
        }
    }

    public static Properties loadProperties(Class<?> clazz, String name) throws IOException {
        return loadProperties(clazz, name, null);
    }

    public static Properties loadProperties(Class<?> clazz, String name, String extraProperty) throws IOException {
        Properties prop = new Properties();
        InputStream in = null;
        try {
            in = clazz.getResourceAsStream(name);
            if (in == null) {
                throw new IOException("Missing bundled properties file: " + name);
            }
            prop.load(in);
        } finally {
            closeQuietly(in);
        }

        String extraPath = extraProperty != null ? System.getProperty(extraProperty) : null;
        if (extraPath != null && !extraPath.trim().isEmpty()) {
            loadPropertiesFile(prop, new File(extraPath));
        } else {
            File sidecar = resolveSidecarPropertiesFile(clazz, name);
            if (sidecar != null && sidecar.isFile()) {
                loadPropertiesFile(prop, sidecar);
            }
        }
        return prop;
    }

    private static void loadPropertiesFile(Properties prop, File file) throws IOException {
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            prop.load(in);
        } finally {
            closeQuietly(in);
        }
    }

    /** Filesystem-safe install id: strip non-alphanumeric (case preserved). */
    public static String sanitizeInstallDirName(String name) {
        if (name == null) {
            return "launcher";
        }
        String sanitized = name.replaceAll("[^A-Za-z0-9_-]", "");
        if (sanitized.isEmpty()) {
            return "launcher";
        }
        return sanitized;
    }

    /**
     * Lowercase alphanumeric data dir id for XDG paths (matches Linux install dir
     * sanitization).
     */
    public static String sanitizeLinuxDataDirName(String name) {
        return sanitizeInstallDirName(name).toLowerCase(Locale.ROOT);
    }

    public static File resolveDataDir(Properties properties, Class<?> clazz) throws IOException {
        return PlatformSupport.INSTANCE.resolveDataDir(properties, clazz);
    }

    /**
     * Path to the code-source JAR or directory for {@code clazz}, or null if unknown.
     */
    public static File resolveCodeSourcePath(Class<?> clazz) {
        try {
            URL location = clazz.getProtectionDomain().getCodeSource().getLocation();
            if (location == null) {
                return null;
            }
            return new File(location.toURI());
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Directory containing the code-source for {@code clazz} (parent of a JAR).
     */
    public static File resolveCodeSourceBaseDir(Class<?> clazz) {
        File path = resolveCodeSourcePath(clazz);
        if (path == null) {
            return null;
        }

        File baseDir = path.isFile() ? path.getParentFile() : path;
        if (baseDir == null) {
            return null;
        }

        return baseDir;
    }

    /**
     * Write {@code launcher.version} beside the given launcher JAR.
     */
    public static void writeLauncherVersionFile(File launcherJar) throws IOException {
        String version = JarVersionReader.readVersion(launcherJar);
        File versionFile = new File(launcherJar.getParentFile(), "launcher.version");
        Files.writeString(versionFile.toPath(), version.trim(), StandardCharsets.UTF_8);
    }

    private static File resolveSidecarPropertiesFile(Class<?> clazz, String name) {
        File baseDir = resolveCodeSourceBaseDir(clazz);
        if (baseDir == null) {
            return null;
        }
        return new File(baseDir, name);
    }

}
