/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.browser;

import com.skcraft.launcher.browser.platform.BrowserPlatform;
import com.skcraft.launcher.swing.SwingHelper;
import com.skcraft.launcher.util.Environment;
import com.skcraft.launcher.util.Platform;
import com.skcraft.launcher.util.SharedLocale;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;

/**
 * Shared Swing fallback content for unavailable or failed browser backends.
 */
public final class BrowserFallbackPanels {

    private BrowserFallbackPanels() {
    }

    public static JPanel buildUnavailablePanel(Component parent) {
        JPanel content = new JPanel(new MigLayout("insets 18, wrap 1, gap 0 8", "[center]", "[]6[]6[]12[]"));
        content.setOpaque(false);

        Icon icon = UIManager.getIcon("OptionPane.warningIcon");
        if (icon != null) {
            content.add(new JLabel(icon));
        }

        JLabel title = new JLabel(SharedLocale.tr("news.panel.unavailable.title"));
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 1));
        content.add(title);

        Platform platform = Environment.detectPlatform();
        String detailsKey;
        String installLabel = null;

        switch (platform) {
            case WINDOWS:
                detailsKey = "news.panel.unavailable.windows.details";
                installLabel = SharedLocale.tr("news.panel.unavailable.windows.download");
                break;
            case LINUX:
                detailsKey = "news.panel.unavailable.linux.details";
                installLabel = SharedLocale.tr("news.panel.unavailable.linux.download");
                break;
            case MAC_OS_X:
                detailsKey = "news.panel.unavailable.mac.details";
                break;
            default:
                detailsKey = "news.panel.unavailable.generic.details";
                break;
        }

        JLabel details = new JLabel("<html><div style=\"width: 320px; text-align: center;\">"
                + SwingHelper.htmlEscape(SharedLocale.tr(detailsKey))
                + "</div></html>");
        content.add(details);

        if (installLabel != null && BrowserPlatform.current().canInstallDependency()) {
            JButton installButton = new JButton(installLabel);
            installButton.addActionListener(event -> BrowserPlatform.current().installDependency(parent));
            content.add(installButton);
        }

        return centered(content);
    }

    private static JPanel centered(JPanel content) {
        JPanel panel = new JPanel(new GridBagLayout());
        SwingHelper.applyTableBackground(panel);
        panel.add(content);
        return panel;
    }
}
