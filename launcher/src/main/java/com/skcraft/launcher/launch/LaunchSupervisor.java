/*
 * SKCraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.launch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.skcraft.concurrency.ObservableFuture;
import com.skcraft.concurrency.ProgressObservable;
import com.skcraft.launcher.Instance;
import com.skcraft.launcher.Launcher;
import com.skcraft.launcher.auth.Session;
import com.skcraft.launcher.dialog.AccountSelectDialog;
import com.skcraft.launcher.dialog.ProcessConsoleFrame;
import com.skcraft.launcher.dialog.ProgressDialog;
import com.skcraft.launcher.launch.LaunchOptions.UpdatePolicy;
import com.skcraft.launcher.launch.runtime.JavaRuntime;
import com.skcraft.launcher.model.minecraft.JavaVersion;
import com.skcraft.launcher.model.minecraft.VersionManifest;
import com.skcraft.launcher.persistence.Persistence;
import com.skcraft.launcher.swing.SwingHelper;
import com.skcraft.launcher.update.Updater;
import com.skcraft.launcher.update.runtime.JavaVersionResolver;
import com.skcraft.launcher.update.runtime.RuntimeInstallTask;
import com.skcraft.launcher.util.SharedLocale;
import com.skcraft.launcher.util.SwingExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.apache.commons.io.FileUtils;

import javax.annotation.Nullable;
import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.logging.Level;

import static com.google.common.util.concurrent.MoreExecutors.sameThreadExecutor;
import static com.skcraft.launcher.util.SharedLocale.tr;

@Log
public class LaunchSupervisor {

    private final Launcher launcher;
    private final ObjectMapper mapper = new ObjectMapper();

    public LaunchSupervisor(Launcher launcher) {
        this.launcher = launcher;
    }

    public void launch(LaunchOptions options) {
        final Window window = options.getWindow();
        final Instance instance = options.getInstance();
        final LaunchListener listener = options.getListener();

        try {
            boolean update = options.getUpdatePolicy().isUpdateEnabled() && instance.isUpdatePending();

            // Store last access date
            Date now = new Date();
            instance.setLastAccessed(now);
            Persistence.commitAndForget(instance);
            launcher.getConfig().setLastInstance(instance.getName());
            Persistence.commitAndForget(launcher.getConfig());

            // Perform login
            final Session session;
            if (options.getSession() != null) {
                session = options.getSession();
            } else {
                session = AccountSelectDialog.showAccountRequest(window, launcher);
                if (session == null) {
                    return;
                }
            }

            // If we have to update, we have to update
            if (!instance.isInstalled()) {
                update = true;
            }

            if (update) {
                // Execute the updater
                Updater updater = new Updater(launcher, instance);
                updater.setOnline(options.getUpdatePolicy() == UpdatePolicy.ALWAYS_UPDATE || session.isOnline());
                ObservableFuture<Instance> future = new ObservableFuture<Instance>(
                        launcher.getExecutor().submit(updater), updater);

                // Show progress
                ProgressDialog.showProgress(window, future, SharedLocale.tr("launcher.updatingTitle"), tr("launcher.updatingStatus", instance.getTitle()));
                SwingHelper.addErrorDialogCallback(window, future);

                // Update the list of instances after updating
                future.addListener(new Runnable() {
                    @Override
                    public void run() {
                        SwingUtilities.invokeLater(new Runnable() {
                            @Override
                            public void run() {
                                listener.instancesUpdated();
                            }
                        });
                    }
                }, SwingExecutor.INSTANCE);

                // On success, launch also
                Futures.addCallback(future, new FutureCallback<Instance>() {
                    @Override
                    public void onSuccess(Instance result) {
                        launchInstance(window, instance, session, listener, true);
                    }

                    @Override
                    public void onFailure(Throwable t) {
                    }
                }, SwingExecutor.INSTANCE);
            } else {
                boolean runtimeDownloadAllowed = options.getUpdatePolicy() == UpdatePolicy.ALWAYS_UPDATE
                        || (options.getUpdatePolicy().isUpdateEnabled() && session.isOnline());
                launchInstance(window, instance, session, listener, runtimeDownloadAllowed);
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            SwingHelper.showErrorDialog(window, SharedLocale.tr("launcher.noInstanceError"), SharedLocale.tr("launcher.noInstanceTitle"));
        }
    }

    private void launchInstance(Window window, Instance instance, Session session,
                                LaunchListener listener, boolean runtimeDownloadAllowed) {
        if (instance.getSettings().getRuntime() != null) {
            launch(window, instance, session, listener);
            return;
        }

        JavaVersion javaVersion = null;
        if (instance.getSettings().getManagedRuntimeComponent() != null) {
            javaVersion = new JavaVersion();
            javaVersion.setComponent(instance.getSettings().getManagedRuntimeComponent());
        }

        if (javaVersion != null || instance.getSettings().usesAutomaticRuntime()) {
            prepareRuntimeAndLaunch(window, instance, session, listener, runtimeDownloadAllowed, javaVersion);
        } else {
            launch(window, instance, session, listener);
        }
    }

    private void prepareRuntimeAndLaunch(Window window, Instance instance, Session session,
                                         LaunchListener listener, boolean runtimeDownloadAllowed,
                                         @Nullable JavaVersion javaVersion) {
        JavaVersion resolvedJavaVersion = javaVersion != null
                ? javaVersion
                : readRequiredJavaVersion(instance);

        if (!runtimeDownloadAllowed) {
            if (launcher.getRuntimeManager().getRuntime(resolvedJavaVersion).isPresent()) {
                launch(window, instance, session, listener);
            } else {
                SwingHelper.showErrorDialog(window,
                        tr("runtime.missingDisabled", instance.getTitle(), resolvedJavaVersion.getComponent()),
                        tr("runtime.missingTitle"));
            }
            return;
        }

        RuntimeInstallTask task = new RuntimeInstallTask(launcher, resolvedJavaVersion);
        ObservableFuture<JavaRuntime> future = new ObservableFuture<JavaRuntime>(
                launcher.getExecutor().submit(task), task);

        ProgressDialog.showProgress(
                window, future, tr("runtime.installingTitle"), tr("runtime.installingStatus", instance.getTitle()));
        SwingHelper.addErrorDialogCallback(window, future);

        Futures.addCallback(future, new FutureCallback<JavaRuntime>() {
            @Override
            public void onSuccess(JavaRuntime result) {
                launch(window, instance, session, listener);
            }

            @Override
            public void onFailure(Throwable t) {
            }
        }, SwingExecutor.INSTANCE);
    }

    private JavaVersion readRequiredJavaVersion(Instance instance) {
        if (!instance.getVersionPath().isFile()) {
            return JavaVersionResolver.legacyDefault();
        }

        try {
            VersionManifest version = mapper.readValue(instance.getVersionPath(), VersionManifest.class);
            JavaVersion javaVersion = JavaVersionResolver.resolve(launcher, instance, version);
            if (version.getJavaVersion() == null) {
                version.setJavaVersion(javaVersion);
                mapper.writeValue(instance.getVersionPath(), version);
            }
            return javaVersion;
        } catch (IOException e) {
            log.log(Level.WARNING, "Failed to read Java version from " + instance.getVersionPath(), e);
            return JavaVersionResolver.legacyDefault();
        }
    }

    private void launch(Window window, Instance instance, Session session, final LaunchListener listener) {
        final File extractDir = launcher.createExtractDir();

        Runner task = new Runner(launcher, instance, session, extractDir, new MemoryVerifier(instance));
        ObservableFuture<Process> processFuture = new ObservableFuture<Process>(
                launcher.getExecutor().submit(task), task);

        // Completes when the game window is up (or launch failed) — keeps one ProgressDialog open.
        final SettableFuture<Process> readyFuture = SettableFuture.create();
        final AtomicBoolean gameStarted = new AtomicBoolean(false);

        Futures.addCallback(processFuture, new FutureCallback<Process>() {
            @Override
            public void onSuccess(Process result) {
            }

            @Override
            public void onFailure(Throwable t) {
                readyFuture.setException(t);
            }
        });
        readyFuture.addListener(() -> {
            if (readyFuture.isCancelled()) {
                processFuture.cancel(true);
            }
        }, sameThreadExecutor());

        ProgressObservable launchProgress = new ProgressObservable() {
            @Override
            public double getProgress() {
                return processFuture.isDone() ? -1 : processFuture.getProgress();
            }

            @Override
            public String getStatus() {
                return processFuture.isDone()
                        ? tr("launcher.launchingStatus", instance.getTitle())
                        : processFuture.getStatus();
            }
        };

        // Register before showProgress — that call blocks the EDT until readyFuture completes.
        ListenableFuture<ProcessConsoleFrame> lifeFuture = Futures.transform(
                processFuture,
                new LaunchProcessHandler(launcher, instance, listener, gameStarted, readyFuture),
                launcher.getExecutor());
        SwingHelper.addErrorDialogCallback(null, lifeFuture);

        lifeFuture.addListener(() -> {
            try {
                log.info("Process ended; cleaning up " + extractDir.getAbsolutePath());
                FileUtils.deleteDirectory(extractDir);
            } catch (IOException e) {
                log.log(Level.WARNING, "Failed to clean up " + extractDir.getAbsolutePath(), e);
            }
        }, sameThreadExecutor());

        Futures.addCallback(lifeFuture, new FutureCallback<ProcessConsoleFrame>() {
            @Override
            public void onSuccess(@Nullable ProcessConsoleFrame result) {
                if (gameStarted.get()) {
                    listener.gameClosed();
                }
            }

            @Override
            public void onFailure(Throwable t) {
                if (!(t instanceof CancellationException)) {
                    log.info("Process failure: " + t.getLocalizedMessage());
                }
            }
        }, SwingExecutor.INSTANCE);

        ProgressDialog.showProgress(
                window, readyFuture, launchProgress,
                SharedLocale.tr("launcher.launchingTItle"),
                tr("launcher.launchingStatus", instance.getTitle()));
    }


    @RequiredArgsConstructor
    static class MemoryVerifier implements BiFunction<Integer, Integer, Runner.MemoryVerificationResult> {
        private final Instance instance;

        @Override
        public Runner.MemoryVerificationResult apply(Integer currentMaxMemory, Integer requiredMaxMemory) {
            ListenableFuture<Runner.MemoryVerificationResult> fut = SwingExecutor.INSTANCE.submit(() -> {
                Object[] options = new Object[]{
                        tr("button.cancel"),
                        tr("button.increaseMemory"),
                        tr("button.launchAnyway"),
                };

                String message = tr("runner.insufficientMemory",
                        instance.getTitle(), formatMemoryGb(requiredMaxMemory), formatMemoryGb(currentMaxMemory));
                int picked = JOptionPane.showOptionDialog(null,
                        SwingHelper.htmlWrap(message),
                        tr("launcher.insufficientMemoryTitle"),
                        JOptionPane.DEFAULT_OPTION,
                        JOptionPane.WARNING_MESSAGE,
                        null,
                        options,
                        null);

                if (picked == 1) {
                    return Runner.MemoryVerificationResult.UPDATE_INSTANCE_SETTINGS;
                }
                if (picked == 2) {
                    return Runner.MemoryVerificationResult.LAUNCH_ANYWAY;
                }
                return Runner.MemoryVerificationResult.CANCEL;
            });

            try {
                return fut.get();
            } catch (ExecutionException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        private static String formatMemoryGb(int memoryMb) {
            return String.format(Locale.US, memoryMb % 1024 == 0 ? "%.0f" : "%.1f", memoryMb / 1024.0);
        }
    }
}
