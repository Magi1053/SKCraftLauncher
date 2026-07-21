/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.launch;

import java.util.ArrayList;
import java.util.List;

/**
 * Thread-safe fixed-size circular buffer for game log lines.
 */
public final class GameLogBuffer {

    private final String[] lines;
    private final GameLogLevel[] levels;
    private int first;
    private int count;
    private long totalAppended;
    private long publishedStart;
    private long publishedEnd;

    public GameLogBuffer(int maxLines) {
        if (maxLines <= 0) {
            throw new IllegalArgumentException("maxLines must be > 0");
        }
        this.lines = new String[maxLines];
        this.levels = new GameLogLevel[maxLines];
    }

    public synchronized void append(String line) {
        append(line, GameLogLevel.INFO);
    }

    public synchronized void append(GameLogLine line) {
        if (line == null) {
            return;
        }
        append(line.getText(), line.getLevel());
    }

    private void append(String line, GameLogLevel level) {
        if (line == null) {
            return;
        }
        int index = (first + count) % lines.length;
        lines[index] = line;
        levels[index] = level != null ? level : GameLogLevel.INFO;
        totalAppended++;

        if (count == lines.length) {
            first = (first + 1) % lines.length;
        } else {
            count++;
        }
    }

    public synchronized void appendAll(List<GameLogLine> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        for (GameLogLine line : batch) {
            append(line);
        }
    }

    public synchronized void clear() {
        for (int i = 0; i < count; i++) {
            int index = (first + i) % lines.length;
            lines[index] = null;
            levels[index] = null;
        }
        first = 0;
        count = 0;
        publishedStart = totalAppended;
        publishedEnd = totalAppended;
    }

    /**
     * Return the changes since the previous drain, normalized to the current
     * bounded contents. This prevents an idle EDT from accumulating an
     * unbounded pending batch when the producer wraps the ring repeatedly.
     */
    public synchronized SyncDelta drainDelta() {
        long currentEnd = totalAppended;
        long currentStart = currentEnd - count;
        long previousCount = publishedEnd - publishedStart;

        int dropped = (int) Math.min(previousCount,
                Math.max(0, currentStart - publishedStart));
        long appendStart = Math.max(publishedEnd, currentStart);
        int appendCount = (int) (currentEnd - appendStart);
        List<GameLogLine> appended = new ArrayList<>(appendCount);

        for (long sequence = appendStart; sequence < currentEnd; sequence++) {
            int logicalIndex = (int) (sequence - currentStart);
            int index = (first + logicalIndex) % lines.length;
            appended.add(new GameLogLine(lines[index], levels[index]));
        }

        publishedStart = currentStart;
        publishedEnd = currentEnd;
        return new SyncDelta(dropped, appended);
    }

    public static final class SyncDelta {
        private final int dropped;
        private final List<GameLogLine> appended;

        private SyncDelta(int dropped, List<GameLogLine> appended) {
            this.dropped = dropped;
            this.appended = appended;
        }

        public int getDropped() {
            return dropped;
        }

        public List<GameLogLine> getAppended() {
            return appended;
        }
    }
}
