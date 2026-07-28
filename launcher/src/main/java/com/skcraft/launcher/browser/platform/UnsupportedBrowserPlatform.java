/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.browser.platform;

import java.io.IOException;

/**
 * Browser policy for hosts without a bundled Weblite native runtime.
 */
final class UnsupportedBrowserPlatform extends BrowserPlatform {

    private final String osName;
    private final String osArch;

    UnsupportedBrowserPlatform(String osName, String osArch) {
        super(osArch);
        this.osName = osName;
        this.osArch = osArch;
    }

    @Override
    public String runtimePlatformKey() throws IOException {
        throw new IOException(
                "Unsupported platform for weblite loader: os="
                        + osName + ", arch=" + osArch);
    }
}
