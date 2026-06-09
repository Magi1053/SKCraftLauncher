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

    public Downloader(Bootstrap bootstrap) {
        this(bootstrap, Mode.INITIAL, null, Collections.<LauncherBinary>emptyList());
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
            log.log(Level.WARNING, "Interrupted");
            launchFallbackOrExit();
        } catch (Throwable t) {
            log.log(Level.WARNING, "Failed to download launcher", t);
            if (mode == Mode.INITIAL) {
                SwingHelper.showErrorDialog(null, tr("errors.failedDownloadError"), tr("errorTitle"), t);
            }
            launchFallbackOrExit();
        }
    }

    private void execute() throws Exception {
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                Bootstrap.setSwingLookAndFeel();
                dialog = new DownloadFrame(Downloader.this);
                dialog.setVisible(true);
                dialog.setDownloader(Downloader.this);
            }
        });

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

            LauncherBinary binary = new LauncherBinary(finalFile);
            binaries.add(binary);
        } finally {
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    dialog.setDownloader(null);
                    dialog.dispose();
                }
            });
        }

        bootstrap.launchExisting(binaries, false);
    }

    private URL resolveDownloadUrl() throws Exception {
        if (mode == Mode.UPDATE) {
            if (updateUrl == null) {
                throw new IOException("Update URL was not provided");
            }
            return updateUrl;
        }

        URL latestUrl = HttpRequest.url(bootstrap.getProperties().getProperty("latestUrl"));
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
        if (mode == Mode.UPDATE && !existingBinaries.isEmpty()) {
            try {
                bootstrap.launchExisting(new ArrayList<LauncherBinary>(existingBinaries), false);
                return;
            } catch (Throwable t) {
                log.log(Level.WARNING, "Failed to launch existing launcher after update interruption", t);
            }
        }

        System.exit(0);
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
}
