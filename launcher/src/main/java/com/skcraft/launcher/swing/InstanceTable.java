/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.swing;

import com.skcraft.launcher.Instance;

import javax.swing.table.TableModel;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;

public class InstanceTable extends DefaultTable {

    private static final String ROLLOVER_ROW_PROPERTY = "launcher.rolloverRow";

    private int rolloverRow = -1;

    public InstanceTable() {
        super();
        setTableHeader(null);
        setFont(InstanceRowStyle.titleFont());
        setRowHeight(InstanceRowStyle.ROW_HEIGHT);
        putClientProperty(ROLLOVER_ROW_PROPERTY, rolloverRow);
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                setRolloverRow(rowAtPoint(e.getPoint()));
            }
        });
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseExited(MouseEvent e) {
                setRolloverRow(-1);
            }
        });
    }

    @Override
    public void setModel(TableModel dataModel) {
        super.setModel(dataModel);
        if (dataModel instanceof InstanceTableModel) {
            setDefaultRenderer(Instance.class, new InstanceTableCellRenderer((InstanceTableModel) dataModel));
        }
    }

    private void setRolloverRow(int row) {
        if (row == rolloverRow) {
            return;
        }

        int oldRolloverRow = rolloverRow;
        rolloverRow = row;
        putClientProperty(ROLLOVER_ROW_PROPERTY, rolloverRow);
        repaintRow(oldRolloverRow);
        repaintRow(rolloverRow);
    }

    private void repaintRow(int row) {
        if (row >= 0 && row < getRowCount()) {
            repaint(getCellRect(row, 0, true));
        }
    }
}
