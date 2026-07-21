/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.launch;

import java.util.Locale;

/**
 * Severity of a parsed game log line.
 */
public enum GameLogLevel {
    FATAL,
    ERROR,
    WARN,
    INFO,
    DEBUG,
    TRACE;

    public static GameLogLevel fromName(String name) {
        if (name == null || name.isEmpty()) {
            return INFO;
        }

        String normalized = name.toUpperCase(Locale.ROOT);
        if ("WARNING".equals(normalized)) {
            return WARN;
        }

        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return INFO;
        }
    }
}
