/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.bootstrap;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class JarVersionReader {

    private static final String PROPERTIES_PATH = "com/skcraft/launcher/launcher.properties";
    private static final String VERSION_KEY = "version";
    private static final String SELF_UPDATE_URL_KEY = "selfUpdateUrl";
    private static final String VERSION_PLACEHOLDER = "${project.version}";
    private static final String SNAPSHOT_FALLBACK = "1.0.0-SNAPSHOT";

    private JarVersionReader() {
    }

    public static String readVersion(File jarFile) throws IOException {
        return readProperty(jarFile, VERSION_KEY, true);
    }

    public static String readSelfUpdateUrl(File jarFile) throws IOException {
        return readProperty(jarFile, SELF_UPDATE_URL_KEY, true);
    }

    public static String readSelfUpdateUrlFromClasspath(Class<?> clazz) throws IOException {
        Properties properties = BootstrapUtils.loadProperties(clazz, "launcher.properties");
        String value = properties.getProperty(SELF_UPDATE_URL_KEY);
        if (value == null || value.trim().isEmpty() || value.contains("${")) {
            throw new IOException("Missing selfUpdateUrl in launcher.properties");
        }
        return value.trim();
    }

    private static String readProperty(File jarFile, String key, boolean required) throws IOException {
        try (JarFile jar = new JarFile(jarFile)) {
            JarEntry entry = jar.getJarEntry(PROPERTIES_PATH);
            if (entry == null) {
                throw new IOException("Missing launcher properties in " + jarFile.getAbsolutePath());
            }

            Properties properties = new Properties();
            try (InputStream in = jar.getInputStream(entry)) {
                properties.load(in);
            }

            String value = properties.getProperty(key);
            if (value == null || value.trim().isEmpty()) {
                if (required) {
                    throw new IOException("Missing launcher property '" + key + "' in " + jarFile.getAbsolutePath());
                }
                return null;
            }

            if (VERSION_KEY.equals(key) && VERSION_PLACEHOLDER.equals(value)) {
                return SNAPSHOT_FALLBACK;
            }

            return value.trim();
        }
    }
}
