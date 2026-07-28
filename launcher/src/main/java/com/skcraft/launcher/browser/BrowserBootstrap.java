/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.browser;

import com.beust.jcommander.ParameterException;
import com.skcraft.launcher.browser.platform.BrowserPlatform;
import com.skcraft.launcher.browser.weblite.WebliteRuntimeResolver;
import com.skcraft.launcher.Launcher;
import lombok.extern.java.Log;

import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Level;

/**
 * Prepares the process environment and verified native bridge for weblite.
 */
@Log
public final class BrowserBootstrap {

    private static final BrowserPlatform PLATFORM = BrowserPlatform.current();

    private BrowserBootstrap() {
    }

    /**
     * Prepare weblite before any browser peer or host toolkit is initialized.
     */
    public static void prepare(String[] args) {
        try {
            Path baseDir = Launcher.resolveBaseDirFromArguments(args).toPath();
            PLATFORM.configureEnvironment(baseDir);
            WebliteRuntimeResolver.resolveAndLoad(
                    baseDir, PLATFORM.runtimePlatformKey());
        } catch (ParameterException e) {
            log.log(Level.WARNING, "Browser runtime bootstrap skipped because launcher arguments are invalid.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.log(Level.WARNING, "Browser runtime bootstrap was interrupted.", e);
        } catch (IOException e) {
            log.log(Level.WARNING, "Browser runtime bootstrap could not prepare weblite.", e);
        } catch (LinkageError e) {
            log.log(Level.WARNING, "Browser runtime native bridge could not be loaded.", e);
        }
    }

    /**
     * Apply host-specific Swing settings before constructing launcher windows.
     */
    public static void configureSwing() {
        PLATFORM.configureSwing();
    }
}
