/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.swing;

import com.formdev.flatlaf.ui.FlatComboBoxUI;
import com.formdev.flatlaf.ui.FlatUIUtils;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.plaf.ComponentUI;
import java.awt.Graphics2D;

/**
 * FlatMac paints up+down chevrons on non-editable combos and overlays the popup
 * on the field. Keep mac button look, but use a single down arrow and pop down
 * below the combo (same as editable FlatMac combos / Aqua isPopDown).
 */
public class LauncherComboBoxUI extends FlatComboBoxUI {

    public static ComponentUI createUI(JComponent c) {
        return new LauncherComboBoxUI();
    }

    @Override
    public void installUI(JComponent c) {
        super.installUI(c);
        // FlatMac overlays the popup over the combo; force standard drop-down placement.
        c.putClientProperty("JComboBox.isPopDown", Boolean.TRUE);
    }

    @Override
    protected JButton createArrowButton() {
        return new FlatComboBoxButton() {
            @Override
            protected void paintArrow(Graphics2D g) {
                FlatUIUtils.paintArrow(g, 0, 0, getWidth(), getHeight(), getDirection(), chevron,
                        getArrowWidth(), getArrowThickness(), getXOffset(), getYOffset());
            }
        };
    }
}
