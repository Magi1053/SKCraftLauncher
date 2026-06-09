/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.swing;

import com.skcraft.launcher.Instance;

import javax.swing.table.TableModel;
import java.awt.Font;

public class InstanceTable extends DefaultTable {

    private static final int INSTANCE_ROW_HEIGHT = 44;
    private static final float INSTANCE_FONT_SIZE = 14.0f;

    public InstanceTable() {
        super();
        setTableHeader(null);
        setFont(getFont().deriveFont(Font.PLAIN, INSTANCE_FONT_SIZE));
        setRowHeight(INSTANCE_ROW_HEIGHT);
    }

    @Override
    public void setModel(TableModel dataModel) {
        super.setModel(dataModel);
        if (dataModel instanceof InstanceTableModel) {
            setDefaultRenderer(Instance.class, new InstanceTableCellRenderer((InstanceTableModel) dataModel));
        }
    }
}
