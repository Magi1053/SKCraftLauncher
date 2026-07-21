/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.dialog;

import com.skcraft.launcher.swing.LinedBoxPanel;
import com.skcraft.launcher.swing.MessageLog;
import com.skcraft.launcher.swing.SwingHelper;
import com.skcraft.launcher.util.SharedLocale;
import lombok.Getter;
import lombok.Setter;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.PrintWriter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.skcraft.launcher.util.SharedLocale.tr;

/**
 * A version of the console window that can manage a process.
 */
public class ProcessConsoleFrame extends ConsoleFrame {

    private static final ConcurrentHashMap<String, ProcessConsoleFrame> BY_INSTANCE =
            new ConcurrentHashMap<String, ProcessConsoleFrame>();

    private JButton killButton;
    private JButton minimizeButton;
    private TrayIcon trayIcon;

    @Getter private Process process;
    @Getter @Setter private boolean killOnClose;

    private PrintWriter processOut;
    private String instanceKey;
    private final AtomicBoolean killedByUser = new AtomicBoolean();

    /**
     * Create a new instance of the frame.
     *
     * @param numLines the number of log lines
     * @param colorEnabled whether color is enabled in the log
     */
    public ProcessConsoleFrame(int numLines, boolean colorEnabled) {
        this(numLines, colorEnabled, true);
    }

    /**
     * Create a new instance of the frame.
     *
     * @param numLines the number of log lines
     * @param colorEnabled whether color is enabled in the log
     * @param showUi whether to attach a tray icon and allow showing the window
     */
    public ProcessConsoleFrame(int numLines, boolean colorEnabled, boolean showUi) {
        this(new MessageLog(numLines, colorEnabled, true), showUi);
    }

    private ProcessConsoleFrame(MessageLog messageLog, boolean showUi) {
        super(SharedLocale.tr("console.title"), messageLog);
        processOut = new PrintWriter(
                getMessageLog().getOutputStream(new Color(0, 0, 255)), true);
        initComponents(showUi);
        updateComponents();
    }

    public static ProcessConsoleFrame createGameConsole(int numLines) {
        return createGameConsole(numLines, true);
    }

    public static ProcessConsoleFrame createGameConsole(int numLines, boolean showUi) {
        return new ProcessConsoleFrame(MessageLog.createGameLog(numLines), showUi);
    }

    /**
     * Close any existing process console for the given instance key.
     */
    public static void closeExisting(String instanceKey) {
        if (instanceKey == null) {
            return;
        }

        ProcessConsoleFrame existing = BY_INSTANCE.get(instanceKey);
        if (existing != null && existing.hasLiveProcess()) {
            return;
        }
        if (existing != null && BY_INSTANCE.remove(instanceKey, existing)) {
            existing.performClose();
        }
    }

    /**
     * Associate this console with an instance so a later launch can replace it.
     */
    public void registerForInstance(String instanceKey) {
        this.instanceKey = instanceKey;
        if (instanceKey != null) {
            BY_INSTANCE.put(instanceKey, this);
        }
    }

    /**
     * Track the given process.
     *
     * @param process the process
     */
    public synchronized void setProcess(Process process) {
        try {
            Process lastProcess = this.process;
            if (lastProcess != null) {
                processOut.println(tr("console.processEndCode", lastProcess.exitValue()));
            }
        } catch (IllegalThreadStateException e) {
        }

        if (process != null) {
            processOut.println(SharedLocale.tr("console.attachedToProcess"));
        }

        this.process = process;

        SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                updateComponents();
            }
        });
    }

    private synchronized boolean hasProcess() {
        return process != null;
    }

    private synchronized boolean hasLiveProcess() {
        return process != null && process.isAlive();
    }

    public void processEnded(boolean keepOpen) {
        setProcess(null);
        if (keepOpen) {
            requestFocus();
        } else {
            performClose();
        }
    }

    /**
     * Whether Force Close (or tray equivalent) terminated the process.
     */
    public boolean wasKilledByUser() {
        return killedByUser.get();
    }

    @Override
    protected void performClose() {
        if (instanceKey != null) {
            BY_INSTANCE.remove(instanceKey, this);
        }

        if (hasProcess()) {
            if (killOnClose) {
                performKill();
            }
        }

        if (trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
        }

        super.performClose();
    }

    private void performKill() {
        if (!confirmKill()) {
            return;
        }

        synchronized (this) {
            if (hasProcess()) {
                killedByUser.set(true);
                process.destroy();
                setProcess(null);
            }
        }

        updateComponents();
    }

    protected void initComponents(boolean showUi) {
        killButton = new JButton(SharedLocale.tr("console.forceClose"));
        minimizeButton = new JButton(SharedLocale.tr("console.closeWindow"));

        LinedBoxPanel buttonsPanel = getButtonsPanel();
        buttonsPanel.addGlue();
        buttonsPanel.addElement(killButton);
        buttonsPanel.addElement(minimizeButton);
        alignActionButtons();

        killButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                performKill();
            }
        });

        minimizeButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                contextualClose();
            }
        });

        if (showUi) {
            if (!setupTrayIcon()) {
                minimizeButton.setEnabled(true);
            }
        } else {
            minimizeButton.setEnabled(true);
        }
    }

    private boolean setupTrayIcon() {
        if (!SystemTray.isSupported()) {
            return false;
        }

        trayIcon = new TrayIcon(getTrayRunningIcon());
        trayIcon.setImageAutoSize(true);
        trayIcon.setToolTip(SharedLocale.tr("console.trayTooltip"));

        trayIcon.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                reshow();
            }
        });

        PopupMenu popup = new PopupMenu();
        MenuItem item;

        popup.add(item = new MenuItem(SharedLocale.tr("console.trayTitle")));
        item.setEnabled(false);

        popup.add(item = new MenuItem(SharedLocale.tr("console.tray.showWindow")));
        item.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                reshow();
            }
        });

        popup.add(item = new MenuItem(SharedLocale.tr("console.tray.forceClose")));
        item.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                performKill();
            }
        });

        trayIcon.setPopupMenu(popup);

        try {
            SystemTray tray = SystemTray.getSystemTray();
            tray.add(trayIcon);
            return true;
        } catch (AWTException e) {
        }

        return false;
    }

    private void alignActionButtons() {
        SwingHelper.alignButtonSizes(killButton, minimizeButton);
    }

    private synchronized void updateComponents() {
        Image icon = hasProcess() ? getTrayRunningIcon() : getTrayClosedIcon();

        killButton.setEnabled(hasProcess());

        if (!hasProcess() || trayIcon == null) {
            minimizeButton.setText(SharedLocale.tr("console.closeWindow"));
        } else {
            minimizeButton.setText(SharedLocale.tr("console.hideWindow"));
        }
        alignActionButtons();

        if (trayIcon != null) {
            trayIcon.setImage(icon);
        }

        setIconImage(icon);
    }

    private synchronized void contextualClose() {
        if (!hasProcess() || trayIcon == null) {
            performClose();
        } else {
            minimize();
        }

        updateComponents();
    }

    private boolean confirmKill() {
        if (System.getProperty("skcraftLauncher.killWithoutConfirm", "false").equalsIgnoreCase("true")) {
            return true;
        } else {
            return SwingHelper.confirmDialog(this,  SharedLocale.tr("console.confirmKill"), SharedLocale.tr("console.confirmKillTitle"));
        }
    }

    private void minimize() {
        setVisible(false);
    }

    private void reshow() {
        setVisible(true);
        requestFocus();
    }

}
