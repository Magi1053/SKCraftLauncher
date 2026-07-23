/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.auth;

import lombok.Getter;
import lombok.NonNull;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * An offline session.
 */
public class OfflineSession implements Session {

    private static Map<String, String> dummyProperties = Collections.emptyMap();

    @Getter
    private final String name;
    private final String uuid;

    /**
     * Create a new offline session using the given player name.
     *
     * @param name the player name
     */
    public OfflineSession(@NonNull String name) {
        this.name = name;
        this.uuid = getOfflineUuid(name);
    }

    /**
     * Restore an offline session from disk.
     * Always re-derives the offline UUID from the username and never carries
     * avatar/texture data, so the game cannot resolve a Mojang/custom skin.
     */
    public static OfflineSession fromSavedSession(@NonNull SavedSession session) {
        return new OfflineSession(session.getUsername());
    }

    public static String getOfflineUuid(@NonNull String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)).toString();
    }

    @Override
    public String getUuid() {
        return uuid;
    }

    @Override
    public String getAccessToken() {
        return "0";
    }

    @Override
    public Map<String, String> getUserProperties() {
        return dummyProperties;
    }

    @Override
    public String getSessionToken() {
        return "-";
    }

    @Override
    public UserType getUserType() {
        return UserType.OFFLINE;
    }

    @Override
    public byte[] getAvatarImage() {
        return null;
    }

    @Override
    public boolean isOnline() {
        return false;
    }

    @Override
    public SavedSession toSavedSession() {
        SavedSession savedSession = new SavedSession();
        savedSession.setType(UserType.OFFLINE);
        savedSession.setUsername(name);
        savedSession.setUuid(uuid);
        // No access/refresh tokens or avatar — offline play uses Steve/Alex only.
        return savedSession;
    }

}
