package com.skcraft.launcher.agent.window;

import java.lang.instrument.Instrumentation;

/**
 * Launcher-owned window integration loaded before Minecraft and its libraries.
 */
public final class WindowIntegration {

    private WindowIntegration() {
    }

    public static void install(Instrumentation instrumentation) {
        try {
            instrumentation.addTransformer(new WindowClassTransformer(), false);
            System.out.println("[SKCraft Window Agent] In-process maximization enabled");
        } catch (Throwable t) {
            System.err.println("[SKCraft Window Agent] Could not install transformers: " + t);
        }
    }
}
