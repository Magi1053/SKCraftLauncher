/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.browser.platform;

/**
 * macOS weblite / host WebKit browser configuration.
 */
public final class MacBrowserPlatform extends BrowserPlatform {

    MacBrowserPlatform(String osArch) {
        super(osArch);
    }

    @Override
    public String runtimePlatformKey() {
        return runtimePlatformKey("osx");
    }
}
