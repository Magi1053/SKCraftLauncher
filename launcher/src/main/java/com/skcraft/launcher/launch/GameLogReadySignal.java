/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.launch;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

/**
 * Scans game stdout/stderr for lines that imply the client window is up.
 * Used as the readiness signal on non-Windows platforms.
 */
public final class GameLogReadySignal {

    private final CountDownLatch latch = new CountDownLatch(1);

    public boolean isSignaled() {
        return latch.getCount() == 0;
    }

    public InputStream tee(InputStream source) {
        return new LineScanningInputStream(source, this::offerLine);
    }

    private void offerLine(String line) {
        if (matchesReadyHeuristic(line)) {
            latch.countDown();
        }
    }

    static boolean matchesReadyHeuristic(String line) {
        if (line == null || line.isEmpty()) {
            return false;
        }
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("sound engine started")
                || (lower.contains("created:") && lower.contains("display"))
                || (lower.contains("lwjgl")
                    && (lower.contains("framebuffer") || lower.contains("opengl")));
    }

    private static final class LineScanningInputStream extends FilterInputStream {
        private final Consumer<String> lineConsumer;
        private final StringBuilder lineBuf = new StringBuilder(256);

        LineScanningInputStream(InputStream in, Consumer<String> lineConsumer) {
            super(in);
            this.lineConsumer = lineConsumer;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                acceptByte((byte) b);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                for (int i = 0; i < n; i++) {
                    acceptByte(b[off + i]);
                }
            }
            return n;
        }

        private void acceptByte(byte value) {
            char c = (char) (value & 0xFF);
            if (c == '\n') {
                flushLine();
            } else if (c != '\r') {
                lineBuf.append(c);
            }
        }

        private void flushLine() {
            if (lineBuf.length() > 0) {
                lineConsumer.accept(lineBuf.toString());
                lineBuf.setLength(0);
            }
        }

        @Override
        public void close() throws IOException {
            flushLine();
            super.close();
        }
    }
}
