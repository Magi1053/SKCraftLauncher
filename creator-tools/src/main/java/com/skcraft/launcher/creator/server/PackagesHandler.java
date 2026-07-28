/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.creator.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.skcraft.launcher.creator.model.creator.ManifestEntry;
import com.skcraft.launcher.model.modpack.Manifest;
import com.skcraft.launcher.model.modpack.ManifestInfo;
import com.skcraft.launcher.model.modpack.PackageList;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

class PackagesHandler extends AbstractHandler {

    private final ObjectMapper mapper;
    private final File baseDir;
    private volatile Supplier<List<ManifestEntry>> listingEntriesSupplier = Collections::emptyList;

    public PackagesHandler(ObjectMapper mapper, File baseDir) {
        this.mapper = mapper;
        this.baseDir = baseDir;
    }

    public void setListingEntriesSupplier(Supplier<List<ManifestEntry>> listingEntriesSupplier) {
        this.listingEntriesSupplier = listingEntriesSupplier != null
                ? listingEntriesSupplier
                : Collections::emptyList;
    }

    public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
        response.setContentType("text/plain; charset=utf-8");
        response.setStatus(HttpServletResponse.SC_OK);

        List<ManifestInfo> packages = Lists.newArrayList();
        PackageList packageList = new PackageList();
        packageList.setMinimumVersion(PackageList.MIN_VERSION);
        packageList.setPackages(packages);

        List<ManifestEntry> listingEntries = listingEntriesSupplier.get();
        if (listingEntries == null) {
            listingEntries = Collections.emptyList();
        }

        File[] files = baseDir.listFiles(new PackageFileFilter());
        if (files != null) {
            for (File file : files) {
                Manifest manifest = mapper.readValue(file, Manifest.class);
                ManifestInfo info = new ManifestInfo();
                info.setName(manifest.getName());
                info.setTitle(manifest.getTitle());
                info.setVersion(manifest.getVersion());
                info.setLocation(file.getName());

                for (ManifestEntry entry : listingEntries) {
                    ManifestInfo listed = entry.getManifestInfo();
                    if (listed != null && matchesListingEntry(file, manifest, listed)) {
                        info.setPriority(listed.getPriority());
                        info.setNewsUrl(listed.getNewsUrl());
                        info.setIconUrl(listed.getIconUrl());
                        break;
                    }
                }

                packages.add(info);
            }
        }

        mapper.writeValue(response.getWriter(), packageList);
        baseRequest.setHandled(true);
    }

    /**
     * Match listing metadata to a TestServer package file. Listing locations often
     * use production names while Test builds serve {@code staging.json}, so also
     * match by manifest name.
     */
    private static boolean matchesListingEntry(File file, Manifest manifest, ManifestInfo listed) {
        if (file.getName().equals(listed.getLocation())) {
            return true;
        }
        String packName = manifest.getName();
        return packName != null && packName.equalsIgnoreCase(listed.getName());
    }

    private static class PackageFileFilter implements FileFilter {
        @Override
        public boolean accept(File pathname) {
            return pathname.getName().toLowerCase().endsWith(".json");
        }
    }
}
