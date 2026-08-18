package com.skcraft.launcher.windowagent;

import java.lang.instrument.Instrumentation;

/**
 * Launcher-owned window integration loaded before Minecraft and its libraries.
 */
public final class WindowAgent {

    private static final String ENABLE_OPTION = "startMaximized";

    private WindowAgent() {
    }

    public static void premain(String options, Instrumentation instrumentation) {
        if (!ENABLE_OPTION.equals(options)) {
            return;
        }

        try {
            instrumentation.addTransformer(new WindowClassTransformer(), false);
            System.out.println("[SKCraft Window Agent] In-process maximization enabled");
        } catch (Throwable t) {
            System.err.println("[SKCraft Window Agent] Could not install transformers: " + t);
        }
    }
}
