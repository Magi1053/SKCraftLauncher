/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher;

import com.skcraft.launcher.bootstrap.*;
import com.skcraft.launcher.bootstrap.platform.PlatformSupport;
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

    @Getter
    private final File baseDir;
    @Getter
    private final File binariesDir;
    @Getter
    private final Properties properties;
    private final String[] originalArgs;

    public static void main(String[] args) throws Throwable {
        // Must run before any UI so the process matches Start Menu shortcut identity.
        applyAppIdentity();

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

    private static void applyAppIdentity() {
        try {
            PlatformSupport.INSTANCE.applyAppIdentity(Bootstrap.class);
        } catch (Throwable t) {
            log.log(Level.FINE, "Unable to apply platform app identity", t);
        }
    }

    public Bootstrap(String[] args) throws IOException {
        this.properties = BootstrapUtils.loadProperties(
                Bootstrap.class,
                "bootstrap.properties",
                "com.skcraft.launcher.bootstrap.propertiesFile");

        File baseDir = BootstrapUtils.resolveDataDir(properties, Bootstrap.class);

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
        BundledSeed.copyIfNeeded(Bootstrap.class, baseDir.toPath(), binariesDir.toPath());

        File[] files = binariesDir.listFiles(new LauncherBinary.Filter());
        List<LauncherBinary> binaries = new ArrayList<LauncherBinary>();

        if (files != null) {
            for (File file : files) {
                Bootstrap.log.info("Found " + file.getAbsolutePath() + "...");
                binaries.add(new LauncherBinary(file));
            }
        }

        refreshLauncherVersionFile(binaries);

        if (binaries.isEmpty()) {
            downloadAndLaunch(Collections.<LauncherBinary>emptyList());
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
                downloadAndLaunch(new Downloader(this, updateInfo.getUrl(), binaries));
                return;
            }
        } catch (Throwable t) {
            log.log(Level.WARNING, "Unable to perform bootstrap update check; launching local JAR.", t);
        }

        launchExisting(binaries, true);
    }

    private void downloadAndLaunch(List<LauncherBinary> existingBinaries) throws Exception {
        Bootstrap.log.info("Downloading the launcher...");
        downloadAndLaunch(new Downloader(this, existingBinaries));
    }

    private void downloadAndLaunch(Downloader downloader) throws Exception {
        Thread thread = new Thread(downloader);
        thread.start();
        thread.join();

        List<LauncherBinary> binaries = downloader.getBinaries();
        if (binaries != null && !binaries.isEmpty()) {
            launchExisting(binaries, false);
        }
    }

    private void refreshLauncherVersionFile(List<LauncherBinary> binaries) {
        LauncherBinary newest = findNewestExecutableBinary(binaries);
        if (newest == null) {
            return;
        }

        try {
            BootstrapUtils.writeLauncherVersionFile(newest.getPath());
        } catch (Throwable t) {
            log.log(Level.WARNING, "Unable to write launcher.version sidecar.", t);
        }
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
        Throwable lastFailure = null;

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
                lastFailure = t;
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
                downloadAndLaunch(binaries);
            } else if (lastFailure != null) {
                String message = lastFailure.getMessage();
                if (message == null || message.trim().isEmpty()) {
                    message = "Failed to find launchable .jar";
                }
                throw new IOException(message, lastFailure);
            } else {
                throw new IOException("Failed to find launchable .jar");
            }
        }
    }

    public void execute(Class<?> clazz)
            throws InvocationTargetException, IllegalAccessException, NoSuchMethodException {
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

        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(clazz.getClassLoader());
        try {
            method.invoke(null, new Object[] { args });
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    public String resolveSelfUpdateUrl() throws IOException {
        File[] files = binariesDir.listFiles(new LauncherBinary.Filter());
        if (files != null && files.length > 0) {
            List<LauncherBinary> binaries = new ArrayList<LauncherBinary>();
            for (File file : files) {
                binaries.add(new LauncherBinary(file));
            }
            Collections.sort(binaries);

            for (LauncherBinary binary : binaries) {
                try {
                    return JarVersionReader.readSelfUpdateUrl(binary.getPath());
                } catch (IOException e) {
                    log.log(Level.WARNING, "Unable to read self-update URL from " + binary.getPath(), e);
                }
            }
        }

        return JarVersionReader.readSelfUpdateUrlFromClasspath(Bootstrap.class);
    }

    public Class<?> load(File jarFile) throws Exception {
        URL launcherUrl = jarFile.toURI().toURL();
        URL[] urls = new URL[] { launcherUrl };
        URLClassLoader child = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
        Class<?> clazz = Class.forName(getProperties().getProperty("launcherClass"), true, child);

        String expectedUrl = resolveSelfUpdateUrl();
        String actualUrl = JarVersionReader.readSelfUpdateUrl(jarFile);
        if (!Objects.equals(expectedUrl, actualUrl)) {
            throw new Exception("Self-update URL does not match expected self-update URL");
        }

        return clazz;
    }

    public static void setSwingLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Throwable e) {
        }
    }
}
