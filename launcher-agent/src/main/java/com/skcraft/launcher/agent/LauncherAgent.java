package com.skcraft.launcher.agent;

import com.skcraft.launcher.agent.window.WindowIntegration;

import java.lang.instrument.Instrumentation;

/**
 * Entry point for launcher-owned Minecraft integrations.
 */
public final class LauncherAgent {

    private static final String START_MAXIMIZED_OPTION = "startMaximized";

    private LauncherAgent() {
    }

    public static void premain(String options, Instrumentation instrumentation) {
        if (!START_MAXIMIZED_OPTION.equals(options)) {
            return;
        }

        WindowIntegration.install(instrumentation);
    }
}
