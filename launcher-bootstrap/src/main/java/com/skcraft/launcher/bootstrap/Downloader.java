/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.bootstrap;

import com.skcraft.launcher.Bootstrap;
import lombok.extern.java.Log;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;

import static com.skcraft.launcher.bootstrap.BootstrapUtils.checkInterrupted;
import static com.skcraft.launcher.bootstrap.SharedLocale.tr;

@Log
public class Downloader implements Runnable, ProgressObservable {

    public enum Mode {
        INITIAL,
        UPDATE
    }

    private final Bootstrap bootstrap;
    private final Mode mode;
    private final URL updateUrl;
    private final List<LauncherBinary> existingBinaries;
    private DownloadFrame dialog;
    private HttpRequest httpRequest;
    private Thread thread;
    private List<LauncherBinary> binaries;

    public Downloader(Bootstrap bootstrap) {
        this(bootstrap, Collections.<LauncherBinary>emptyList());
    }

    public Downloader(Bootstrap bootstrap, List<LauncherBinary> existingBinaries) {
        this(bootstrap, Mode.INITIAL, null, existingBinaries);
    }

    public Downloader(Bootstrap bootstrap, URL updateUrl, List<LauncherBinary> existingBinaries) {
        this(bootstrap, Mode.UPDATE, updateUrl, existingBinaries);
    }

    private Downloader(Bootstrap bootstrap, Mode mode, URL updateUrl, List<LauncherBinary> existingBinaries) {
        this.bootstrap = bootstrap;
        this.mode = mode;
        this.updateUrl = updateUrl;
        this.existingBinaries = new ArrayList<LauncherBinary>(existingBinaries);
    }

    @Override
    public void run() {
        this.thread = Thread.currentThread();

        try {
            execute();
        } catch (InterruptedException e) {
            if (hasFallbackBinaries()) {
                log.info("Download interrupted; launching bundled launcher.");
            } else {
                log.log(Level.WARNING, "Interrupted");
            }
            launchFallbackOrExit();
        } catch (Throwable t) {
            if (hasFallbackBinaries()) {
                log.log(Level.INFO, "Download failed; launching bundled launcher.", t);
            } else {
                log.log(Level.WARNING, "Failed to download launcher", t);
                if (mode == Mode.INITIAL) {
                    SwingHelper.showErrorDialog(null, tr("errors.failedDownloadError"), tr("errorTitle"), t);
                }
            }
            launchFallbackOrExit();
        }
    }

    private void execute() throws Exception {
        setupProgressUi();

        List<LauncherBinary> binaries = new ArrayList<LauncherBinary>(existingBinaries);
        URL resolvedUrl = resolveDownloadUrl();

        try {
            checkInterrupted();

            File finalFile = new File(bootstrap.getBinariesDir(), System.currentTimeMillis() + ".jar");
            File tempFile = new File(finalFile.getParentFile(), finalFile.getName() + ".tmp");

            log.info("Downloading " + resolvedUrl + " to " + tempFile.getAbsolutePath());

            httpRequest = HttpRequest.get(resolvedUrl);
            httpRequest
                    .execute()
                    .expectResponseCode(200)
                    .saveContent(tempFile);

            finalFile.delete();
            tempFile.renameTo(finalFile);

            writeLauncherVersionFile(finalFile);

            LauncherBinary binary = new LauncherBinary(finalFile);
            binaries.add(binary);
        } finally {
            teardownProgressUi();
        }

        finish(binaries);
    }

    public List<LauncherBinary> getBinaries() {
        return binaries;
    }

    private void finish(List<LauncherBinary> binaries) {
        this.binaries = binaries;
    }

    private void setupProgressUi() throws Exception {
        if (mode != Mode.UPDATE && hasFallbackBinaries()) {
            return;
        }

        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                Bootstrap.setSwingLookAndFeel();
                dialog = new DownloadFrame(Downloader.this);
                dialog.setVisible(true);
                dialog.setDownloader(Downloader.this);
            }
        });
    }

    private void teardownProgressUi() {
        if (dialog != null) {
            final DownloadFrame frame = dialog;
            dialog = null;
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    frame.setDownloader(null);
                    frame.dispose();
                }
            });
        }
    }

    private URL resolveDownloadUrl() throws Exception {
        if (mode == Mode.UPDATE) {
            if (updateUrl == null) {
                throw new IOException("Update URL was not provided");
            }
            return updateUrl;
        }

        URL latestUrl = HttpRequest.url(bootstrap.resolveSelfUpdateUrl());
        log.info("Reading update URL " + latestUrl + "...");

        String data = HttpRequest
                .get(latestUrl)
                .execute()
                .expectResponseCode(200)
                .returnContent()
                .asString("UTF-8");

        Object object = JSONValue.parse(data);
        if (!(object instanceof JSONObject)) {
            log.warning("Did not get valid update document - got:\n\n" + data);
            throw new IOException("Update URL did not return a valid result");
        }

        Object rawUrlValue = ((JSONObject) object).get("url");
        if (rawUrlValue == null) {
            log.warning("Did not get valid update document - got:\n\n" + data);
            throw new IOException("Update URL did not return a valid result");
        }

        return HttpRequest.url(String.valueOf(rawUrlValue).trim());
    }

    private void launchFallbackOrExit() {
        teardownProgressUi();

        List<LauncherBinary> binaries = existingBinaries.isEmpty()
                ? discoverLocalBinaries()
                : new ArrayList<LauncherBinary>(existingBinaries);

        if (!binaries.isEmpty()) {
            finish(binaries);
            return;
        }

        System.exit(1);
    }

    private boolean hasFallbackBinaries() {
        return !existingBinaries.isEmpty() || !discoverLocalBinaries().isEmpty();
    }

    private List<LauncherBinary> discoverLocalBinaries() {
        File[] files = bootstrap.getBinariesDir().listFiles(new LauncherBinary.Filter());
        if (files == null || files.length == 0) {
            return Collections.emptyList();
        }

        List<LauncherBinary> binaries = new ArrayList<LauncherBinary>();
        for (File file : files) {
            binaries.add(new LauncherBinary(file));
        }
        return binaries;
    }

    public void cancel() {
        thread.interrupt();
    }

    public String getStatus() {
        HttpRequest httpRequest = this.httpRequest;
        if (httpRequest != null) {
            double progress = httpRequest.getProgress();
            if (progress >= 0) {
                return String.format(tr("downloader.progressStatus"), progress * 100);
            }
        }

        return tr("downloader.status");
    }

    @Override
    public double getProgress() {
        HttpRequest httpRequest = this.httpRequest;
        return httpRequest != null ? httpRequest.getProgress() : -1;
    }

    private static void writeLauncherVersionFile(File launcherJar) throws IOException {
        String version = JarVersionReader.readVersion(launcherJar);
        File versionFile = new File(launcherJar.getParentFile(), "launcher.version");
        Files.writeString(versionFile.toPath(), version.trim(), StandardCharsets.UTF_8);
    }
}
