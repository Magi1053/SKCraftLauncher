/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.swing;

import com.skcraft.launcher.Instance;

import javax.swing.*;
import javax.swing.table.TableCellRenderer;
import java.awt.*;

public class InstanceTableCellRenderer extends JPanel implements TableCellRenderer {

    private final InstanceTableModel model;
    private final JLabel iconLabel = new JLabel();
    private final JLabel titleLabel = new JLabel();
    private final JLabel subtitleLabel = new JLabel();

    public InstanceTableCellRenderer(InstanceTableModel model) {
        super(new BorderLayout());
        this.model = model;
        setOpaque(true);
        setBorder(BorderFactory.createEmptyBorder(
                InstanceRowStyle.VERTICAL_INSET, InstanceRowStyle.SIDE_INSET,
                InstanceRowStyle.VERTICAL_INSET, InstanceRowStyle.SIDE_INSET));

        int iconColumnWidth = model.getIconColumnWidth();
        JPanel contentPanel = new JPanel(new BorderLayout(InstanceRowStyle.ICON_TEXT_GAP, 0));
        contentPanel.setOpaque(false);
        contentPanel.setBorder(BorderFactory.createEmptyBorder(
                0, InstanceRowStyle.HORIZONTAL_INSET, 0, InstanceRowStyle.HORIZONTAL_INSET));

        iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
        iconLabel.setVerticalAlignment(SwingConstants.CENTER);
        Dimension iconSize = new Dimension(iconColumnWidth, iconColumnWidth);
        iconLabel.setPreferredSize(iconSize);
        iconLabel.setMinimumSize(iconSize);
        iconLabel.setMaximumSize(new Dimension(iconColumnWidth, Integer.MAX_VALUE));
        iconLabel.setOpaque(false);

        titleLabel.setFont(InstanceRowStyle.titleFont());
        titleLabel.setOpaque(false);

        subtitleLabel.setFont(subtitleLabel.getFont().deriveFont(
                Font.PLAIN, InstanceRowStyle.SUBTITLE_FONT_SIZE));
        subtitleLabel.setOpaque(false);

        contentPanel.add(iconLabel, BorderLayout.WEST);
        contentPanel.add(createTextPanel(), BorderLayout.CENTER);
        add(contentPanel, BorderLayout.CENTER);
    }

    @Override
    public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
            boolean hasFocus, int row, int column) {
        Instance instance = (Instance) value;
        iconLabel.setIcon(model.getIcon(instance));
        titleLabel.setText(instance.getTitle());
        subtitleLabel.setText(InstanceStatusText.forInstance(instance));

        Color mutedForeground = SwingHelper.uiColor("Label.disabledForeground", table.getForeground());

        if (isSelected) {
            Color activeSelectionBackground = UIManager.getColor("Table.selectionBackground");
            Color activeSelectionForeground = UIManager.getColor("Table.selectionForeground");
            Color selectionForeground = activeSelectionForeground != null
                    ? activeSelectionForeground : table.getSelectionForeground();
            setBackground(activeSelectionBackground != null ? activeSelectionBackground : table.getSelectionBackground());
            titleLabel.setForeground(selectionForeground);
            subtitleLabel.setForeground(selectionForeground);
        } else if (isRolloverRow(table, row)) {
            setBackground(InstanceRowStyle.hoverBackground());
            titleLabel.setForeground(table.getForeground());
            subtitleLabel.setForeground(mutedForeground);
        } else {
            setBackground(table.getBackground());
            titleLabel.setForeground(table.getForeground());
            subtitleLabel.setForeground(mutedForeground);
        }

        return this;
    }

    private JPanel createTextPanel() {
        JPanel textPanel = new JPanel(new GridBagLayout());
        textPanel.setOpaque(false);

        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.weightx = 1.0;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        textPanel.add(titleLabel, constraints);

        constraints = (GridBagConstraints) constraints.clone();
        constraints.gridy = 1;
        constraints.insets = new Insets(InstanceRowStyle.TITLE_SUBTITLE_GAP, 0, 0, 0);
        textPanel.add(subtitleLabel, constraints);

        return textPanel;
    }

    private static boolean isRolloverRow(JTable table, int row) {
        Object rolloverRow = table.getClientProperty("launcher.rolloverRow");
        return rolloverRow instanceof Integer && ((Integer) rolloverRow).intValue() == row;
    }

}
