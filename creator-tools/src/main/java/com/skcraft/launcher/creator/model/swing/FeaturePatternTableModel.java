/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.creator.model.swing;

import com.skcraft.launcher.builder.FeaturePattern;

import javax.swing.table.AbstractTableModel;
import java.util.Collections;
import java.util.List;

public class FeaturePatternTableModel extends AbstractTableModel {

    private final List<FeaturePattern> features;

    public FeaturePatternTableModel(List<FeaturePattern> features) {
        this.features = features;
    }

    @Override
    public String getColumnName(int columnIndex) {
        switch (columnIndex) {
            case 0:
                return "Feature";
            case 1:
                return "Recommendation";
            case 2:
                return "Default?";
            case 3:
                return "Min Memory Increase";
            default:
                return null;
        }
    }

    @Override
    public Class<?> getColumnClass(int columnIndex) {
        switch (columnIndex) {
            case 0:
                return String.class;
            case 1:
                return String.class;
            case 2:
                return String.class;
            case 3:
                return Integer.class;
            default:
                return null;
        }
    }

    @Override
    public void setValueAt(Object value, int rowIndex, int columnIndex) {
    }

    @Override
    public boolean isCellEditable(int rowIndex, int columnIndex) {
        return false;
    }

    @Override
    public int getRowCount() {
        return features.size();
    }

    @Override
    public int getColumnCount() {
        return 4;
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        switch (columnIndex) {
            case 0:
                return features.get(rowIndex).getFeature().getName();
            case 1:
                return features.get(rowIndex).getFeature().getRecommendation();
            case 2:
                return features.get(rowIndex).getFeature().isSelected() ? "Yes" : "";
            case 3:
                return features.get(rowIndex).getFeature().getMinMemoryDelta();
            default:
                return null;
        }
    }

    public FeaturePattern getFeature(int index) {
        return features.get(index);
    }

    public void addFeature(FeaturePattern pattern) {
        features.add(pattern);
        fireTableDataChanged();
    }

    public void removeFeature(int index) {
        features.remove(index);
        fireTableDataChanged();
    }

    public void moveFeatureUp(int index) {
        if (index <= 0 || index >= features.size()) {
            return;
        }

        Collections.swap(features, index, index - 1);
        fireTableRowsUpdated(index - 1, index);
    }

    public void moveFeatureDown(int index) {
        if (index < 0 || index >= features.size() - 1) {
            return;
        }

        Collections.swap(features, index, index + 1);
        fireTableRowsUpdated(index, index + 1);
    }

}
