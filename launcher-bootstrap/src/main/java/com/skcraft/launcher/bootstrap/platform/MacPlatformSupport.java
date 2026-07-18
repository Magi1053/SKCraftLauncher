/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.bootstrap.platform;

import java.io.File;
import java.util.Properties;

final class MacPlatformSupport extends PlatformSupport.Adapter {

    @Override
    public File resolveDataDir(Properties properties, Class<?> anchor) {
        String folderName = resolveProperty(properties, "packageAppNameMac", "packageAppName");
        return new File(System.getProperty("user.home"), "Library/Application Support/" + folderName);
    }
}
