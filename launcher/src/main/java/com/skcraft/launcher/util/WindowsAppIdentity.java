/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.util;

import com.sun.jna.WString;
import com.sun.jna.platform.win32.Shell32;
import lombok.extern.java.Log;

import java.util.logging.Level;

/** Applies the Windows AppUserModelID set by bootstrap for taskbar grouping. */
@Log
public final class WindowsAppIdentity {

    public static final String APP_USER_MODEL_ID_PROPERTY = "com.skcraft.launcher.appUserModelId";

    private WindowsAppIdentity() {
    }

    public static void applyIfPresent() {
        if (Environment.detectPlatform() != Platform.WINDOWS) {
            return;
        }

        String appUserModelId = System.getProperty(APP_USER_MODEL_ID_PROPERTY);
        if (appUserModelId == null || appUserModelId.isEmpty()) {
            return;
        }

        try {
            Shell32.INSTANCE.SetCurrentProcessExplicitAppUserModelID(new WString(appUserModelId));
        } catch (Throwable t) {
            log.log(Level.FINE, "Unable to apply Windows AppUserModelID", t);
        }
    }
}
