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

import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;

@Log
public class UpdateChecker {

    private static final int CHECK_TIMEOUT_MS = 5000;

    private final Bootstrap bootstrap;

    public UpdateChecker(Bootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    public UpdateInfo checkForUpdate(String currentVersion) {
        try {
            String encodedVersion = URLEncoder.encode(currentVersion, "UTF-8");
            String latestUrl = bootstrap.resolveSelfUpdateUrl();
            URL url = HttpRequest.url(latestUrl + "?version=" + encodedVersion);

            String data = HttpRequest.get(url)
                    .timeout(CHECK_TIMEOUT_MS)
                    .execute()
                    .expectResponseCode(200)
                    .returnContent()
                    .asString("UTF-8");

            Object object = JSONValue.parse(data);
            if (!(object instanceof JSONObject)) {
                log.warning("Invalid latest.json payload, expected object.");
                return null;
            }

            JSONObject json = (JSONObject) object;
            String latestVersion = readString(json, "version");
            String rawUrl = readString(json, "url");

            if (latestVersion == null || rawUrl == null) {
                log.warning("latest.json is missing required 'version' or 'url' fields.");
                return null;
            }

            ComparableVersion current = new ComparableVersion(currentVersion);
            ComparableVersion latest = new ComparableVersion(latestVersion);

            if (latest.compareTo(current) > 0) {
                return new UpdateInfo(latestVersion, HttpRequest.url(rawUrl.trim()));
            }

            return null;
        } catch (Exception e) {
            log.info(String.format(
                    Locale.ROOT,
                    "Skipping bootstrap update check and launching local JAR (reason: %s)",
                    e.getMessage()));
            return null;
        }
    }

    private static String readString(JSONObject object, String key) {
        Object value = object.get(key);
        if (value == null) {
            return null;
        }

        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    public static final class UpdateInfo {
        private final String version;
        private final URL url;

        public UpdateInfo(String version, URL url) {
            this.version = version;
            this.url = url;
        }

        public String getVersion() {
            return version;
        }

        public URL getUrl() {
            return url;
        }
    }
}
