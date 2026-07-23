/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.util;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Process-wide HTTP client used for bulk file downloads.
 */
public final class SharedHttpClient {

    private static final int EXECUTOR_THREADS = 32;
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(
            EXECUTOR_THREADS, new DownloadThreadFactory());
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .executor(EXECUTOR)
            .build();

    private SharedHttpClient() {
    }

    public static HttpClient get() {
        return CLIENT;
    }

    private static final class DownloadThreadFactory implements ThreadFactory {
        private final AtomicInteger nextId = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable,
                    "launcher-http-" + nextId.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
