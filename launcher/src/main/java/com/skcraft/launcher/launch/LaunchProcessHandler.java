/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.launch;

import com.google.common.base.Function;
import com.google.common.util.concurrent.SettableFuture;
import com.skcraft.launcher.Instance;
import com.skcraft.launcher.Launcher;
import com.skcraft.launcher.LauncherException;
import com.skcraft.launcher.dialog.ProcessConsoleFrame;
import com.skcraft.launcher.swing.MessageLog;
import com.skcraft.launcher.util.SharedLocale;
import lombok.NonNull;
import lombok.extern.java.Log;

import javax.annotation.Nullable;
import javax.swing.*;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * After {@link Runner} starts the game process: attach logs, wait until the
 * game window is up, dismiss the launching dialog / launcher, then wait for
 * exit.
 */
@Log
public class LaunchProcessHandler implements Function<Process, ProcessConsoleFrame> {

    private static final int CONSOLE_NUM_LINES = 10_000;
    // EDT frame budget for batching document updates (~60 FPS); process I/O remains
    // continuous.
    private static final int CONSOLE_UI_REFRESH_DELAY_MS = 16;

    private final Launcher launcher;
    private final Instance instance;
    private final LaunchListener listener;
    private final AtomicBoolean gameStarted;
    private final SettableFuture<Process> readyFuture;
    private ProcessConsoleFrame consoleFrame;
    private boolean showConsole;

    public LaunchProcessHandler(@NonNull Launcher launcher, @NonNull Instance instance,
            @NonNull LaunchListener listener, @NonNull AtomicBoolean gameStarted,
            @NonNull SettableFuture<Process> readyFuture) {
        this.launcher = launcher;
        this.instance = instance;
        this.listener = listener;
        this.gameStarted = gameStarted;
        this.readyFuture = readyFuture;
    }

