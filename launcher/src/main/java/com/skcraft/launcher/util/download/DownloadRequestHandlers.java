/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.util.download;

import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Registry of host-specific download request handlers.
 */
public final class DownloadRequestHandlers {

    private static final List<DownloadRequestHandler> HANDLERS = Collections.unmodifiableList(
            Arrays.<DownloadRequestHandler>asList(new CurseForgeDownloadHandler()));

    private DownloadRequestHandlers() {
    }

    /**
     * Run preparation for every handler that matches the URL.
     *
     * @param url the request URL
     * @param headers mutable request headers for this URL only
     * @throws IOException on preparation failure
     */
    public static void prepare(URL url, Map<String, String> headers) throws IOException {
        for (DownloadRequestHandler handler : HANDLERS) {
            if (handler.matches(url)) {
                handler.prepare(url, headers);
            }
        }
    }

    /**
     * Return the first non-null failure explanation from a matching handler.
     *
     * @param url the request URL
     * @param responseCode the unexpected HTTP response code
     * @return a user-facing message, or {@code null}
     */
    public static String explainFailure(URL url, int responseCode) {
        for (DownloadRequestHandler handler : HANDLERS) {
            if (handler.matches(url)) {
                String message = handler.explainFailure(url, responseCode);
                if (message != null) {
                    return message;
                }
            }
        }
        return null;
    }
}
