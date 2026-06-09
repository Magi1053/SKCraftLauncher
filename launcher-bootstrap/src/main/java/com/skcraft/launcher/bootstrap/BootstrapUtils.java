/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.bootstrap;

import javax.swing.JFileChooser;
import javax.swing.filechooser.FileSystemView;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Properties;
import java.util.regex.Pattern;

public final class BootstrapUtils {

    private static final Pattern absoluteUrlPattern = Pattern.compile("^[A-Za-z0-9\\-]+://.*$");

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

    public static File getWindowsInstallDir(Class<?> clazz) {
        File codeSourcePath = resolveCodeSourcePath(clazz);
        if (codeSourcePath == null || !codeSourcePath.isFile() || !codeSourcePath.getName().endsWith(".jar")) {
            return null;
        }

        File jarDir = codeSourcePath.getParentFile();
        if (jarDir == null) {
            return null;
        }

        if ("app".equalsIgnoreCase(jarDir.getName())) {
            File parent = jarDir.getParentFile();
            if (parent != null && new File(parent, "runtime").isDirectory()) {
                return parent;
            }
        }

        return jarDir;
    }

    public static File getLegacyWindowsDataDir(Properties properties) {
        File documentsDir = getDocumentsDir();
        if (documentsDir == null || properties == null) {
            return null;
        }

        String folderName = properties.getProperty("homeFolderWindows");
        if (folderName == null || folderName.trim().isEmpty()) {
            return null;
        }

        return new File(documentsDir, folderName);
    }

    private static File getDocumentsDir() {
        JFileChooser chooser = new JFileChooser();
        FileSystemView fileSystemView = chooser.getFileSystemView();
        return fileSystemView != null ? fileSystemView.getDefaultDirectory() : null;
    }

    private static File resolveSidecarPropertiesFile(Class<?> clazz, String name) {
        File baseDir = resolveCodeSourceBaseDir(clazz);
        if (baseDir == null) {
            return null;
        }
        return new File(baseDir, name);
    }

    private static File resolveCodeSourceBaseDir(Class<?> clazz) {
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

    private static File resolveCodeSourcePath(Class<?> clazz) {
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

}
