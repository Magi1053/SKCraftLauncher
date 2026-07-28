/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.swing;

import com.skcraft.launcher.Launcher;
import com.skcraft.launcher.auth.AccountList;
import com.skcraft.launcher.auth.SavedSession;
import com.skcraft.launcher.util.SharedLocale;
import lombok.NonNull;

import javax.swing.*;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Footer control for viewing and changing the active launch account.
 *
 * <p>
 * Layout mirrors {@link InstanceTableCellRenderer} for the content row.
 * Height locked to {@link InstanceRowStyle#ROW_HEIGHT}.
 */
public class AccountSwitcher extends JPanel {

    private final AccountList accounts;
    /**
     * Exact twin of {@link InstanceTableCellRenderer} root: EmptyBorder(VERTICAL,
     * SIDE) around the icon+text panel. Height locked to
     * {@link InstanceRowStyle#ROW_HEIGHT}.
     */
    private final JPanel contentRow = new JPanel(new BorderLayout()) {
        @Override
        public Dimension getPreferredSize() {
            Dimension preferred = super.getPreferredSize();
            preferred.height = InstanceRowStyle.ROW_HEIGHT;
            return preferred;
        }

        @Override
        public Dimension getMinimumSize() {
            Dimension minimum = super.getMinimumSize();
            minimum.height = InstanceRowStyle.ROW_HEIGHT;
            return minimum;
        }

        @Override
        public Dimension getMaximumSize() {
            Dimension maximum = super.getMaximumSize();
            maximum.height = InstanceRowStyle.ROW_HEIGHT;
            return maximum;
        }
    };
    private final JPanel accountPanel = new JPanel(new BorderLayout(InstanceRowStyle.ICON_TEXT_GAP, 0));
    private final JPanel textPanel = new JPanel(new GridBagLayout());
    private final JLabel avatarLabel = new JLabel();
    private final JLabel usernameLabel = new JLabel();
    private final JLabel statusLabel = new JLabel();
    private final List<ActionListener> actionListeners = new ArrayList<ActionListener>();
    private final Icon defaultHead;
    private boolean hovered;

    public AccountSwitcher(@NonNull AccountList accounts) {
        super(new BorderLayout());
        this.accounts = accounts;
        this.defaultHead = SwingHelper.createIcon(Launcher.class, "default_skin.png",
                InstanceRowStyle.ICON_SIZE, InstanceRowStyle.ICON_SIZE);

        setOpaque(false);
        setBorder(SwingHelper.uiLineBorder());
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setToolTipText(SharedLocale.tr("accounts.manageTitle"));

        buildContentRow();

        add(contentRow, BorderLayout.CENTER);

        MouseAdapter interaction = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                setHovered(true);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                Point p = SwingUtilities.convertPoint((Component) e.getSource(), e.getPoint(), AccountSwitcher.this);
                if (!contains(p)) {
                    setHovered(false);
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e) && isEnabled()) {
                    fireActionPerformed();
                }
            }
        };
        addMouseListener(interaction);
        addMouseListenerToAccountControls(interaction);

        accounts.addListDataListener(new ListDataListener() {
            @Override
            public void intervalAdded(ListDataEvent e) {
                refresh();
            }

            @Override
            public void intervalRemoved(ListDataEvent e) {
                refresh();
            }

            @Override
            public void contentsChanged(ListDataEvent e) {
                refresh();
            }
        });
        refresh();
    }

    public void addActionListener(ActionListener listener) {
        actionListeners.add(listener);
    }

    public void removeActionListener(ActionListener listener) {
        actionListeners.remove(listener);
    }

    @Override
    public void updateUI() {
        super.updateUI();
        // Child fields are still null when JPanel's constructor invokes updateUI().
        if (usernameLabel != null) {
            usernameLabel.setFont(InstanceRowStyle.titleFont());
        }
        if (statusLabel != null) {
            statusLabel.setFont(statusLabel.getFont().deriveFont(
                    Font.PLAIN, InstanceRowStyle.SUBTITLE_FONT_SIZE));
            statusLabel.setForeground(SwingHelper.uiColor("Label.disabledForeground", Color.GRAY));
        }
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension preferred = super.getPreferredSize();
        preferred.height = rowHeightWithInsets();
        return preferred;
    }

    @Override
    public Dimension getMinimumSize() {
        Dimension minimum = super.getMinimumSize();
        minimum.height = rowHeightWithInsets();
        return minimum;
    }

    @Override
    public Dimension getMaximumSize() {
        Dimension maximum = super.getMaximumSize();
        maximum.height = rowHeightWithInsets();
        return maximum;
    }

    private int rowHeightWithInsets() {
        Insets insets = getInsets();
        return InstanceRowStyle.ROW_HEIGHT + insets.top + insets.bottom;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (!hovered) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setColor(InstanceRowStyle.hoverBackground());
            g2.fillRect(0, 0, getWidth(), getHeight());
        } finally {
            g2.dispose();
        }
    }

    public void refresh() {
        SavedSession active = accounts.getActiveAccount();

        if (active != null) {
            usernameLabel.setText(active.getUsername());
            statusLabel.setText(SharedLocale.tr(AccountList.isOfflineAccount(active)
                    ? "accounts.offlineMode"
                    : "accounts.switchAccount"));
            if (!AccountList.isOfflineAccount(active) && active.getAvatarImage() != null) {
                // Same construction as AccountSelectDialog.AccountRenderer.
                avatarLabel.setIcon(new ImageIcon(active.getAvatarImage()));
            } else {
                avatarLabel.setIcon(defaultHead);
            }
        } else {
            usernameLabel.setText(SharedLocale.tr("accounts.noAccountSelected"));
            statusLabel.setText(SharedLocale.tr("launcher.accountSignIn"));
            avatarLabel.setIcon(defaultHead);
        }

        revalidate();
        repaint();
    }

    private void buildContentRow() {
        // Same outer insets as InstanceTableCellRenderer root.
        contentRow.setOpaque(false);
        contentRow.setBorder(BorderFactory.createEmptyBorder(
                InstanceRowStyle.VERTICAL_INSET, InstanceRowStyle.SIDE_INSET,
                InstanceRowStyle.VERTICAL_INSET, InstanceRowStyle.SIDE_INSET));

        // Mirrors InstanceTableCellRenderer contentPanel.
        accountPanel.setOpaque(false);
        accountPanel.setBorder(BorderFactory.createEmptyBorder(
                0, InstanceRowStyle.HORIZONTAL_INSET, 0, InstanceRowStyle.HORIZONTAL_INSET));

        avatarLabel.setHorizontalAlignment(SwingConstants.CENTER);
        avatarLabel.setVerticalAlignment(SwingConstants.CENTER);
        avatarLabel.setOpaque(false);
        Dimension headSize = new Dimension(InstanceRowStyle.ICON_SIZE, InstanceRowStyle.ICON_SIZE);
        avatarLabel.setPreferredSize(headSize);
        avatarLabel.setMinimumSize(headSize);
        avatarLabel.setMaximumSize(new Dimension(InstanceRowStyle.ICON_SIZE, Integer.MAX_VALUE));

        usernameLabel.setFont(InstanceRowStyle.titleFont());
        usernameLabel.setOpaque(false);

        statusLabel.setFont(statusLabel.getFont().deriveFont(
                Font.PLAIN, InstanceRowStyle.SUBTITLE_FONT_SIZE));
        statusLabel.setForeground(SwingHelper.uiColor("Label.disabledForeground", Color.GRAY));
        statusLabel.setOpaque(false);

        textPanel.setOpaque(false);
        addTextRows();

        accountPanel.add(avatarLabel, BorderLayout.WEST);
        accountPanel.add(textPanel, BorderLayout.CENTER);
        contentRow.add(accountPanel, BorderLayout.CENTER);
    }

    private void addTextRows() {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = 0;
        constraints.anchor = GridBagConstraints.WEST;
        constraints.weightx = 1.0;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        textPanel.add(usernameLabel, constraints);

        constraints = (GridBagConstraints) constraints.clone();
        constraints.gridy = 1;
        constraints.insets = new Insets(InstanceRowStyle.TITLE_SUBTITLE_GAP, 0, 0, 0);
        textPanel.add(statusLabel, constraints);
    }

    private void addMouseListenerToAccountControls(MouseAdapter listener) {
        contentRow.addMouseListener(listener);
        accountPanel.addMouseListener(listener);
        avatarLabel.addMouseListener(listener);
        usernameLabel.addMouseListener(listener);
        statusLabel.addMouseListener(listener);
        textPanel.addMouseListener(listener);
    }

    private void setHovered(boolean hovered) {
        if (this.hovered == hovered) {
            return;
        }
        this.hovered = hovered;
        repaint();
    }

    private void fireActionPerformed() {
        ActionEvent event = new ActionEvent(this, ActionEvent.ACTION_PERFORMED, "accountSwitcher");
        for (ActionListener listener : actionListeners) {
            listener.actionPerformed(event);
        }
    }

}
