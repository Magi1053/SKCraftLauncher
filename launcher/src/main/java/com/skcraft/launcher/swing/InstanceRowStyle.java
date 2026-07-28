/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.swing;

import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;

/**
 * Shared metrics for instance table rows, account switcher, and account list rows.
 * Keep these in sync so the UIs cannot drift.
 */
public final class InstanceRowStyle {

    /** {@link InstanceTable} / content-row height. */
    public static final int ROW_HEIGHT = 48;
    /** Instance / avatar icon edge length. */
    public static final int ICON_SIZE = 32;
    /** Title font size (instance rows and account switcher). */
    public static final float TITLE_FONT_SIZE = 14.0f;
    /** Subtitle font size. */
    public static final float SUBTITLE_FONT_SIZE = 11.0f;
    /** Outer EmptyBorder vertical inset on the row root. */
    public static final int VERTICAL_INSET = 0;
    /** Outer EmptyBorder horizontal inset on the row root. */
    public static final int SIDE_INSET = 2;
    /** Inner content panel horizontal EmptyBorder. */
    public static final int HORIZONTAL_INSET = 6;
    /** Gap between icon and text columns. */
    public static final int ICON_TEXT_GAP = 8;
    /** Gap between title and subtitle (top inset on subtitle). */
    public static final int TITLE_SUBTITLE_GAP = 0;
    /** Hover fill alpha on selection background. */
    public static final int HOVER_ALPHA = 42;

    private InstanceRowStyle() {
    }

    /**
     * Title font at {@link #TITLE_FONT_SIZE}: FlatLaf {@code semibold.font} when present
     * (Segoe UI Semibold / platform equivalent), else Label.font at that size.
     */
    public static Font titleFont() {
        Font semibold = UIManager.getFont("semibold.font");
        if (semibold != null) {
            return semibold.deriveFont(TITLE_FONT_SIZE);
        }
        Font label = UIManager.getFont("Label.font");
        if (label != null) {
            return label.deriveFont(TITLE_FONT_SIZE);
        }
        return new Font(Font.DIALOG, Font.PLAIN, Math.round(TITLE_FONT_SIZE));
    }

    /**
     * Semi-transparent hover fill from table/list selection colors.
     */
    public static Color hoverBackground() {
        Color hover = UIManager.getColor("Table.selectionBackground");
        if (hover == null) {
            hover = UIManager.getColor("List.selectionBackground");
        }
        if (hover == null) {
            hover = UIManager.getColor("Component.accentColor");
        }
        if (hover == null) {
            hover = Color.GRAY;
        }
        return new Color(hover.getRed(), hover.getGreen(), hover.getBlue(), HOVER_ALPHA);
    }
}