    @Override
    public ProcessConsoleFrame apply(@Nullable Process process) {
        if (process == null) {
            throw new IllegalArgumentException("process must not be null");
        }
        log.info("Watching process " + process);

        GameLogReadySignal logSignal = new GameLogReadySignal();
        readyFuture.addListener(() -> {
            if (readyFuture.isCancelled() && process.isAlive()) {
                process.destroy();
            }
        }, r -> new Thread(r, "launch-ready-cancel").start());

        final GameLogBuffer buffer = new GameLogBuffer(CONSOLE_NUM_LINES);
        final CoalescedLogUpdater updater = new CoalescedLogUpdater(buffer);
        final ProcessLogReader reader = new ProcessLogReader(process, buffer, updater::requestSync, logSignal);

        try {
            attachConsole(process, buffer, updater);
            reader.start();

            GameWindowWatcher.Result result = GameWindowWatcher.waitUntilReady(process, logSignal);
            log.info("Game readiness: " + result);

            if (result == GameWindowWatcher.Result.PROCESS_DIED) {
                reader.stop();
                SwingUtilities.invokeAndWait(updater::flushNow);

                // Force Close / cancel during launch dies with a non-zero code — not a crash.
                if (wasUserAborted()) {
                    if (!readyFuture.isDone()) {
                        readyFuture.cancel(false);
                    }
                    log.info("Game process terminated by user before becoming ready");
                } else {
                    showConsoleForFailure();
                    int exitCode = process.exitValue();
                    LauncherException failure = new LauncherException(
                            "Game process exited before becoming ready with code " + exitCode,
                            SharedLocale.tr("console.processExitedEarly"));
                    if (!readyFuture.isDone()) {
                        readyFuture.setException(failure);
                    }
                    throw new RuntimeException(failure.getLocalizedMessage(), failure);
                }
            } else {
                if (result == GameWindowWatcher.Result.READY && launcher.getConfig().isMaximizeWindow()) {
                    GameWindowWatcher.maximizeGameWindow(process);
                }

                // Dismiss launching dialog before disposing its owner (the launcher frame).
                markReady(process);
                disposeLauncherIfRunning(process, result);

                process.waitFor();
            }
        } catch (InterruptedException e) {
            if (!readyFuture.isDone()) {
                readyFuture.setException(e);
            }
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException e) {
            log.log(Level.WARNING, "Launch UI failure", e);
            if (!readyFuture.isDone()) {
                readyFuture.setException(e);
            }
            if (process.isAlive()) {
                process.destroy();
            }
        } finally {
            reader.stop();
            try {
                SwingUtilities.invokeAndWait(updater::flushNow);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (InvocationTargetException e) {
                log.log(Level.FINE, "Unable to finalize game console refresh", e);
            }
        }

        log.info("Process ended");
        SwingUtilities.invokeLater(() -> {
            if (consoleFrame == null) {
                return;
            }
            consoleFrame.processEnded(showConsole);
        });
        return consoleFrame;
    }

    private void attachConsole(Process process, GameLogBuffer buffer, CoalescedLogUpdater updater)
            throws InterruptedException, InvocationTargetException {
        SwingUtilities.invokeAndWait(() -> {
            ProcessConsoleFrame.closeExisting(instance.getName());

            showConsole = launcher.getConfig().isShowInstanceConsole();
            consoleFrame = ProcessConsoleFrame.createGameConsole(CONSOLE_NUM_LINES, showConsole);
            consoleFrame.setTitle(SharedLocale.tr("console.title") + " - " + instance.getTitle());
            consoleFrame.registerForInstance(instance.getName());

            MessageLog messageLog = consoleFrame.getMessageLog();
            updater.bind(messageLog);
            messageLog.bindGameBuffer(buffer, updater::requestSync);

            consoleFrame.setProcess(process);
            consoleFrame.setVisible(showConsole);
        });
    }

    private void disposeLauncherIfRunning(Process process, GameWindowWatcher.Result result)
            throws InterruptedException, InvocationTargetException {
        SwingUtilities.invokeAndWait(() -> {
            if (!process.isAlive()) {
                return;
            }
            if (result != GameWindowWatcher.Result.READY && result != GameWindowWatcher.Result.TIMEOUT) {
                return;
            }
            if (gameStarted.compareAndSet(false, true)) {
                listener.gameStarted();
            }
        });
    }

    private void showConsoleForFailure() throws InterruptedException, InvocationTargetException {
        SwingUtilities.invokeAndWait(() -> {
            if (consoleFrame != null) {
                showConsole = true;
                consoleFrame.setVisible(true);
                consoleFrame.requestFocus();
            }
        });
    }

    private void markReady(Process process) {
        if (!readyFuture.isDone()) {
            readyFuture.set(process);
        }
    }

    private boolean wasUserAborted() {
        return readyFuture.isCancelled() || (consoleFrame != null && consoleFrame.wasKilledByUser());
    }

    private static final class CoalescedLogUpdater {
        private final GameLogBuffer buffer;
        private final AtomicBoolean dirty = new AtomicBoolean();
        private final AtomicBoolean syncScheduled = new AtomicBoolean();
        private volatile MessageLog messageLog;
        private Timer syncTimer;

        private CoalescedLogUpdater(GameLogBuffer buffer) {
            this.buffer = buffer;
        }

        private void bind(MessageLog messageLog) {
            this.messageLog = messageLog;
        }

        private void requestSync() {
            dirty.set(true);
            scheduleSync();
        }

        private void scheduleSync() {
            if (syncScheduled.compareAndSet(false, true)) {
                SwingUtilities.invokeLater(this::startTimer);
            }
        }

        private void startTimer() {
            if (syncTimer == null) {
                syncTimer = new Timer(CONSOLE_UI_REFRESH_DELAY_MS, event -> flushToUi());
                syncTimer.setRepeats(false);
            }
            syncTimer.restart();
        }

        private void flushToUi() {
            MessageLog log = messageLog;
            if (log != null && dirty.compareAndSet(true, false)) {
                log.refreshFromBuffer(buffer);
            }

            syncScheduled.set(false);
            if (dirty.get()) {
                scheduleSync();
            }
        }

        private void flushNow() {
            if (syncTimer != null) {
                syncTimer.stop();
            }
            syncScheduled.set(false);
            dirty.set(false);
            MessageLog log = messageLog;
            if (log != null) {
                log.refreshFromBuffer(buffer);
            }
        }
    }

}
