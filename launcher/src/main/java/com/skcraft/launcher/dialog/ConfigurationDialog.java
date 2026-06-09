/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.dialog;

import com.skcraft.launcher.Configuration;
import com.skcraft.launcher.Instance;
import com.skcraft.launcher.Launcher;
import com.skcraft.launcher.persistence.Persistence;
import com.skcraft.launcher.swing.*;
import com.skcraft.launcher.util.SharedLocale;
import com.google.common.base.Strings;
import lombok.NonNull;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * A dialog to modify configuration options.
 */
public class ConfigurationDialog extends JDialog {

    private final Configuration config;
    private final Launcher launcher;
    private final ObjectSwingMapper mapper;
    private final String originalGameKey;
    private boolean gameKeyChanged;

    private final JPanel tabContainer = new JPanel(new BorderLayout());
    private final JTabbedPane tabbedPane = new JTabbedPane();
    private final JPanel instanceSettingsPanel = new JPanel(new MigLayout("fillx, wrap 1, ins 12", "[grow]", ""));
    private final FormPanel gameSettingsPanel = new FormPanel();
    private final JSpinner widthSpinner = new JSpinner();
    private final JSpinner heightSpinner = new JSpinner();
    private final FormPanel proxySettingsPanel = new FormPanel();
    private final JCheckBox useProxyCheck = new JCheckBox(SharedLocale.tr("options.useProxyCheck"));
    private final JTextField proxyHostText = new JTextField();
    private final JSpinner proxyPortText = new JSpinner();
    private final JTextField proxyUsernameText = new JTextField();
    private final JPasswordField proxyPasswordText = new JPasswordField();
    private final FormPanel advancedPanel = new FormPanel();
    private final JTextField gameKeyText = new JTextField();
    private final LinedBoxPanel buttonsPanel = new LinedBoxPanel(true);
    private final JButton okButton = new JButton(SharedLocale.tr("button.ok"));
    private final JButton cancelButton = new JButton(SharedLocale.tr("button.cancel"));
    private final JButton aboutButton = new JButton(SharedLocale.tr("options.about"));
    private final JButton logButton = new JButton(SharedLocale.tr("options.launcherConsole"));

    /**
     * Create a new configuration dialog.
     *
     * @param owner    the window owner
     * @param launcher the launcher
     */
    public ConfigurationDialog(Window owner, @NonNull Launcher launcher) {
        super(owner, ModalityType.DOCUMENT_MODAL);

        this.config = launcher.getConfig();
        this.launcher = launcher;
        this.originalGameKey = Strings.nullToEmpty(config.getGameKey());
        mapper = new ObjectSwingMapper(config);

        setTitle(SharedLocale.tr("options.title"));
        initComponents();
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(new Dimension(400, 500));
        setResizable(false);
        setLocationRelativeTo(owner);

        mapper.map(widthSpinner, "windowWidth");
        mapper.map(heightSpinner, "windowHeight");
        mapper.map(useProxyCheck, "proxyEnabled");
        mapper.map(proxyHostText, "proxyHost");
        mapper.map(proxyPortText, "proxyPort");
        mapper.map(proxyUsernameText, "proxyUsername");
        mapper.map(proxyPasswordText, "proxyPassword");
        mapper.map(gameKeyText, "gameKey");

        mapper.copyFromObject();
    }

