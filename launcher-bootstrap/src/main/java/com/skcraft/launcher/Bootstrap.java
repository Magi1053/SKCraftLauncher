/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher;

import com.skcraft.launcher.bootstrap.*;
import lombok.Getter;
import lombok.extern.java.Log;

import javax.swing.*;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.logging.Level;

import static com.skcraft.launcher.bootstrap.SharedLocale.tr;

@Log
public class Bootstrap {

    private static final int BOOTSTRAP_VERSION = 1;

    @Getter private final File baseDir;
    @Getter private final File binariesDir;
    @Getter private final Properties properties;
    private final String[] originalArgs;

    public static void main(String[] args) throws Throwable {
        SimpleLogFormatter.configureGlobalLogger();
        BootstrapFileLogging.init();
        SharedLocale.loadBundle("com.skcraft.launcher.lang.Bootstrap", Locale.getDefault());

        Bootstrap bootstrap = new Bootstrap(args);
        try {
            bootstrap.cleanup();
            bootstrap.launch();
        } catch (Throwable t) {
            Bootstrap.log.log(Level.WARNING, "Error", t);
            Bootstrap.setSwingLookAndFeel();
            SwingHelper.showErrorDialog(null, tr("errors.bootstrapError"), tr("errorTitle"), t);
        }
    }

    public Bootstrap(String[] args) throws IOException {
        this.properties = BootstrapUtils.loadProperties(
                Bootstrap.class,
                "bootstrap.properties",
                "com.skcraft.launcher.bootstrap.propertiesFile");

        File baseDir = resolveDataDir();

        this.baseDir = baseDir;
        BootstrapFileLogging.attachFile(this.baseDir);
        this.binariesDir = new File(baseDir, "launcher");
        this.originalArgs = args;

        binariesDir.mkdirs();
    }

    public void cleanup() {
        File[] files = binariesDir.listFiles(new FileFilter() {
            @Override
            public boolean accept(File pathname) {
                return pathname.getName().endsWith(".tmp");
            }
        });

        if (files != null) {
            for (File file : files) {
                file.delete();
            }
        }
    }

    public void launch() throws Throwable {
        File[] files = binariesDir.listFiles(new LauncherBinary.Filter());
        List<LauncherBinary> binaries = new ArrayList<LauncherBinary>();

        if (files != null) {
            for (File file : files) {
                Bootstrap.log.info("Found " + file.getAbsolutePath() + "...");
                binaries.add(new LauncherBinary(file));
            }
        }

        if (binaries.isEmpty()) {
            launchInitial();
            return;
        }

        Collections.sort(binaries);

        LauncherBinary currentBinary = findNewestExecutableBinary(binaries);
        if (currentBinary == null) {
            launchExisting(binaries, true);
            return;
        }

        try {
            String currentVersion = JarVersionReader.readVersion(currentBinary.getPath());
            UpdateChecker.UpdateInfo updateInfo = new UpdateChecker(this).checkForUpdate(currentVersion);
            if (updateInfo != null) {
                log.info("Found launcher update " + updateInfo.getVersion() + "; downloading before launch.");
                launchUpdate(binaries, updateInfo.getUrl());
                return;
            }
        } catch (Throwable t) {
            log.log(Level.WARNING, "Unable to perform bootstrap update check; launching local JAR.", t);
        }

        launchExisting(binaries, true);
    }

    public void launchInitial() throws Exception {
        Bootstrap.log.info("Downloading the launcher...");
        Thread thread = new Thread(new Downloader(this));
        thread.start();
    }

    private void launchUpdate(List<LauncherBinary> binaries, URL updateUrl) {
        Thread thread = new Thread(new Downloader(this, updateUrl, binaries));
        thread.start();
    }

    private LauncherBinary findNewestExecutableBinary(List<LauncherBinary> binaries) {
        for (LauncherBinary binary : binaries) {
            try {
                binary.getExecutableJar();
                return binary;
            } catch (LauncherBinary.PackedJarException e) {
                log.log(Level.WARNING, "Skipping packed launcher binary " + binary.getPath(), e);
            }
        }

        return null;
    }

