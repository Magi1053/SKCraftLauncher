/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.install;

import com.google.common.base.Charsets;
import com.google.common.base.Strings;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.skcraft.concurrency.ProgressObservable;
import com.skcraft.launcher.util.SharedHttpClient;
import com.skcraft.launcher.util.SharedLocale;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.java.Log;

import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.logging.Level;

import static com.skcraft.launcher.util.SharedLocale.tr;

@Log
public class HttpDownloader implements Downloader {

    private final Random random = new Random();
    private final HashFunction hf = Hashing.sha1();

    private final File tempDir;
    @Getter @Setter private int threadCount = 32;
    @Getter @Setter private int retryDelay = 2000;
    @Getter @Setter private int tryCount = 3;

    private List<HttpDownloadJob> queue = new ArrayList<HttpDownloadJob>();
    private final Set<String> usedKeys = new HashSet<String>();

    private final List<HttpDownloadJob> running = new ArrayList<HttpDownloadJob>();
    private final List<HttpDownloadJob> failed = new ArrayList<HttpDownloadJob>();
    private boolean interrupted;
    private long downloaded = 0;
    private long total = 0;
    private int left = 0;

    /**
     * Create a new downloader using the given executor.
     *
     * @param tempDir the temporary directory
     */
    public HttpDownloader(@NonNull File tempDir) {
        this.tempDir = tempDir;
    }

    /**
     * Make sure that we aren't re-using hash IDs.
     *
     * @param baseKey the key to make unique
     * @return a unique key
     */
    private String createUniqueKey(String baseKey) {
        String key = baseKey;
        int i = 0;
        while (usedKeys.contains(key)) {
            key = baseKey + "_" + (i++);
        }
        usedKeys.add(key);
        return key;
    }

    @Override
    public synchronized File download(@NonNull List<URL> urls, @NonNull String key, long size, String name) {
        if (urls.isEmpty()) {
            throw new IllegalArgumentException("Can't download empty list of URLs");
        }

        String hash = hf.hashString(Strings.nullToEmpty(key) + urls.get(0), Charsets.UTF_8).toString();
        hash = createUniqueKey(hash);
        File tempFile = new File(tempDir, hash.substring(0, 2) + "/" + hash);

        // If the file is already downloaded (such as from before), then don't re-download
        if (!tempFile.exists()) {
            total += size;
            left++;
            queue.add(new HttpDownloadJob(tempFile, urls, size, name != null ? name : tempFile.getName()));
        }

        return tempFile;
    }


    @Override
    public File download(URL url, String key, long size, String name) {
        List<URL> urls = new ArrayList<URL>();
        urls.add(url);
        return download(urls, key, size, name);
    }

    @Override
    public synchronized void download(@NonNull List<URL> urls, @NonNull File destination,
            long size, String name) {
        if (urls.isEmpty()) {
            throw new IllegalArgumentException("Can't download empty list of URLs");
        }

        if (!destination.exists()) {
            total += size;
            left++;
            queue.add(new HttpDownloadJob(destination, urls, size,
                    name != null ? name : destination.getName()));
        }
    }

