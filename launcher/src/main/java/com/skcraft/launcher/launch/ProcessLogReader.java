/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.launch;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads both process streams continuously and appends parsed lines into a
 * bounded {@link GameLogBuffer}.
 */
public final class ProcessLogReader {

    private static final Logger log = Logger.getLogger(ProcessLogReader.class.getName());

    private final Process process;
    private final GameLogBuffer buffer;
    private final Runnable onDirty;
    private final GameLogReadySignal readySignal;
    private final AtomicBoolean running = new AtomicBoolean();

    private Thread stdoutThread;
    private Thread stderrThread;
    private InputStream stdout;
    private InputStream stderr;

    public ProcessLogReader(Process process, GameLogBuffer buffer, Runnable onDirty) {
        this(process, buffer, onDirty, null);
    }

    public ProcessLogReader(Process process, GameLogBuffer buffer, Runnable onDirty,
            GameLogReadySignal readySignal) {
        this.process = process;
        this.buffer = buffer;
        this.onDirty = onDirty;
        this.readySignal = readySignal;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }

        stdout = process.getInputStream();
        stderr = process.getErrorStream();
        if (readySignal != null) {
            stdout = readySignal.tee(stdout);
            stderr = readySignal.tee(stderr);
        }

        stdoutThread = new Thread(() -> consume(stdout, new GameLogParser()), "Game log stdout");
        stderrThread = new Thread(() -> consume(stderr, new GameLogParser()), "Game log stderr");
        stdoutThread.setDaemon(true);
        stderrThread.setDaemon(true);
        stdoutThread.start();
        stderrThread.start();
    }

    public void stop() {
        // The process normally reached EOF before this call. Give both readers
        // a chance to publish their final partial line before closing streams.
        joinQuietly(stdoutThread, 250);
        joinQuietly(stderrThread, 250);
        running.set(false);
        closeQuietly(stdout);
        closeQuietly(stderr);
        joinQuietly(stdoutThread);
        joinQuietly(stderrThread);
    }

    private void consume(InputStream stream, GameLogParser parser) {
        final byte[] chunk = new byte[4096];
        final StringBuilder pending = new StringBuilder();

        try {
            int read;
            while (running.get() && (read = stream.read(chunk)) != -1) {
                pending.append(new String(chunk, 0, read, StandardCharsets.UTF_8));
                drainLines(pending, parser);
            }

            if (pending.length() > 0) {
                emitParsed(parser.parseLine(stripCarriageReturn(pending.toString())));
                pending.setLength(0);
            }
            emitParsed(parser.flushPending());
        } catch (IOException e) {
            if (running.get()) {
                log.log(Level.FINE, "Stopped reading process stream", e);
            }
        } finally {
            closeQuietly(stream);
        }
    }

    private void drainLines(StringBuilder pending, GameLogParser parser) {
        int lineStart = 0;
        for (int i = 0; i < pending.length(); i++) {
            if (pending.charAt(i) == '\n') {
                String line = pending.substring(lineStart, i);
                emitParsed(parser.parseLine(stripCarriageReturn(line)));
                lineStart = i + 1;
            }
        }
        if (lineStart > 0) {
            pending.delete(0, lineStart);
        }
    }

    private static String stripCarriageReturn(String line) {
        if (line.endsWith("\r")) {
            return line.substring(0, line.length() - 1);
        }
        return line;
    }

    private void emitParsed(List<GameLogLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        buffer.appendAll(lines);
        onDirty.run();
    }

    private static void joinQuietly(Thread thread) {
        joinQuietly(thread, 2000);
    }

    private static void joinQuietly(Thread thread, long timeoutMillis) {
        if (thread == null || thread == Thread.currentThread()) {
            return;
        }
        try {
            thread.join(timeoutMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }
}
