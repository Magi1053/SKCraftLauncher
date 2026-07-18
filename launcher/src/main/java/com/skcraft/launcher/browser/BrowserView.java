/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.browser;

import javax.swing.border.Border;
import java.awt.Component;
import java.net.URL;

/**
 * Swing-facing contract implemented by each embedded browser backend.
 */
public interface BrowserView {

    Component getComponent();

    void setBrowserBorder(Border border);

    void setDarkTheme(boolean darkTheme);

    void load(URL url);

    void loadHtml(String html);

    void disposeBrowser();
}