    /**
     * Prevent further downloads from being queued and download queued files.
     *
     * @throws InterruptedException thrown on interruption
     * @throws IOException thrown on I/O error
     */
    public void execute() throws InterruptedException, IOException {
        synchronized (this) {
            queue = Collections.unmodifiableList(queue);
        }

        ListeningExecutorService executor = MoreExecutors.listeningDecorator(
                Executors.newFixedThreadPool(threadCount));

        try {
            List<ListenableFuture<?>> futures = new ArrayList<ListenableFuture<?>>();

            synchronized (this) {
                for (HttpDownloadJob job : queue) {
                    futures.add(executor.submit(job));
                }
            }

            try {
                Futures.allAsList(futures).get();
            } catch (ExecutionException e) {
                throw new IOException("Something went wrong", e);
            }

            synchronized (this) {
                if (interrupted) {
                    throw new InterruptedException("One or more downloads were interrupted");
                }
                if (failed.size() > 0) {
                    throw new IOException(failed.size() + " file(s) could not be downloaded");
                }
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Override
    public synchronized double getProgress() {
        if (total <= 0) {
            return -1;
        }

        long downloaded = this.downloaded;
        for (HttpDownloadJob job : running) {
            downloaded += Math.max(0, job.getProgress() * job.size);
        }
        return downloaded / (double) total;
    }

    @Override
    public synchronized String getStatus() {
        String failMessage = tr("downloader.failedCount", failed.size());
        if (running.size() == 1) {
            return tr("downloader.downloadingItem", running.get(0).getName()) +
                    "\n" + running.get(0).getStatus() +
                    "\n" + failMessage;
        } else if (running.size() > 0) {
            StringBuilder builder = new StringBuilder();
            for (HttpDownloadJob job : running) {
                builder.append("\n");
                builder.append(job.getStatus());
            }
            return tr("downloader.downloadingList", queue.size(), left, failed.size()) +
                    builder.toString() +
                    "\n" + failMessage;
        } else {
            return SharedLocale.tr("downloader.noDownloads");
        }
    }

    public class HttpDownloadJob implements Runnable, ProgressObservable {
        private final File destFile;
        private final List<URL> urls;
        private final long size;
        @Getter private String name;
        private volatile long transferred;

        private HttpDownloadJob(File destFile, List<URL> urls, long size, String name) {
            this.destFile = destFile;
            this.urls = urls;
            this.size = size;
            this.name = name;
        }

        @Override
        public void run() {
            try {
                synchronized (HttpDownloader.this) {
                    running.add(this);
                }

                download();

                synchronized (HttpDownloader.this) {
                    downloaded += size;
                }
            } catch (IOException | RuntimeException e) {
                deleteTemporaryFile();
                log.log(Level.WARNING, "Failed to download " + destFile + " from " + urls, e);
                synchronized (HttpDownloader.this) {
                    failed.add(this);
                }
            } catch (InterruptedException e) {
                deleteTemporaryFile();
                Thread.currentThread().interrupt();
                log.info("Download of " + destFile + " was interrupted");
                synchronized (HttpDownloader.this) {
                    interrupted = true;
                }
            } finally {
                synchronized (HttpDownloader.this) {
                    left--;
                    running.remove(this);
                }
            }
        }

        private void download() throws IOException, InterruptedException {
            log.log(Level.FINE, "Downloading " + destFile + " from " + urls);

            File destDir = destFile.getParentFile();
            File tempFile = getTemporaryFile();
            destDir.mkdirs();

            // Try to download
            download(tempFile);

            try {
                Files.move(tempFile.toPath(), destFile.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile.toPath(), destFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        }

        private File getTemporaryFile() {
            return new File(destFile.getParentFile(), destFile.getName() + ".tmp");
        }

        private void deleteTemporaryFile() {
            try {
                Files.deleteIfExists(getTemporaryFile().toPath());
            } catch (IOException e) {
                log.log(Level.FINE, "Failed to remove partial download for " + destFile, e);
            }
        }

        private void download(File file) throws IOException, InterruptedException {
            int trial = 0;
            boolean first = true;
            IOException lastException = null;

            do {
                for (URL url : urls) {
                    // Sleep between each trial
                    if (!first) {
                        Thread.sleep((long) (retryDelay / 2 + (random.nextDouble() * retryDelay)));
                    }
                    first = false;

                    try {
                        tryDownloadFrom(url, file);
                        return;
                    } catch (IOException e) {
                        lastException = e;
                    }
                }
            } while (++trial < tryCount);

            throw new IOException("Failed to download from " + urls, lastException);
        }

        private void tryDownloadFrom(URL url, File file) throws InterruptedException, IOException {
            long existingLength = file.isFile() ? file.length() : 0;
            if (size > 0 && existingLength == size) {
                transferred = existingLength;
                return;
            }

            HttpRequest.Builder builder = HttpRequest.newBuilder(toUri(url))
                    .GET()
                    .timeout(Duration.ofMinutes(10))
                    .header("User-Agent", "Mozilla/5.0 (Java) SKMCLauncher");
            if (existingLength > 0) {
                builder.header("Range", "bytes=" + existingLength + "-");
            }

            HttpResponse<InputStream> response;
            try {
                response = SharedHttpClient.get().send(builder.build(),
                        HttpResponse.BodyHandlers.ofInputStream());
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid download URL " + url, e);
            }

            int status = response.statusCode();
            boolean append = existingLength > 0 && status == 206;
            if (status != 200 && !append) {
                response.body().close();
                throw new IOException("Did not get expected response code, got " + status + " for " + url);
            }

            long baseLength = append ? existingLength : 0;
            long responseLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
            transferred = baseLength;

            StandardOpenOption[] options = append
                    ? new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                            StandardOpenOption.APPEND}
                    : new StandardOpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING};

            long responseBytes = 0;
            try (InputStream in = response.body();
                    OutputStream out = Files.newOutputStream(file.toPath(), options)) {
                byte[] buffer = new byte[32 * 1024];
                int length;
                while ((length = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, length);
                    responseBytes += length;
                    transferred = baseLength + responseBytes;
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException();
                    }
                }
            }

            if (responseLength >= 0 && responseBytes != responseLength) {
                throw new IOException(String.format(
                        "Connection closed with %d bytes transferred, expected %d",
                        responseBytes, responseLength));
            }
        }

        private URI toUri(URL url) throws IOException {
            try {
                return url.toURI();
            } catch (URISyntaxException e) {
                try {
                    // URL accepts unescaped spaces while URI does not. Preserve
                    // existing percent escapes and quote the common legacy form.
                    return new URI(url.toExternalForm().replace(" ", "%20"));
                } catch (URISyntaxException nested) {
                    throw new IOException("Invalid download URL " + url, nested);
                }
            }
        }

        @Override
        public double getProgress() {
            return size > 0 ? Math.min(1, transferred / (double) size) : -1;
        }

        @Override
        public String getStatus() {
            double progress = getProgress();
            if (progress >= 0) {
                return tr("downloader.jobProgress", name, Math.round(progress * 100 * 100) / 100.0);
            } else {
                return tr("downloader.jobPending", name);
            }
        }
    }
}