    public void launchExisting(List<LauncherBinary> binaries, boolean redownload) throws Exception {
        Collections.sort(binaries);
        LauncherBinary working = null;
        Class<?> clazz = null;

        for (LauncherBinary binary : binaries) {
            File testFile = binary.getPath();
            try {
                testFile = binary.getExecutableJar();
                Bootstrap.log.info("Trying " + testFile.getAbsolutePath() + "...");
                clazz = load(testFile);
                Bootstrap.log.info("Launcher loaded successfully.");
                working = binary;
                break;
            } catch (Throwable t) {
                Bootstrap.log.log(Level.WARNING, "Failed to load " + testFile.getAbsoluteFile(), t);
            }
        }

        if (working != null) {
            for (LauncherBinary binary : binaries) {
                if (working != binary) {
                    log.info("Removing " + binary.getPath() + "...");
                    binary.remove();
                }
            }

            execute(clazz);
        } else {
            if (redownload) {
                launchInitial();
            } else {
                throw new IOException("Failed to find launchable .jar");
            }
        }
    }

    public void execute(Class<?> clazz) throws InvocationTargetException, IllegalAccessException, NoSuchMethodException {
        Method method = clazz.getDeclaredMethod("main", String[].class);
        String[] launcherArgs = new String[] {
                "--dir",
                baseDir.getAbsolutePath(),
                "--bootstrap-version",
                String.valueOf(BOOTSTRAP_VERSION) };

        String[] args = new String[originalArgs.length + launcherArgs.length];
        System.arraycopy(launcherArgs, 0, args, 0, launcherArgs.length);
        System.arraycopy(originalArgs, 0, args, launcherArgs.length, originalArgs.length);

        log.info("Launching with arguments " + Arrays.toString(args));

        method.invoke(null, new Object[] { args });
    }

    public Class<?> load(File jarFile) throws Exception {
        URL[] urls = new URL[] { jarFile.toURI().toURL() };
        URLClassLoader child = new URLClassLoader(urls, this.getClass().getClassLoader());
        Class<?> clazz = Class.forName(getProperties().getProperty("launcherClass"), true, child);

        String latestUrl = getProperties().getProperty("latestUrl");
        Properties prop = new Properties();
        prop.load(clazz.getResourceAsStream("launcher.properties"));
        String selfUpdateUrl = prop.getProperty("selfUpdateUrl");
        if (!Objects.equals(latestUrl, selfUpdateUrl)) {
            throw new Exception("Self Update URL is not equal to Latest URL");
        }

        return clazz;
    }

    public static void setSwingLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Throwable e) {
        }
    }

    private File resolveDataDir() throws IOException {
        if (!isWindows()) {
            return getUserLauncherDir();
        }

        File installDir = BootstrapUtils.getWindowsInstallDir(Bootstrap.class);
        if (installDir == null) {
            throw new IOException("Unable to determine launcher install directory.");
        }
        if (!ensureWritableDirectory(installDir)) {
            throw new IOException("Install directory is not writable: " + installDir);
        }

        LegacyDocumentsMigrator.migrateIfNeeded(installDir, BootstrapUtils.getLegacyWindowsDataDir(properties));

        File cwd = new File(System.getProperty("user.dir")).getAbsoluteFile();
        if (isWritableDirectory(cwd) && pathsEqual(cwd, installDir)) {
            return installDir;
        }

        log.info("Using install directory for launcher data: " + installDir.getAbsolutePath());
        return installDir;
    }

    private static boolean ensureWritableDirectory(File dir) {
        if (!dir.exists() && !dir.mkdirs()) {
            return false;
        }
        return isWritableDirectory(dir);
    }

    private static boolean isWritableDirectory(File dir) {
        return dir.isDirectory() && dir.canWrite();
    }

    private static boolean pathsEqual(File first, File second) {
        try {
            return first.getCanonicalPath().equalsIgnoreCase(second.getCanonicalPath());
        } catch (IOException e) {
            return first.getAbsolutePath().equalsIgnoreCase(second.getAbsolutePath());
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private File getUserLauncherDir() {
        String osName = System.getProperty("os.name").toLowerCase(Locale.ROOT);

        File dotFolder = new File(System.getProperty("user.home"), getProperties().getProperty("homeFolder"));
        String xdgFolderName = getProperties().getProperty("homeFolderLinux");

        if (osName.contains("linux") && !dotFolder.exists() && xdgFolderName != null && !xdgFolderName.isEmpty()) {
            String xdgDataHome = System.getenv("XDG_DATA_HOME");
            if (xdgDataHome == null || xdgDataHome.isEmpty()) {
                xdgDataHome = System.getProperty("user.home") + "/.local/share";
            }

            return new File(xdgDataHome, xdgFolderName);
        }

        return dotFolder;
    }
}
