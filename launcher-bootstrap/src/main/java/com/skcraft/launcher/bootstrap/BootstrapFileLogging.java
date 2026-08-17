/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.bootstrap;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public final class BootstrapFileLogging {

    private static final Logger ROOT_LOGGER = Logger.getLogger("");
    private static final Logger log = Logger.getLogger(BootstrapFileLogging.class.getName());

    private static BufferHandler bufferHandler;
    private static boolean initialized;
    private static boolean attached;

    private BootstrapFileLogging() {
    }

    public static synchronized void init() {
        if (initialized) {
            return;
        }

        bufferHandler = new BufferHandler();
        ROOT_LOGGER.addHandler(bufferHandler);
        initialized = true;
    }

    public static synchronized void attachFile(File baseDir) {
        if (attached || baseDir == null) {
            return;
        }

        File logDir = new File(baseDir, "logs");
        File logFile = new File(logDir, "bootstrap.log");
        try {
            if (!logDir.exists() && !logDir.mkdirs()) {
                log.warning("Failed to create bootstrap log directory " + logDir.getAbsolutePath()
                        + "; continuing with console-only logging.");
                return;
            }

            FileHandler fileHandler = new FileHandler(logFile.getAbsolutePath(), false);
            fileHandler.setFormatter(new SimpleLogFormatter());
            ROOT_LOGGER.addHandler(fileHandler);

            if (bufferHandler != null) {
                bufferHandler.replayTo(fileHandler);
                ROOT_LOGGER.removeHandler(bufferHandler);
                bufferHandler.close();
                bufferHandler = null;
            }

            fileHandler.flush();
            attached = true;
        } catch (IOException e) {
            log.log(Level.WARNING, "Failed to open bootstrap log file " + logFile.getAbsolutePath()
                    + "; continuing with console-only logging.", e);
        }
    }

    private static final class BufferHandler extends Handler {

        private final List<LogRecord> buffered = new ArrayList<LogRecord>();

        @Override
        public synchronized void publish(LogRecord record) {
            if (record == null || !isLoggable(record)) {
                return;
            }

            buffered.add(copyRecord(record));
        }

        synchronized void replayTo(Handler target) {
            for (LogRecord record : buffered) {
                target.publish(record);
            }
            target.flush();
            buffered.clear();
        }

        @Override
        public synchronized void flush() {
        }

        @Override
        public synchronized void close() {
            buffered.clear();
        }

        private static LogRecord copyRecord(LogRecord source) {
            LogRecord copy = new LogRecord(source.getLevel(), source.getMessage());
            copy.setLoggerName(source.getLoggerName());
            copy.setResourceBundle(source.getResourceBundle());
            copy.setResourceBundleName(source.getResourceBundleName());
            copy.setSequenceNumber(source.getSequenceNumber());
            copy.setSourceClassName(source.getSourceClassName());
            copy.setSourceMethodName(source.getSourceMethodName());
            copy.setMillis(source.getMillis());
            copy.setThreadID(source.getThreadID());
            copy.setThrown(source.getThrown());
            Object[] parameters = source.getParameters();
            copy.setParameters(parameters != null ? parameters.clone() : null);
            return copy;
        }
    }
}
