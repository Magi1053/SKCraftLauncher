/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.swing;

import com.google.common.base.Charsets;
import com.google.common.hash.Hashing;
import com.skcraft.launcher.util.HttpRequest;
import lombok.NonNull;
import lombok.extern.java.Log;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.logging.Level;

/**
 * Downloads and caches remote instance icons under {@code baseDir/cache/icons}.
 */
@Log
public class InstanceIconCache {

    private static final int MAX_BYTES = 2 * 1024 * 1024;

    private final File cacheDir;
    private final Executor executor;
    private final Runnable onUpdated;
    private final ConcurrentHashMap<String, Icon> memory = new ConcurrentHashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Set<String> failed = ConcurrentHashMap.newKeySet();

    public InstanceIconCache(@NonNull File cacheDir, @NonNull Executor executor, @NonNull Runnable onUpdated) {
        this.cacheDir = cacheDir;
        this.executor = executor;
        this.onUpdated = onUpdated;
    }

    /**
     * Allow previously failed URLs to be fetched again (e.g. on instance list refresh).
     */
    public void clearFailed() {
        failed.clear();
    }

    /**
     * Return a scaled icon for the URL if available; otherwise kick off a fetch and return null.
     */
    public Icon get(String iconUrl) {
        if (iconUrl == null || iconUrl.isEmpty()) {
            return null;
        }

        Icon cached = memory.get(iconUrl);
        if (cached != null) {
            return cached;
        }
        if (failed.contains(iconUrl)) {
            return null;
        }

        File file = cacheFileFor(iconUrl);
        if (file.isFile()) {
            Icon fromDisk = loadScaledIcon(file);
            if (fromDisk != null) {
                memory.put(iconUrl, fromDisk);
                return fromDisk;
            }
        }

        requestFetch(iconUrl, file);
        return null;
    }

    private void requestFetch(String iconUrl, File destFile) {
        if (!inFlight.add(iconUrl)) {
            return;
        }

        executor.execute(() -> {
            try {
                fetchAndStore(iconUrl, destFile);
            } finally {
                inFlight.remove(iconUrl);
            }
        });
    }

    private void fetchAndStore(String iconUrl, File destFile) {
        URL url;
        try {
            url = parseHttpUrl(iconUrl);
        } catch (MalformedURLException | IllegalArgumentException e) {
            failed.add(iconUrl);
            log.log(Level.WARNING, "Invalid instance icon URL: " + iconUrl, e);
            return;
        }

        try {
            byte[] bytes = HttpRequest
                    .get(url)
                    .execute()
                    .expectResponseCode(200)
                    .returnContent()
                    .asBytes();

            if (bytes.length == 0 || bytes.length > MAX_BYTES) {
                throw new IOException("Icon payload size out of range: " + bytes.length);
            }

            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                throw new IOException("Unrecognized image data from " + iconUrl);
            }

            File parent = destFile.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }

            File tempFile = new File(destFile.getPath() + ".tmp");
            Files.write(tempFile.toPath(), bytes);
            destFile.delete();
            if (!tempFile.renameTo(destFile)) {
                Files.deleteIfExists(tempFile.toPath());
                throw new IOException("Failed to rename " + tempFile + " to " + destFile);
            }

            Icon icon = toScaledIcon(image);
            memory.put(iconUrl, icon);
            failed.remove(iconUrl);
            onUpdated.run();
        } catch (IOException | InterruptedException e) {
            failed.add(iconUrl);
            log.log(Level.WARNING, "Failed to download instance icon from " + iconUrl, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private File cacheFileFor(String iconUrl) {
        String hash = Hashing.sha1().hashString(iconUrl, Charsets.UTF_8).toString();
        return new File(cacheDir, hash.substring(0, 2) + "/" + hash);
    }

    private static URL parseHttpUrl(String iconUrl) throws MalformedURLException {
        URL url = new URL(iconUrl);
        String protocol = url.getProtocol();
        if (protocol == null) {
            throw new IllegalArgumentException("Missing URL protocol");
        }
        String normalized = protocol.toLowerCase(Locale.ROOT);
        if (!"http".equals(normalized) && !"https".equals(normalized)) {
            throw new IllegalArgumentException("Only http/https icon URLs are allowed: " + protocol);
        }
        return url;
    }

    private static Icon loadScaledIcon(File file) {
        try {
            BufferedImage image = ImageIO.read(file);
            if (image == null) {
                return null;
            }
            return toScaledIcon(image);
        } catch (IOException e) {
            log.log(Level.WARNING, "Failed to read cached instance icon " + file, e);
            return null;
        }
    }

    private static Icon toScaledIcon(BufferedImage image) {
        int size = InstanceRowStyle.ICON_SIZE;
        Image scaled = image.getScaledInstance(size, size, Image.SCALE_SMOOTH);
        return new ImageIcon(scaled);
    }

}
