/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.swing;

import javax.swing.*;
import java.awt.*;

/**
 * Panel that keeps {@code Table.background} after FlatLaf theme switches.
 */
public class TableBackgroundPanel extends JPanel {

    public TableBackgroundPanel(LayoutManager layout) {
        super(layout);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        setOpaque(true);
        setBackground(SwingHelper.tableBackground());
    }
}
