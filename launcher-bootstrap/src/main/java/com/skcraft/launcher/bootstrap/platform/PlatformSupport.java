/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.bootstrap.platform;

import lombok.experimental.Delegate;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.Properties;

public enum PlatformSupport {
    INSTANCE;

    @Delegate
    private final Adapter adapter = current();

    private static Adapter current() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return new WindowsPlatformSupport();
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return new MacPlatformSupport();
        }
        if (os.contains("linux")) {
            return new LinuxPlatformSupport();
        }
        return new Adapter() {
            @Override
            public File resolveDataDir(Properties properties, Class<?> anchor) throws IOException {
                throw new IOException("Unsupported operating system: " + System.getProperty("os.name"));
            }
        };
    }

    abstract static class Adapter {

        public abstract File resolveDataDir(Properties properties, Class<?> anchor) throws IOException;

        public void applyAppIdentity(Class<?> anchor) {
        }

        static String resolveProperty(Properties properties, String platformKey, String fallbackKey) {
            String value = properties.getProperty(platformKey);
            if (value != null && !value.isEmpty()) {
                return value;
            }
            return properties.getProperty(fallbackKey, "");
        }
    }
}
