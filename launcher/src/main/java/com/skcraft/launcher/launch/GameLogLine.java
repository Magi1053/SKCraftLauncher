/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.launch;

import java.util.Objects;

/**
 * A game log line and its parsed severity.
 */
public final class GameLogLine {

    private final String text;
    private final GameLogLevel level;

    public GameLogLine(String text, GameLogLevel level) {
        this.text = Objects.requireNonNull(text, "text");
        this.level = level != null ? level : GameLogLevel.INFO;
    }

    public String getText() {
        return text;
    }

    public GameLogLevel getLevel() {
        return level;
    }
}
