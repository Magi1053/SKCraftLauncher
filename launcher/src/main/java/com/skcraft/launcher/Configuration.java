/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * The configuration for the launcher.
 * </p>
 * Default values are stored as field values. Note that if a default
 * value is changed after the launcher has been deployed, it may not take effect
 * for users who have already used the launcher because the old default
 * values would have been written to disk.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Configuration {

    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";
    public static final String THEME_SYSTEM = "system";

    private boolean offlineEnabled = false;
    private int windowWidth = 854;
    private int windowHeight = 480;
    private boolean maximizeWindow = true;
    private boolean showInstanceConsole = false;
    private boolean darkTheme = false;
    private String themeMode;
    private boolean proxyEnabled = false;
    private String proxyHost = "localhost";
    private int proxyPort = 8080;
    private String proxyUsername;
    private String proxyPassword;
    private String gameKey;
    private String lastInstance;
    private boolean serverEnabled = false;
    private String serverHost;
    private int serverPort = 25565;

    @Override
    public boolean equals(Object o) {
        return super.equals(o);
    }

    @Override
    public int hashCode() {
        return super.hashCode();
    }

    /**
     * Backwards compatibility for old configs with the misspelling.
     */
    public void setWidowHeight(int height) {
        this.windowHeight = height;
    }

    /**
     * Backwards compatibility for old configs with jvmPaths
     */
    public void setJvmPath(String jvmPath) {
        // Global Java runtime settings are no longer used.
    }

    public String getThemeMode() {
        if (themeMode == null || themeMode.trim().isEmpty()) {
            return darkTheme ? THEME_DARK : THEME_LIGHT;
        }

        String normalized = themeMode.trim().toLowerCase();
        if (THEME_DARK.equals(normalized)) {
            return THEME_DARK;
        }
        if (THEME_SYSTEM.equals(normalized)) {
            return THEME_SYSTEM;
        }
        return THEME_LIGHT;
    }

    public void setThemeMode(String themeMode) {
        this.themeMode = themeMode;
        this.darkTheme = THEME_DARK.equals(getThemeMode());
    }
}
