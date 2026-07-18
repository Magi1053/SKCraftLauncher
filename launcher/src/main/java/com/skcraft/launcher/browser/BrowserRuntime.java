/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.browser;

import com.skcraft.launcher.browser.swt.SwtRuntimeResolver;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Launcher policy for selecting and preparing its embedded browser backend.
 */
public enum BrowserRuntime {
    WKWEBVIEW,
    SWT;

    private static final BrowserRuntime BACKEND = detectHostBackend();

    static BrowserRuntime detectBackend() {
        return BACKEND;
    }

    private static BrowserRuntime detectHostBackend() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            return WKWEBVIEW;
        }
        return SWT;
    }

    public static boolean requiresSwt() {
        return detectBackend() == SWT;
    }

    public static URL[] resolveLauncherClasspath(URL launcherLocation, Path baseDir)
            throws IOException, InterruptedException {
        if (!requiresSwt()) {
            return new URL[] { launcherLocation };
        }

        Path swtJar = SwtRuntimeResolver.resolveSwtJar(baseDir);
        return new URL[] { launcherLocation, swtJar.toUri().toURL() };
    }
}
