/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.dialog;

import com.skcraft.concurrency.ObservableFuture;
import com.skcraft.launcher.Instance;
import com.skcraft.launcher.InstanceList;
import com.skcraft.launcher.Launcher;
import com.skcraft.launcher.browser.WebpagePanel;
import com.skcraft.launcher.launch.LaunchListener;
import com.skcraft.launcher.launch.LaunchOptions;
import com.skcraft.launcher.launch.LaunchOptions.UpdatePolicy;
import com.skcraft.launcher.model.modpack.Manifest;
import com.skcraft.launcher.persistence.Persistence;
import com.skcraft.launcher.swing.*;
import com.skcraft.launcher.util.SharedLocale;
import com.skcraft.launcher.util.SwingExecutor;
import lombok.Getter;
import lombok.NonNull;
import lombok.extern.java.Log;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.lang.ref.WeakReference;
import java.net.URL;

import static com.skcraft.launcher.util.SharedLocale.tr;

/**
 * The main launcher frame.
 */
@Log
public class LauncherFrame extends JFrame {

    private final Launcher launcher;

    private static final String INSTANCES_LIST_CARD = "list";
    private static final String INSTANCES_EMPTY_CARD = "empty";

    @Getter
    private final InstanceTable instancesTable = new InstanceTable();
    private final InstanceTableModel instancesModel;
    @Getter
    private final JScrollPane instanceScroll = new JScrollPane(instancesTable);
    private final JPanel instancesPanel = new JPanel(new CardLayout());
    private WebpagePanel webView;
    private URL lastLoggedNewsUrl;
    private final JButton launchButton = new JButton(SharedLocale.tr("launcher.launch"));
    private final JButton refreshButton = new JButton(SharedLocale.tr("launcher.checkForUpdates"));
    private final JButton optionsButton = new JButton(SharedLocale.tr("launcher.options"));
    private final JButton selfUpdateButton = new JButton(SharedLocale.tr("launcher.updateLauncher"));
    private final JCheckBox updateCheck = new JCheckBox(SharedLocale.tr("launcher.downloadUpdates"));
    private boolean initialInstanceLoad = true;
    private boolean instancesLoaded = false;

    /**
     * Create a new frame.
     *
     * @param launcher the launcher
     */
    public LauncherFrame(@NonNull Launcher launcher) {
        super(tr("launcher.title", launcher.getVersion()));

        this.launcher = launcher;
        instancesModel = new InstanceTableModel(launcher);

        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(520, 300));
        initComponents();
        pack();
        setLocationRelativeTo(null);

