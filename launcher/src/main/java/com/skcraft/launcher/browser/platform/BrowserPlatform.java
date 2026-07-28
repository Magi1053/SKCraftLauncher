/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.browser.platform;

import ca.weblite.webview.swing.WebViewComponent;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.skcraft.concurrency.ProgressObservable;
import com.skcraft.launcher.dialog.ProgressDialog;
import com.skcraft.launcher.swing.SwingHelper;
import com.skcraft.launcher.util.HttpRequest;
import com.skcraft.launcher.util.SharedLocale;
import lombok.extern.java.Log;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/**
 * Host-specific browser setup and native runtime selection.
 */
@Log
public abstract class BrowserPlatform {

    private static final BrowserPlatform CURRENT = selectCurrent();
    @SuppressWarnings("null")
    private static final ListeningExecutorService DEPENDENCY_INSTALLER = MoreExecutors
            .listeningDecorator(Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "browser-dependency-installer");
                thread.setDaemon(true);
                return thread;
            }));

    private final String architectureSuffix;

    protected BrowserPlatform(String osArch) {
        String normalizedArch = osArch.toLowerCase(Locale.ROOT);
        architectureSuffix = normalizedArch.contains("aarch64") || normalizedArch.contains("arm64")
                ? "arm64"
                : "64";
    }

    public static BrowserPlatform current() {
        return CURRENT;
    }

    public void configureEnvironment(Path baseDir) throws IOException {
    }

    public void configureSwing() {
        JPopupMenu.setDefaultLightWeightPopupEnabled(false);
        ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false);
    }

    public void installBoundsFix(WebViewComponent component) {
    }

    public void stopBoundsFix(WebViewComponent component) {
    }

    public void setContentVisible(WebViewComponent component, boolean visible) {
    }

    /**
     * Open a URL in the host browser.
     */
    public void openExternalUrl(URI url) throws IOException {
        Desktop.getDesktop().browse(url);
    }

    /**
     * Update {@code prefers-color-scheme} on a live browser without navigating.
     *
     * @return true if the live update was applied
     */
    public boolean applyPreferredColorScheme(WebViewComponent component, boolean darkTheme) {
        return false;
    }

    /**
     * Whether the host web engine required by weblite is present.
     * Platforms that soft-fail without a runtime should override this for fail-fast
     * UX.
     */
    public boolean isHostRuntimeAvailable() {
        return true;
    }

    /**
     * Whether this platform can install a missing host browser dependency.
     */
    public boolean canInstallDependency() {
        return false;
    }

    /**
     * Install this platform's missing browser dependency.
     *
     * @param parent component used to own dialogs
     */
    public final void installDependency(Component parent) {
        if (!canInstallDependency()) {
            return;
        }

        // Prefer the owning window so confirm/progress/success dialogs center on
        // the launcher frame, not the news panel (or other nested component).
        Window owner = parent instanceof Window
                ? (Window) parent
                : SwingUtilities.getWindowAncestor(parent);
        Component dialogParent = owner != null ? owner : parent;

        DependencyInstallTask task;
        try {
            task = createDependencyInstallTask();
        } catch (IOException e) {
            showDependencyInstallFailure(dialogParent, e);
            return;
        }

        if (!SwingHelper.confirmDialog(dialogParent, dependencyInstallConfirmMessage(),
                SharedLocale.tr("news.panel.install.confirmTitle"))) {
            return;
        }

        ListenableFuture<Void> future = DEPENDENCY_INSTALLER.submit(task);
        ProgressDialog.showProgress(owner, future, task,
                SharedLocale.tr("news.panel.install.progressTitle"),
                SharedLocale.tr("news.panel.install.starting"));

        try {
            future.get();
            showDependencyInstallSuccess(dialogParent);
        } catch (CancellationException e) {
            // The user cancelled the progress dialog.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof CancellationException) {
                // Password prompt or progress dialog cancelled.
                return;
            }
            showDependencyInstallFailure(dialogParent, cause);
        }
    }

    /**
     * Confirmation message shown before installing the host browser dependency.
     */
    public String dependencyInstallConfirmMessage() {
        throw new UnsupportedOperationException("Browser dependency install is unavailable");
    }

    /**
     * Create a background task that installs the host browser dependency.
     */
    public DependencyInstallTask createDependencyInstallTask() throws IOException {
        throw new UnsupportedOperationException("Browser dependency install is unavailable");
    }

    public abstract String runtimePlatformKey() throws IOException;

    protected final String runtimePlatformKey(String osPrefix) {
        return osPrefix + "_" + architectureSuffix;
    }

    /**
     * Background install work with status suitable for {@code ProgressDialog}.
     */
    public abstract static class DependencyInstallTask
            implements Callable<Void>, ProgressObservable {

        private static final int MAX_DETAIL_LOG_CHARS = 64 * 1024;

        private volatile String status = SharedLocale.tr("news.panel.install.starting");
        private volatile HttpRequest download;
        private final StringBuilder detailLog = new StringBuilder();

        protected final void setStatus(String status) {
            this.status = status;
        }

        protected final void setDownload(HttpRequest download) {
            this.download = download;
        }

        /**
         * Append a line to the ProgressDialog details area (below the status headline).
         */
        protected final void appendDetailLog(String line) {
            if (line == null) {
                return;
            }
            synchronized (detailLog) {
                if (detailLog.length() > 0) {
                    detailLog.append('\n');
                }
                detailLog.append(line);
                if (detailLog.length() > MAX_DETAIL_LOG_CHARS) {
                    detailLog.delete(0, detailLog.length() - MAX_DETAIL_LOG_CHARS);
                }
            }
        }

        protected final String detailLog() {
            synchronized (detailLog) {
                return detailLog.toString();
            }
        }

        @Override
        public double getProgress() {
            HttpRequest request = download;
            return request != null ? request.getProgress() : -1;
        }

        @Override
        public String getStatus() {
            String detail;
            synchronized (detailLog) {
                detail = detailLog.length() == 0 ? null : detailLog.toString();
            }
            return detail == null ? status : status + "\n" + detail;
        }
    }

    private static void showDependencyInstallSuccess(Component parent) {
        String restartNow = SharedLocale.tr("news.panel.install.restartNow");
        String restartLater = SharedLocale.tr("news.panel.install.restartLater");
        Object[] options = { restartNow, restartLater };
        int picked = JOptionPane.showOptionDialog(
                parent,
                SwingHelper.htmlWrap(SharedLocale.tr("news.panel.install.success")),
                SharedLocale.tr("news.panel.install.successTitle"),
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.INFORMATION_MESSAGE,
                null,
                options,
                restartNow);
        if (picked == 0) {
            relaunchCurrentProcess(parent);
        }
    }

    private static void relaunchCurrentProcess(Component parent) {
        ProcessHandle.Info info = ProcessHandle.current().info();
        Optional<String> command = info.command();
        if (!command.isPresent()) {
            SwingHelper.showErrorDialog(parent,
                    SharedLocale.tr("news.panel.install.restartFailed"),
                    SharedLocale.tr("errorTitle"));
            return;
        }

        ArrayList<String> commandLine = new ArrayList<>();
        commandLine.add(command.get());
        info.arguments().ifPresent(args -> Collections.addAll(commandLine, args));

        try {
            new ProcessBuilder(commandLine).inheritIO().start();
            System.exit(0);
        } catch (IOException e) {
            log.log(Level.WARNING, "Unable to relaunch launcher after browser dependency install", e);
            SwingHelper.showErrorDialog(parent,
                    SharedLocale.tr("news.panel.install.restartFailed"),
                    SharedLocale.tr("errorTitle"),
                    e);
        }
    }

    private static void showDependencyInstallFailure(Component parent, Throwable failure) {
        log.log(Level.WARNING, "Browser dependency installation failed", failure);
        String details = failure.getLocalizedMessage();
        if (details == null || details.trim().isEmpty()) {
            details = failure.toString();
        }
        SwingHelper.showErrorDialog(parent,
                SharedLocale.tr("news.panel.install.failed"),
                SharedLocale.tr("errorTitle"),
                new IOException(details, failure));
    }

    private static BrowserPlatform selectCurrent() {
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String osArch = System.getProperty("os.arch", "");
        if (osName.contains("win")) {
            return new WindowsBrowserPlatform(osArch);
        }
        if (osName.contains("mac") || osName.contains("darwin")) {
            return new MacBrowserPlatform(osArch);
        }
        if (osName.contains("linux")) {
            return new LinuxBrowserPlatform(osArch);
        }
        return new UnsupportedBrowserPlatform(osName, osArch);
    }
}
