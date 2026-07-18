/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.bootstrap.platform;

import com.skcraft.launcher.bootstrap.BootstrapUtils;

import java.io.File;
import java.util.Properties;

final class LinuxPlatformSupport extends PlatformSupport.Adapter {

    @Override
    public File resolveDataDir(Properties properties, Class<?> anchor) {
        String appName = resolveProperty(properties, "packageAppNameLinux", "packageAppName");
        String folderName = BootstrapUtils.sanitizeLinuxDataDirName(appName);

        String xdgDataHome = System.getenv("XDG_DATA_HOME");
        if (xdgDataHome == null || xdgDataHome.isEmpty()) {
            xdgDataHome = System.getProperty("user.home") + "/.local/share";
        }
        return new File(xdgDataHome, folderName);
    }
}
