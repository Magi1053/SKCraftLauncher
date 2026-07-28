/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.bootstrap.platform;

import com.skcraft.launcher.bootstrap.BootstrapUtils;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class WindowsPlatformSupport extends PlatformSupport.Adapter {

    private static final String APP_USER_MODEL_ID_PREFIX = "SKCraft.";
    private static final String APP_USER_MODEL_ID_PROPERTY = "com.skcraft.launcher.appUserModelId";
    private static final String INSTALL_BASE_DIR_PROPERTY = "com.skcraft.launcher.installBaseDir";
    private static final Pattern ENVIRONMENT_VARIABLE_PATTERN = Pattern.compile("%([^%]+)%");

    @Override
    public File resolveDataDir(Properties properties, Class<?> anchor) throws IOException {
        File installDir = resolveInstallDir(anchor);
        if (installDir == null) {
            installDir = resolveDefaultInstallDir(properties);
        }
        if (!ensureWritableDirectory(installDir)) {
            throw new IOException("Install directory is not writable: " + installDir);
        }

        File dataDir = resolveDataDir(installDir);
        if (!ensureWritableDirectory(dataDir)) {
            throw new IOException("Data directory is not writable: " + dataDir);
        }
        return dataDir;
    }

    @Override
    public void applyAppIdentity(Class<?> anchor) {
        // Publish only — JNA must initialize in the launcher classloader.
        File installDir = resolveInstallDir(anchor);
        if (installDir != null) {
            System.setProperty(APP_USER_MODEL_ID_PROPERTY, APP_USER_MODEL_ID_PREFIX + installDir.getName());
        }
    }

    private static File resolveDefaultInstallDir(Properties properties) throws IOException {
        String configuredBaseDir = System.getProperty(INSTALL_BASE_DIR_PROPERTY);
        if (configuredBaseDir == null || configuredBaseDir.trim().isEmpty()) {
            configuredBaseDir = properties.getProperty("installBaseDirWindows");
        }
        if (configuredBaseDir == null || configuredBaseDir.trim().isEmpty()) {
            throw new IOException("Missing required bootstrap property: installBaseDirWindows");
        }

        File baseDir = new File(expandEnvironmentVariables(configuredBaseDir.trim())).getAbsoluteFile();
        String installDirName = resolveProperty(
                properties, "packageAppNameWindows", "packageAppName");
        if (installDirName.isEmpty()) {
            installDirName = "launcher";
        }
        return new File(baseDir, BootstrapUtils.sanitizeInstallDirName(installDirName));
    }

    private static File resolveInstallDir(Class<?> anchor) {
        File codeSourcePath = BootstrapUtils.resolveCodeSourcePath(anchor);
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

    private static File resolveDataDir(File installDir) {
        File nestedDataDir = new File(installDir, BootstrapUtils.DATA_SUBDIR);
        if (nestedDataDir.isDirectory()) {
            return nestedDataDir;
        }
        return hasFlatLayoutData(installDir) ? installDir : nestedDataDir;
    }

    private static boolean hasFlatLayoutData(File dir) {
        return new File(dir, "launcher").isDirectory()
                || new File(dir, "config.json").isFile()
                || new File(dir, "instances").isDirectory()
                || new File(dir, "runtimes").isDirectory();
    }

    private static boolean ensureWritableDirectory(File dir) {
        if (!dir.exists() && !dir.mkdirs()) {
            return false;
        }
        return dir.isDirectory() && dir.canWrite();
    }

    private static String expandEnvironmentVariables(String value) throws IOException {
        Matcher matcher = ENVIRONMENT_VARIABLE_PATTERN.matcher(value);
        StringBuffer expanded = new StringBuffer();
        while (matcher.find()) {
            String variableName = matcher.group(1);
            String environmentValue = getEnvironmentVariable(variableName);
            if (environmentValue == null || environmentValue.isEmpty()) {
                throw new IOException("Undefined Windows environment variable: %" + variableName + "%");
            }
            matcher.appendReplacement(expanded, Matcher.quoteReplacement(environmentValue));
        }
        matcher.appendTail(expanded);
        return expanded.toString();
    }

    private static String getEnvironmentVariable(String name) {
        String value = System.getenv(name);
        if (value != null) {
            return value;
        }

        for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

}
