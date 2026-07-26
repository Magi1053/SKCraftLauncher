/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.util.download;

import com.skcraft.launcher.Launcher;
import com.skcraft.launcher.LauncherUtils;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * CurseForge CDN / Maven download request handling (API key header and 401 errors).
 */
public final class CurseForgeDownloadHandler implements DownloadRequestHandler {

    private static final String API_KEY_ENVIRONMENT_VARIABLE = "CURSEFORGE_API_KEY";
    private static final String API_KEY_SYSTEM_PROPERTY = "com.skcraft.launcher.curseForgeApiKey";
    private static final String API_KEY_PROPERTY = "curseForgeApiKey";

    private boolean apiKeyLoaded;
    private String apiKey;

    @Override
    public boolean matches(URL url) {
        String host = url.getHost().toLowerCase(Locale.ROOT);
        if (host.equals("forgecdn.net") || host.endsWith(".forgecdn.net")
                || host.equals("cursemaven.com") || host.endsWith(".cursemaven.com")) {
            return true;
        }

        return (host.equals("curseforge.com") || host.endsWith(".curseforge.com"))
                && url.getPath().startsWith("/api/maven/");
    }

    @Override
    public void prepare(URL url, Map<String, String> headers) throws IOException {
        String key = getApiKey();
        if (key != null) {
            headers.put("x-api-key", key);
        }
    }

    @Override
    public String explainFailure(URL url, int responseCode) {
        if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
            return "CurseForge rejected the download request. Configure a valid "
                    + "curseForgeApiKey in launcher.properties.";
        }
        return null;
    }

    private synchronized String getApiKey() throws IOException {
        if (!apiKeyLoaded) {
            apiKey = firstNonBlank(
                    System.getenv(API_KEY_ENVIRONMENT_VARIABLE),
                    System.getProperty(API_KEY_SYSTEM_PROPERTY),
                    loadPropertiesApiKey());
            apiKeyLoaded = true;
        }
        return apiKey;
    }

    private static String loadPropertiesApiKey() throws IOException {
        Properties properties = LauncherUtils.loadProperties(
                Launcher.class, "launcher.properties", "com.skcraft.launcher.propertiesFile");
        return properties.getProperty(API_KEY_PROPERTY);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }
}