    private void initComponents() {
        buildInstanceSettingsPanel();
        SwingHelper.removeOpaqueness(instanceSettingsPanel);
        tabbedPane.addTab(SharedLocale.tr("options.instancesTab"), new JScrollPane(instanceSettingsPanel));

        gameSettingsPanel.addRow(new JLabel(SharedLocale.tr("options.windowWidth")), widthSpinner);
        gameSettingsPanel.addRow(new JLabel(SharedLocale.tr("options.windowHeight")), heightSpinner);
        SwingHelper.removeOpaqueness(gameSettingsPanel);
        tabbedPane.addTab(SharedLocale.tr("options.minecraftTab"), SwingHelper.alignTabbedPane(gameSettingsPanel));

        proxySettingsPanel.addRow(useProxyCheck);
        proxySettingsPanel.addRow(new JLabel(SharedLocale.tr("options.proxyHost")), proxyHostText);
        proxySettingsPanel.addRow(new JLabel(SharedLocale.tr("options.proxyPort")), proxyPortText);
        proxySettingsPanel.addRow(new JLabel(SharedLocale.tr("options.proxyUsername")), proxyUsernameText);
        proxySettingsPanel.addRow(new JLabel(SharedLocale.tr("options.proxyPassword")), proxyPasswordText);
        SwingHelper.removeOpaqueness(proxySettingsPanel);
        tabbedPane.addTab(SharedLocale.tr("options.proxyTab"), SwingHelper.alignTabbedPane(proxySettingsPanel));

        advancedPanel.addRow(new JLabel(SharedLocale.tr("options.gameKey")), gameKeyText);
        SwingHelper.removeOpaqueness(advancedPanel);
        tabbedPane.addTab(SharedLocale.tr("options.advancedTab"), SwingHelper.alignTabbedPane(advancedPanel));

        buttonsPanel.addElement(logButton);
        buttonsPanel.addElement(aboutButton);
        buttonsPanel.addGlue();
        buttonsPanel.addElement(okButton);
        buttonsPanel.addElement(cancelButton);

        tabContainer.add(tabbedPane, BorderLayout.CENTER);
        tabContainer.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        add(tabContainer, BorderLayout.CENTER);
        add(buttonsPanel, BorderLayout.SOUTH);

        SwingHelper.equalWidth(okButton, cancelButton);

        cancelButton.addActionListener(ActionListeners.dispose(this));

        aboutButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                AboutDialog.showAboutDialog(ConfigurationDialog.this);
            }
        });

        okButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                save();
            }
        });

        logButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                ConsoleFrame.showMessages();
            }
        });

    }

    private void buildInstanceSettingsPanel() {
        JLabel titleLabel = new JLabel(SharedLocale.tr("options.instancesTitle"));
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD));
        instanceSettingsPanel.add(titleLabel, "growx");

        JTextArea explanation = new JTextArea(SharedLocale.tr("options.instancesDescription"));
        explanation.setEditable(false);
        explanation.setFocusable(false);
        explanation.setLineWrap(true);
        explanation.setWrapStyleWord(true);
        explanation.setOpaque(false);
        explanation.setFont(UIManager.getFont("Label.font"));
        explanation.setForeground(UIManager.getColor("Label.foreground"));
        explanation.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
        instanceSettingsPanel.add(explanation, "growx");

        if (launcher.getInstances().size() == 0) {
            instanceSettingsPanel.add(new JLabel(SharedLocale.tr("options.noInstances")), "growx");
            return;
        }

        for (int i = 0; i < launcher.getInstances().size(); i++) {
            Instance instance = launcher.getInstances().get(i);
            JButton settingsButton = new JButton(SharedLocale.tr("options.instanceJavaSettings"));
            settingsButton.addActionListener(e -> {
                dispose();
                InstanceSettingsDialog.open(getOwner(), launcher, instance);
            });

            instanceSettingsPanel.add(createInstanceSettingsRow(instance, settingsButton), "growx");
        }
    }

    private JPanel createInstanceSettingsRow(Instance instance, JButton settingsButton) {
        JPanel row = new JPanel(new MigLayout("fillx, ins 8 0 8 0", "[grow]push[]", "[][]"));
        row.setOpaque(false);
        Color separatorColor = UIManager.getColor("Separator.foreground");
        if (separatorColor == null) {
            separatorColor = UIManager.getColor("Panel.background");
        }
        if (separatorColor == null) {
            separatorColor = Color.LIGHT_GRAY;
        } else {
            separatorColor = separatorColor.darker();
        }
        row.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, separatorColor));

        JLabel titleLabel = new JLabel(instance.getTitle());
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD));
        row.add(titleLabel, "growx");
        row.add(settingsButton, "spany 2, aligny center, wrap");

        JLabel descriptionLabel = new JLabel(getInstanceSettingsDescription(instance));
        descriptionLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        row.add(descriptionLabel, "growx");

        return row;
    }

    private String getInstanceSettingsDescription(Instance instance) {
        if (!instance.isLocal()) {
            return SharedLocale.tr("options.instanceNotInstalled");
        }

        if (instance.isUpdatePending()) {
            return SharedLocale.tr("options.instanceUpdatePending");
        }

        return SharedLocale.tr("options.instanceInstalled");
    }

    /**
     * Save the configuration and close the dialog.
     */
    public void save() {
        mapper.copyFromSwing();
        gameKeyChanged = !originalGameKey.equals(Strings.nullToEmpty(config.getGameKey()));

        Persistence.commitAndForget(config);
        dispose();
    }

    /**
     * Whether the game key was changed when the dialog was last saved.
     *
     * @return true if the game key changed on save
     */
    public boolean isGameKeyChanged() {
        return gameKeyChanged;
    }
}
