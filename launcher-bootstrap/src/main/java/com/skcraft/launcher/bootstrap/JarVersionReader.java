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
    private static final String VERSION_PLACEHOLDER = "${project.version}";
    private static final String SNAPSHOT_FALLBACK = "1.0.0-SNAPSHOT";

    private JarVersionReader() {
    }

    public static String readVersion(File jarFile) throws IOException {
        try (JarFile jar = new JarFile(jarFile)) {
            JarEntry entry = jar.getJarEntry(PROPERTIES_PATH);
            if (entry == null) {
                throw new IOException("Missing launcher properties in " + jarFile.getAbsolutePath());
            }

            Properties properties = new Properties();
            try (InputStream in = jar.getInputStream(entry)) {
                properties.load(in);
            }

            String version = properties.getProperty(VERSION_KEY);
            if (version == null || version.trim().isEmpty()) {
                throw new IOException("Missing launcher version in " + jarFile.getAbsolutePath());
            }

            if (VERSION_PLACEHOLDER.equals(version)) {
                return SNAPSHOT_FALLBACK;
            }

            return version;
        }
    }
}