        SwingHelper.setFrameIcon(this, Launcher.class, "icon.png");

        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                loadInstances();
            }
        });
    }

    private void initComponents() {
        JPanel container = createContainerPanel();
        container.setLayout(new MigLayout("fill, insets 0 11 11 11", "[grow]", "[grow][]"));

        webView = createNewsPanel();
        instanceScroll.setBorder(BorderFactory.createEmptyBorder());
        instanceScroll.setViewportBorder(null);
        instancesPanel.setBorder(null);
        instancesPanel.setOpaque(false);
        instancesPanel.add(instanceScroll, INSTANCES_LIST_CARD);
        instancesPanel.add(createNoInstancesPanel(), INSTANCES_EMPTY_CARD);
        // Border lives on the outer panel; keep the SWT view borderless.
        webView.setBrowserBorder(BorderFactory.createEmptyBorder());

        JPanel leftColumn = new TableChromePanel(new MigLayout("ins 0, fill, gap 0", "[grow, fill]", "[grow, fill]"));
        leftColumn.setBorder(SwingHelper.uiLineBorder());
        leftColumn.add(instancesPanel, "grow");

        JPanel newsPanel = new TableChromePanel(new BorderLayout());
        newsPanel.setBorder(SwingHelper.uiLineBorder());
        newsPanel.add(webView, BorderLayout.CENTER);

        JPanel contentPanel = new JPanel(new MigLayout("ins 0, fill", "[200!][grow, fill]", "[grow, fill]"));
        contentPanel.setPreferredSize(new Dimension(680, 350));
        contentPanel.add(leftColumn, "grow");
        contentPanel.add(newsPanel, "grow");
        selfUpdateButton.setVisible(launcher.getUpdateManager().getPendingUpdate());

        launcher.getUpdateManager().addPropertyChangeListener(new PropertyChangeListener() {
            @Override
            public void propertyChange(PropertyChangeEvent evt) {
                if (evt.getPropertyName().equals("pendingUpdate")) {
                    selfUpdateButton.setVisible((Boolean) evt.getNewValue());

                }
            }
        });

        updateCheck.setSelected(true);
        instancesTable.setModel(instancesModel);
        instancesModel.addTableModelListener(e -> updateInstancesPlaceholder());
        launchButton.setFont(launchButton.getFont().deriveFont(Font.BOLD));
        launchButton.putClientProperty("FlatLaf.styleClass", "primary");
        bindEnterToLaunch();
        container.add(contentPanel, "grow, wrap, gapbottom unrel");

        JPanel bottomBar = new JPanel(new BorderLayout());
        bottomBar.setOpaque(false);

        JPanel bottomLeft = new JPanel(new MigLayout("ins 0", "[][]", "[]"));
        bottomLeft.setOpaque(false);
        bottomLeft.add(refreshButton);
        bottomLeft.add(updateCheck, "grow 0");

        JPanel bottomRight = new JPanel(new MigLayout("ins 0, hidemode 3", "[][][]", "[]"));
        bottomRight.setOpaque(false);
        bottomRight.add(selfUpdateButton);
        bottomRight.add(optionsButton);
        bottomRight.add(launchButton);

        bottomBar.add(bottomLeft, BorderLayout.WEST);
        bottomBar.add(bottomRight, BorderLayout.EAST);
        container.add(bottomBar, "growx");
        getRootPane().setDefaultButton(launchButton);

        add(container, BorderLayout.CENTER);

        instancesTable.getSelectionModel().addListSelectionListener(new ListSelectionListener() {
            @Override
            public void valueChanged(ListSelectionEvent e) {
                if (!e.getValueIsAdjusting() && instancesTable.getSelectedRow() >= 0) {
                    updateNewsPanel();
                }
            }
        });

        instancesTable.addMouseListener(new DoubleClickToButtonAdapter(launchButton));

        refreshButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                loadInstances();
                launcher.getUpdateManager().checkForUpdate(LauncherFrame.this);
            }
        });

        selfUpdateButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                launcher.getUpdateManager().performUpdate(LauncherFrame.this);
            }
        });


        optionsButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showOptions();
            }
        });

        launchButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                launch();
            }
        });

        instancesTable.addMouseListener(new PopupMouseAdapter() {
            @Override
            protected void showPopup(MouseEvent e) {
                int index = instancesTable.rowAtPoint(e.getPoint());
                Instance selected = null;
                if (index >= 0) {
                    instancesTable.setRowSelectionInterval(index, index);
                    selected = launcher.getInstances().get(index);
                }
                popupInstanceMenu(e.getComponent(), e.getX(), e.getY(), selected);
            }
        });
    }

    protected JPanel createContainerPanel() {
        return new JPanel();
    }

    /**
     * Return the news panel.
     *
     * @return the news panel
     */
    protected WebpagePanel createNewsPanel() {
        try {
            return new WebpagePanel();
        } catch (LinkageError e) {
            log.warning("SWT Browser is unavailable; embedded news panel disabled");
            return WebpagePanel.missingBrowser();
        } catch (RuntimeException e) {
            log.warning("SWT Browser failed to initialize; embedded news panel disabled");
            return WebpagePanel.missingBrowser();
        }
    }

    private JPanel createNoInstancesPanel() {
        final JLabel label = new JLabel(SharedLocale.tr("launcher.noInstances"));
        label.setHorizontalAlignment(SwingConstants.CENTER);

        JPanel panel = new TableChromePanel(new GridBagLayout()) {
            @Override
            public void updateUI() {
                super.updateUI();
                if (label.getParent() == this) {
                    Color muted = UIManager.getColor("Label.disabledForeground");
                    if (muted != null) {
                        label.setForeground(muted);
                    }
                }
            }
        };
        panel.add(label);
        Color muted = UIManager.getColor("Label.disabledForeground");
        if (muted != null) {
            label.setForeground(muted);
        }
        return panel;
    }

    private void updateInstancesPlaceholder() {
        CardLayout layout = (CardLayout) instancesPanel.getLayout();
        boolean showEmpty = instancesLoaded && instancesModel.getRowCount() == 0;
        layout.show(instancesPanel, showEmpty ? INSTANCES_EMPTY_CARD : INSTANCES_LIST_CARD);
    }

    private void bindEnterToLaunch() {
        final String launchActionKey = "launchSelectedInstance";
        Action launchAction = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (launchButton.isEnabled()) {
                    launchButton.doClick();
                }
            }
        };

        instancesTable.getActionMap().put(launchActionKey, launchAction);
        KeyStroke enter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0);
        instancesTable.getInputMap(JComponent.WHEN_FOCUSED).put(enter, launchActionKey);
        instancesTable.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(enter, launchActionKey);
    }

    private void updateNewsPanel() {
        updateNewsPanel(false);
    }

    private void updateNewsPanel(boolean forceReload) {
        if (webView == null) {
            return;
        }

        Instance instance = getSelectedInstance();
        URL newsUrl = launcher.getNewsURL(instance);
        if (!newsUrl.equals(lastLoggedNewsUrl)) {
            log.info("Loading news from " + newsUrl);
            lastLoggedNewsUrl = newsUrl;
        }
        webView.browse(newsUrl, !forceReload);
    }

    private Instance getSelectedInstance() {
        int selectedRow = instancesTable.getSelectedRow();
        if (selectedRow < 0) {
            return null;
        }

        int modelRow = instancesTable.convertRowIndexToModel(selectedRow);
        if (modelRow < 0 || modelRow >= launcher.getInstances().size()) {
            return null;
        }

        return launcher.getInstances().get(modelRow);
    }

    private void restoreInstanceSelection(String selectedName) {
        if (selectedName != null) {
            for (int i = 0; i < launcher.getInstances().size(); i++) {
                if (launcher.getInstances().get(i).getName().equalsIgnoreCase(selectedName)) {
                    int viewRow = instancesTable.convertRowIndexToView(i);
                    if (viewRow >= 0) {
                        instancesTable.setRowSelectionInterval(viewRow, viewRow);
                        return;
                    }
                }
            }
        }
    }

    /**
     * Popup the menu for the instances.
     *
     * @param component the component
     * @param x         mouse X
     * @param y         mouse Y
     * @param selected  the selected instance, possibly null
     */
    private void popupInstanceMenu(Component component, int x, int y, final Instance selected) {
        if (selected == null) {
            return;
        }

        JPopupMenu popup = new JPopupMenu();
        JMenuItem menuItem;

        menuItem = new JMenuItem(!selected.isLocal() ? tr("instance.install") : tr("instance.launch"));
        menuItem.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                launch();
            }
        });
        popup.add(menuItem);

        if (selected.isLocal()) {
            popup.addSeparator();

            menuItem = new JMenuItem(SharedLocale.tr("instance.openFolder"));
            menuItem.addActionListener(ActionListeners.browseDir(
                    LauncherFrame.this, selected.getContentDir(), true));
            popup.add(menuItem);

            menuItem = new JMenuItem(SharedLocale.tr("instance.openSaves"));
            menuItem.addActionListener(ActionListeners.browseDir(
                    LauncherFrame.this, new File(selected.getContentDir(), "saves"), true));
            popup.add(menuItem);

            menuItem = new JMenuItem(SharedLocale.tr("instance.openResourcePacks"));
            menuItem.addActionListener(ActionListeners.browseDir(
                    LauncherFrame.this, new File(selected.getContentDir(), "resourcepacks"), true));
            popup.add(menuItem);

            menuItem = new JMenuItem(SharedLocale.tr("instance.openScreenshots"));
            menuItem.addActionListener(ActionListeners.browseDir(
                    LauncherFrame.this, new File(selected.getContentDir(), "screenshots"), true));
            popup.add(menuItem);

            menuItem = new JMenuItem(SharedLocale.tr("instance.copyAsPath"));
            menuItem.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    File dir = selected.getContentDir();
                    dir.mkdirs();
                    SwingHelper.setClipboard(dir.getAbsolutePath());
                }
            });
            popup.add(menuItem);

            menuItem = new JMenuItem(SharedLocale.tr("instance.openSettings"));
            menuItem.addActionListener(e -> {
                InstanceSettingsDialog.open(this, launcher, selected);
            });
            popup.add(menuItem);

            popup.addSeparator();

            if (instanceHasFeatures(selected)) {
                menuItem = new JMenuItem(SharedLocale.tr("instance.selectFeatures"));
                menuItem.addActionListener(new ActionListener() {
                    @Override
                    public void actionPerformed(ActionEvent e) {
                        selected.setUpdatePending(true);
                        launch(true, true);
                        instancesModel.update();
                    }
                });
                popup.add(menuItem);
            }

            menuItem = new JMenuItem(SharedLocale.tr("instance.verifyFiles"));
            menuItem.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    new File(selected.getDir(), "update_cache.json").delete();
                    selected.setUpdatePending(true);
                    launch(false, true);
                    instancesModel.update();
                }
            });
            popup.add(menuItem);

            menuItem = new JMenuItem(SharedLocale.tr("instance.reinstallMods"));
            menuItem.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    confirmHardUpdate(selected);
                }
            });
            popup.add(menuItem);

            menuItem = new JMenuItem(SharedLocale.tr("instance.deleteFiles"));
            menuItem.addActionListener(new ActionListener() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    confirmDelete(selected);
                }
            });
            popup.add(menuItem);
        }

        popup.show(component, x, y);

    }

    private void confirmDelete(Instance instance) {
        if (!SwingHelper.confirmDialog(this,
                tr("instance.confirmDelete", instance.getTitle()), SharedLocale.tr("confirmTitle"))) {
            return;
        }

        ObservableFuture<Instance> future = launcher.getInstanceTasks().delete(this, instance);

        // Update the list of instances after updating
        future.addListener(new Runnable() {
            @Override
            public void run() {
                loadInstances();
            }
        }, SwingExecutor.INSTANCE);
    }

    private void confirmHardUpdate(Instance instance) {
        if (!SwingHelper.confirmDialog(this, SharedLocale.tr("instance.confirmReinstallMods"),
                SharedLocale.tr("confirmTitle"))) {
            return;
        }

        ObservableFuture<Instance> future = launcher.getInstanceTasks().hardUpdate(this, instance);

        // Update the list of instances after updating
        future.addListener(new Runnable() {
            @Override
            public void run() {
                launch(false, true);
                instancesModel.update();
            }
        }, SwingExecutor.INSTANCE);
    }

    private boolean instanceHasFeatures(Instance instance) {
        File manifestPath = instance.getManifestPath();
        if (!manifestPath.isFile()) {
            return false;
        }

        Manifest manifest = Persistence.read(manifestPath, Manifest.class);
        return manifest.getFeatures() != null && !manifest.getFeatures().isEmpty();
    }

    private void loadInstances() {
        String selectedNameToRestore = null;
        Instance selected = getSelectedInstance();
        if (selected != null) {
            selectedNameToRestore = selected.getName();
        } else if (initialInstanceLoad) {
            selectedNameToRestore = launcher.getConfig().getLastInstance();
        }
        final String selectedName = selectedNameToRestore;

        ObservableFuture<InstanceList> future = launcher.getInstanceTasks().reloadInstances(this);

        future.addListener(new Runnable() {
            @Override
            public void run() {
                instancesLoaded = true;
                instancesModel.update();
                instancesTable.clearSelection();
                restoreInstanceSelection(selectedName);
                initialInstanceLoad = false;
                updateNewsPanel(true);
                requestFocus();
            }
        }, SwingExecutor.INSTANCE);
    }

    private void showOptions() {
        ConfigurationDialog configDialog = new ConfigurationDialog(this, launcher);
        configDialog.setVisible(true);
        if (configDialog.isGameKeyChanged()) {
            loadInstances();
        }
    }

    /**
     * Dispose the frame, tearing down the embedded browser first so the native
     * engine is released no matter how the window is closed.
     */
    @Override
    public void dispose() {
        if (webView != null) {
            webView.disposeBrowser();
            webView = null;
        }
        super.dispose();
    }

    private void launch() {
        launch(false, updateCheck.isSelected());
    }

    private void launch(boolean reselectFeatures, boolean permitUpdate) {
        Instance instance = getSelectedInstance();
        if (instance == null) {
            SwingHelper.showErrorDialog(this, SharedLocale.tr("launcher.noInstanceError"),
                    SharedLocale.tr("launcher.noInstanceTitle"));
            return;
        }

        LaunchOptions options = new LaunchOptions.Builder()
                .setInstance(instance)
                .setListener(new LaunchListenerImpl(this))
                .setUpdatePolicy(permitUpdate ? UpdatePolicy.ALWAYS_UPDATE : UpdatePolicy.NO_UPDATE)
                .setReselectFeatures(reselectFeatures)
                .setWindow(this)
                .build();
        launcher.getLaunchSupervisor().launch(options);
    }

    private static class LaunchListenerImpl implements LaunchListener {
        private final WeakReference<LauncherFrame> frameRef;
        private final Launcher launcher;

        private LaunchListenerImpl(LauncherFrame frame) {
            this.frameRef = new WeakReference<LauncherFrame>(frame);
            this.launcher = frame.launcher;
        }

        @Override
        public void instancesUpdated() {
            LauncherFrame frame = frameRef.get();
            if (frame != null) {
                frame.instancesModel.update();
            }
        }

        @Override
        public void gameStarted() {
            LauncherFrame frame = frameRef.get();
            if (frame != null) {
                frame.dispose();
            }
        }

        @Override
        public void gameClosed() {
            Window newLauncherWindow = launcher.showLauncherWindow();
            launcher.getUpdateManager().checkForUpdate(newLauncherWindow);
        }
    }

}
