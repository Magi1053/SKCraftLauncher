/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.swing;

import com.skcraft.launcher.Instance;
import com.skcraft.launcher.InstanceList;
import com.skcraft.launcher.Launcher;
import com.skcraft.launcher.util.SharedLocale;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;

public class InstanceTableModel extends AbstractTableModel {

    private static final int INSTANCE_ICON_SIZE = 24;
    private static final int DOWNLOAD_ICON_SIZE = 22;

    private final InstanceList instances;
    private final Icon instanceIcon;
    private final Icon customInstanceIcon;
    private final Icon downloadIcon;

    public InstanceTableModel(InstanceList instances) {
        this.instances = instances;
        instanceIcon = SwingHelper.createIcon(Launcher.class, "instance_icon.png", INSTANCE_ICON_SIZE, INSTANCE_ICON_SIZE);
        customInstanceIcon = SwingHelper.createIcon(Launcher.class, "custom_instance_icon.png", INSTANCE_ICON_SIZE, INSTANCE_ICON_SIZE);
        downloadIcon = SwingHelper.createIcon(Launcher.class, "download_icon.png", DOWNLOAD_ICON_SIZE, DOWNLOAD_ICON_SIZE);
    }

    public void update() {
        instances.sort();
        fireTableDataChanged();
    }

    @Override
    public String getColumnName(int columnIndex) {
        if (columnIndex == 0) {
            return SharedLocale.tr("launcher.modpackColumn");
        }
        return null;
    }

    @Override
    public Class<?> getColumnClass(int columnIndex) {
        if (columnIndex == 0) {
            return Instance.class;
        }
        return null;
    }

    @Override
    public void setValueAt(Object value, int rowIndex, int columnIndex) {
        if (columnIndex == 0) {
            instances.get(rowIndex).setSelected((boolean) (Boolean) value);
        }
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        return false;
    }

    @Override
    public int getRowCount() {
        return instances.size();
    }

    @Override
    public int getColumnCount() {
        return 1;
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        if (columnIndex == 0) {
            return instances.get(rowIndex);
        }
        return null;
    }

    public Icon getIcon(Instance instance) {
        if (!instance.isLocal()) {
            return downloadIcon;
        } else if (instance.getManifestURL() != null) {
            return instanceIcon;
        } else {
            return customInstanceIcon;
        }
    }

}
