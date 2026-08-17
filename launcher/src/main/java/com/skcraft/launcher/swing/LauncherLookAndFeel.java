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
import javax.swing.text.JTextComponent;
import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.logging.Level;

@Log
public final class LauncherLookAndFeel {

    private static final String CUSTOM_DEFAULTS_SOURCE = "com.skcraft.launcher.theme";
    private static boolean clickToClearFocusInstalled;
    private static boolean clearDialogOpenFocusInstalled;
    private static boolean escapeToCloseDialogInstalled;

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
        installClickToClearFocus();
        installClearDialogOpenFocus();
        installEscapeToCloseDialog();
    }

    public static void applyTheme(String themeMode) {
        install(themeMode);
        FlatLaf.updateUI();
    }

    private static synchronized void installClickToClearFocus() {
        if (clickToClearFocusInstalled) {
            return;
        }

        Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
            if (!(event instanceof MouseEvent) || event.getID() != MouseEvent.MOUSE_RELEASED) {
                return;
            }

            Component clicked = ((MouseEvent) event).getComponent();
            if (requiresMouseFocus(clicked)) {
                return;
            }

            KeyboardFocusManager.getCurrentKeyboardFocusManager().clearGlobalFocusOwner();
        }, AWTEvent.MOUSE_EVENT_MASK);
        clickToClearFocusInstalled = true;
    }

    private static synchronized void installClearDialogOpenFocus() {
        if (clearDialogOpenFocusInstalled) {
            return;
        }

        Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
            if (!(event instanceof WindowEvent) || event.getID() != WindowEvent.WINDOW_OPENED) {
                return;
            }

            Window window = ((WindowEvent) event).getWindow();
            if (!(window instanceof JDialog) || !window.isDisplayable()) {
                return;
            }

            // After Swing assigns initial focus to first focusable child.
            SwingUtilities.invokeLater(() -> {
                if (!window.isDisplayable()) {
                    return;
                }
                KeyboardFocusManager manager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
                if (manager.getActiveWindow() == window) {
                    manager.clearGlobalFocusOwner();
                }
            });
        }, AWTEvent.WINDOW_EVENT_MASK);
        clearDialogOpenFocusInstalled = true;
    }

    private static synchronized void installEscapeToCloseDialog() {
        if (escapeToCloseDialogInstalled) {
            return;
        }

        Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
            if (!(event instanceof KeyEvent) || event.getID() != KeyEvent.KEY_PRESSED) {
                return;
            }

            KeyEvent keyEvent = (KeyEvent) event;
            if (keyEvent.getKeyCode() != KeyEvent.VK_ESCAPE || keyEvent.isConsumed()) {
                return;
            }

            MenuElement[] path = MenuSelectionManager.defaultManager().getSelectedPath();
            if (path != null && path.length > 0) {
                return;
            }

            Window active = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
            if (!(active instanceof JDialog) || !active.isDisplayable() || !active.isShowing()) {
                return;
            }

            active.dispatchEvent(new WindowEvent(active, WindowEvent.WINDOW_CLOSING));
            keyEvent.consume();
        }, AWTEvent.KEY_EVENT_MASK);
        escapeToCloseDialogInstalled = true;
    }

    private static boolean requiresMouseFocus(Component component) {
        for (Component current = component; current != null; current = current.getParent()) {
            if (current instanceof JTextComponent
                    || current instanceof JComboBox
                    || current instanceof JSpinner
                    || current instanceof JTable
                    || current instanceof JList
                    || current instanceof JTree
                    || current instanceof JSlider) {
                return true;
            }
            if (current instanceof Window) {
                break;
            }
        }
        return false;
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
