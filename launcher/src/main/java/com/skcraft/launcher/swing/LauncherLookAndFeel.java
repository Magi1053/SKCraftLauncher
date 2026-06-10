/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.swing;

import com.skcraft.launcher.Configuration;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;
import lombok.extern.java.Log;

import javax.swing.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.logging.Level;

@Log
public final class LauncherLookAndFeel {

    private static final String CUSTOM_DEFAULTS_SOURCE = "com.skcraft.launcher.theme";

    private LauncherLookAndFeel() {
    }

    public static void install(String themeMode) {
        try {
            FlatLaf.registerCustomDefaultsSource(CUSTOM_DEFAULTS_SOURCE);
            FlatLaf.setUseNativeWindowDecorations(true);
            if (isDarkTheme(themeMode)) {
                FlatMacDarkLaf.setup();
            } else {
                FlatMacLightLaf.setup();
            }
        } catch (Exception e) {
            log.log(Level.WARNING, "Failed to set FlatLaf, falling back to system look and feel", e);
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception fallbackEx) {
                log.log(Level.WARNING, "Failed to set fallback look and feel", fallbackEx);
            }
        }
    }

    public static void applyTheme(String themeMode) {
        install(themeMode);
        FlatLaf.updateUI();
    }

    public static boolean isDarkTheme(String themeMode) {
        String normalized = normalizeThemeMode(themeMode);
        if (Configuration.THEME_DARK.equals(normalized)) {
            return true;
        }
        if (Configuration.THEME_SYSTEM.equals(normalized)) {
            return isSystemDarkTheme();
        }
        return false;
    }

    private static String normalizeThemeMode(String themeMode) {
        if (themeMode == null || themeMode.trim().isEmpty()) {
            return Configuration.THEME_LIGHT;
        }

        String normalized = themeMode.trim().toLowerCase(Locale.ROOT);
        if (Configuration.THEME_DARK.equals(normalized)) {
            return Configuration.THEME_DARK;
        }
        if (Configuration.THEME_SYSTEM.equals(normalized)) {
            return Configuration.THEME_SYSTEM;
        }
        return Configuration.THEME_LIGHT;
    }

    private static boolean isSystemDarkTheme() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) {
                return isWindowsSystemDarkTheme();
            }
            if (os.contains("mac")) {
                return isMacSystemDarkTheme();
            }
        } catch (InterruptedException e) {
            log.log(Level.FINE, "Unable to read OS theme mode", e);
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            log.log(Level.FINE, "Unable to read OS theme mode", e);
        }
        return false;
    }

    private static boolean isWindowsSystemDarkTheme() throws IOException, InterruptedException {
        String output = runCommand("reg", "query",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                "/v", "AppsUseLightTheme");
        return output.contains("0x0");
    }

    private static boolean isMacSystemDarkTheme() throws IOException, InterruptedException {
        String output = runCommand("defaults", "read", "-g", "AppleInterfaceStyle");
        return output.toLowerCase(Locale.ROOT).contains("dark");
    }

    private static String runCommand(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).start();
        StringBuilder output = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }

        process.waitFor();
        return output.toString();
    }
}
