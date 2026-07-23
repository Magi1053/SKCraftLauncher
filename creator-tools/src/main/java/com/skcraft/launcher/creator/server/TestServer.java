/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.creator.server;

import com.skcraft.launcher.creator.model.creator.ManifestEntry;
import lombok.Getter;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

import java.util.List;
import java.util.function.Supplier;

public class TestServer {

    @Getter private final Server server;
    private final PackagesHandler packagesHandler;

    public TestServer(Server server, PackagesHandler packagesHandler) {
        this.server = server;
        this.packagesHandler = packagesHandler;
    }

    public void setListingEntriesSupplier(Supplier<List<ManifestEntry>> listingEntriesSupplier) {
        packagesHandler.setListingEntriesSupplier(listingEntriesSupplier);
    }

    public void start() throws Exception {
        getServer().start();
    }

    public int getLocalPort() {
        return ((ServerConnector) server.getConnectors()[0]).getLocalPort();
    }

    public void stop() throws Exception {
        getServer().stop();
    }
}
