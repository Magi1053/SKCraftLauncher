/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.util.download;

import java.io.IOException;
import java.net.URL;
import java.util.Map;

/**
 * Host-specific logic applied before an HTTP download request is sent.
 */
public interface DownloadRequestHandler {

    /**
     * Returns whether this handler applies to the given URL.
     *
     * @param url the request URL
     * @return true if this handler should prepare the request
     */
    boolean matches(URL url);

    /**
     * Prepare a request for a matching URL (for example by adding headers).
     *
     * @param url the request URL
     * @param headers mutable request headers for this URL only
     * @throws IOException on preparation failure
     */
    void prepare(URL url, Map<String, String> headers) throws IOException;

    /**
     * Optionally explain a failed response for a matching URL.
     *
     * @param url the request URL
     * @param responseCode the unexpected HTTP response code
     * @return a user-facing message, or {@code null} to skip
     */
    String explainFailure(URL url, int responseCode);
}
