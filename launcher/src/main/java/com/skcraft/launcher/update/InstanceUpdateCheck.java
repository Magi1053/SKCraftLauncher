/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.update;

import com.skcraft.concurrency.DefaultProgress;
import com.skcraft.concurrency.ProgressObservable;
import com.skcraft.launcher.Instance;
import com.skcraft.launcher.Launcher;
import com.skcraft.launcher.LauncherException;
import com.skcraft.launcher.model.modpack.ManifestInfo;
import com.skcraft.launcher.model.modpack.PackageList;
import com.skcraft.launcher.persistence.Persistence;
import com.skcraft.launcher.util.HttpRequest;
import com.skcraft.launcher.util.SharedLocale;
import lombok.NonNull;
import lombok.extern.java.Log;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.Callable;

import static com.skcraft.launcher.LauncherUtils.concat;

/**
 * Fetches packages.json and refreshes an instance's pending-update state.
 */
@Log
public class InstanceUpdateCheck implements Callable<Instance>, ProgressObservable {

    private final Launcher launcher;
    private final Instance instance;
    private ProgressObservable progress = new DefaultProgress(-1,
            SharedLocale.tr("launcher.updateCheckStatus"));

    public InstanceUpdateCheck(@NonNull Launcher launcher, @NonNull Instance instance) {
        this.launcher = launcher;
        this.instance = instance;
    }

    /**
     * Apply remote package listing metadata to a local instance.
     * Sets {@code updatePending} when the remote version differs; never clears it.
     */
    public static void applyPackageInfo(@NonNull Instance instance, @NonNull ManifestInfo manifest,
                                        @NonNull URL packagesURL) throws MalformedURLException {
        instance.setName(manifest.getName());
        instance.setTitle(manifest.getTitle());
        instance.setPriority(manifest.getPriority());
        URL url = concat(packagesURL, manifest.getLocation());
        instance.setManifestURL(url);
        instance.setNewsUrl(manifest.getNewsUrl());
        instance.setIconUrl(manifest.getIconUrl());

        log.info("(" + instance.getName() + ").setManifestURL(" + url + ")");

        if (instance.getVersion() == null || !instance.getVersion().equals(manifest.getVersion())) {
            instance.setUpdatePending(true);
            instance.setVersion(manifest.getVersion());
            Persistence.commitAndForget(instance);
            log.info(instance.getName() + " requires an update to " + manifest.getVersion());
        }
    }

    @Override
    public Instance call() throws Exception {
        log.info("Checking for updates for '" + instance.getName() + "'...");
        progress = new DefaultProgress(-1, SharedLocale.tr("launcher.updateCheckStatus"));

        try {
            URL packagesURL = launcher.getPackagesURL();

            PackageList packages = HttpRequest
                    .get(packagesURL)
                    .execute()
                    .expectResponseCode(200)
                    .returnContent()
                    .asJson(PackageList.class);

            if (packages.getMinimumVersion() > Launcher.PROTOCOL_VERSION) {
                throw new LauncherException("Update required", SharedLocale.tr("errors.updateRequiredError"));
            }

            ManifestInfo found = null;
            if (packages.getPackages() != null) {
                for (ManifestInfo manifest : packages.getPackages()) {
                    if (instance.getName().equalsIgnoreCase(manifest.getName())) {
                        found = manifest;
                        break;
                    }
                }
            }

            if (found == null) {
                throw new LauncherException("Pack missing from package list",
                        SharedLocale.tr("launcher.updateCheckMissing", instance.getTitle()));
            }

            applyPackageInfo(instance, found, packagesURL);
            return instance;
        } catch (LauncherException e) {
            throw e;
        } catch (IOException e) {
            throw new LauncherException(e, SharedLocale.tr("launcher.updateCheckFailed", instance.getTitle()));
        }
    }

    @Override
    public double getProgress() {
        return progress.getProgress();
    }

    @Override
    public String getStatus() {
        return progress.getStatus();
    }
}
