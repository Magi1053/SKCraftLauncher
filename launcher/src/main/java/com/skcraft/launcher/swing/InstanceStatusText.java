/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.swing;

import com.skcraft.launcher.Instance;
import com.skcraft.launcher.util.SharedLocale;

public final class InstanceStatusText {

    private InstanceStatusText() {
    }

    public static String forInstance(Instance instance) {
        if (!instance.isLocal()) {
            return SharedLocale.tr("options.instanceNotInstalled");
        }

        if (instance.isUpdatePending()) {
            return SharedLocale.tr("options.instanceUpdatePending");
        }

        if (instance.getManifestURL() == null) {
            return SharedLocale.tr("options.instanceInstalled");
        }

        return SharedLocale.tr("options.instanceUpToDate");
    }
}
