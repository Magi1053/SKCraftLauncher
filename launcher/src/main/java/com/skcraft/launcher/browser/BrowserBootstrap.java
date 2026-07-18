/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.browser;

import com.beust.jcommander.ParameterException;
import com.skcraft.launcher.Launcher;
import lombok.extern.java.Log;

import javax.swing.*;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.logging.Level;

/**
 * Prepares the portable launcher classpath and browser-specific Swing settings.
 */
@Log
public final class BrowserBootstrap {

    private static final String BOOTSTRAPPED_PROPERTY = "launcher.swt.bootstrapped";

    private BrowserBootstrap() {
    }

    /**
     * Relaunch once with SWT when a standalone Windows/Linux launcher needs it.
     *
     * @return true when a child launcher was invoked and the caller should return
     */
    public static boolean prepare(String[] args) {
        if (!BrowserRuntime.requiresSwt()
                || Boolean.getBoolean(BOOTSTRAPPED_PROPERTY)
                || isSwtAvailable()) {
            return false;
        }

        try {
            Path baseDir = Launcher.resolveBaseDirFromArguments(args).toPath();
            URL launcherLocation = resolveLauncherLocation();
            URL[] classpath = BrowserRuntime.resolveLauncherClasspath(launcherLocation, baseDir);
            launchFromChildClassLoader(classpath, args);
            return true;
        } catch (ParameterException e) {
            log.log(Level.WARNING, "Browser runtime bootstrap skipped because launcher arguments are invalid.", e);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.log(Level.WARNING, "Browser runtime bootstrap was interrupted.", e);
            return false;
        } catch (IOException e) {
            log.log(Level.WARNING, "Browser runtime bootstrap could not prepare SWT.", e);
            return false;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to launch with the browser runtime.", e);
        }
    }

    /**
     * Apply host-specific Swing settings before constructing launcher windows.
     */
    public static void configureSwing() {
        if (BrowserRuntime.detectBackend() == BrowserRuntime.WKWEBVIEW) {
            JPopupMenu.setDefaultLightWeightPopupEnabled(false);
            ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false);
        }
    }

    private static boolean isSwtAvailable() {
        try {
            Class.forName("org.eclipse.swt.widgets.Display", false, Launcher.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static URL resolveLauncherLocation() throws IOException {
        URL location = Launcher.class.getProtectionDomain().getCodeSource().getLocation();
        if (location == null) {
            throw new IOException("Unable to determine launcher code source.");
        }
        return location;
    }

    private static void launchFromChildClassLoader(URL[] classpath, String[] args)
            throws IOException, ReflectiveOperationException {
        URLClassLoader child = new URLClassLoader(classpath, ClassLoader.getPlatformClassLoader());
        Thread thread = Thread.currentThread();
        ClassLoader previousContextLoader = thread.getContextClassLoader();
        String previousBootstrap = System.getProperty(BOOTSTRAPPED_PROPERTY);
        boolean launched = false;

        try {
            thread.setContextClassLoader(child);
            Class<?> launcherClass = Class.forName(Launcher.class.getName(), true, child);
            Method mainMethod = launcherClass.getMethod("main", String[].class);
            System.setProperty(BOOTSTRAPPED_PROPERTY, "true");
            mainMethod.invoke(null, new Object[] { args });
            launched = true;
        } catch (InvocationTargetException e) {
            rethrowLaunchFailure(e);
        } finally {
            if (!launched) {
                restoreParentState(thread, previousContextLoader, previousBootstrap);
            }
        }
    }

    private static void rethrowLaunchFailure(InvocationTargetException e) throws InvocationTargetException {
        Throwable cause = e.getCause();
        if (cause instanceof RuntimeException) {
            throw (RuntimeException) cause;
        }
        if (cause instanceof Error) {
            throw (Error) cause;
        }
        throw e;
    }

    private static void restoreParentState(Thread thread, ClassLoader previousContextLoader, String previousBootstrap) {
        thread.setContextClassLoader(previousContextLoader);
        if (previousBootstrap == null) {
            System.clearProperty(BOOTSTRAPPED_PROPERTY);
        } else {
            System.setProperty(BOOTSTRAPPED_PROPERTY, previousBootstrap);
        }
    }
}
