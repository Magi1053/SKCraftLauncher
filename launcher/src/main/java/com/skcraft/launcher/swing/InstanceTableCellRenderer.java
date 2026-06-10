/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.swing;

import com.skcraft.launcher.Instance;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.TableCellRenderer;
import java.awt.*;

public class InstanceTableCellRenderer extends JLabel implements TableCellRenderer {

    private static final int ICON_TEXT_GAP = 8;
    private static final int HORIZONTAL_INSET = 6;

    private final InstanceTableModel model;

    public InstanceTableCellRenderer(InstanceTableModel model) {
        this.model = model;
        setOpaque(true);
        setHorizontalAlignment(SwingConstants.LEFT);
        setIconTextGap(ICON_TEXT_GAP);
        setBorder(new EmptyBorder(0, HORIZONTAL_INSET, 0, HORIZONTAL_INSET));
    }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
            boolean hasFocus, int row, int column) {
        Instance instance = (Instance) value;
        setText(instance.getTitle());
        setIcon(model.getIcon(instance));

        if (isSelected) {
            Color activeSelectionBackground = UIManager.getColor("Table.selectionBackground");
            Color activeSelectionForeground = UIManager.getColor("Table.selectionForeground");
            setBackground(activeSelectionBackground != null ? activeSelectionBackground : table.getSelectionBackground());
            setForeground(activeSelectionForeground != null ? activeSelectionForeground : table.getSelectionForeground());
        } else {
            setBackground(table.getBackground());
            setForeground(table.getForeground());
        }

        return this;
    }

}
