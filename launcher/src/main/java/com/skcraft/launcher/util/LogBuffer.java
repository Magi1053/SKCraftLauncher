/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Keeps a ring buffer of recent root-logger records so the launcher console
 * can show history when opened after startup.
 */
public final class LogBuffer extends Handler {

    private static final int DEFAULT_CAPACITY = 10000;

    private static LogBuffer instance;

    private final int capacity;
    private final ArrayDeque<LogRecord> records;

    private LogBuffer(int capacity) {
        this.capacity = capacity;
        this.records = new ArrayDeque<LogRecord>(Math.min(capacity, 256));
        setFormatter(new SimpleLogFormatter());
    }

    /**
     * Attach the buffer to the root logger once.
     */
    public static synchronized void init() {
        if (instance != null) {
            return;
        }

        instance = new LogBuffer(DEFAULT_CAPACITY);
        Logger.getLogger("").addHandler(instance);
    }

    /**
     * @return the shared buffer, or null if {@link #init()} has not run
     */
    public static synchronized LogBuffer get() {
        return instance;
    }

    /**
     * Clear buffered history.
     */
    public static synchronized void clearBuffer() {
        if (instance != null) {
            instance.clear();
        }
    }

    /**
     * Snapshot of buffered records in chronological order.
     *
     * @return copied list of records
     */
    public synchronized List<LogRecord> snapshot() {
        return new ArrayList<LogRecord>(records);
    }

    /**
     * Drop all buffered records.
     */
    public synchronized void clear() {
        records.clear();
    }

    @Override
    public synchronized void publish(LogRecord record) {
        if (record == null || !isLoggable(record) || !isLauncherRecord(record)) {
            return;
        }

        while (records.size() >= capacity) {
            records.removeFirst();
        }
        records.addLast(copyRecord(record));
    }

    private static boolean isLauncherRecord(LogRecord record) {
        String name = record.getLoggerName();
        return name == null || name.startsWith("com.skcraft");
    }

    @Override
    public void flush() {
    }

    @Override
    public synchronized void close() {
        records.clear();
    }

    private static LogRecord copyRecord(LogRecord source) {
        LogRecord copy = new LogRecord(source.getLevel(), source.getMessage());
        copy.setLoggerName(source.getLoggerName());
        copy.setResourceBundle(source.getResourceBundle());
        copy.setResourceBundleName(source.getResourceBundleName());
        copy.setSequenceNumber(source.getSequenceNumber());
        copy.setSourceClassName(source.getSourceClassName());
        copy.setSourceMethodName(source.getSourceMethodName());
        copy.setThrown(source.getThrown());
        Object[] parameters = source.getParameters();
        copy.setParameters(parameters != null ? parameters.clone() : null);
        return copy;
    }
}
