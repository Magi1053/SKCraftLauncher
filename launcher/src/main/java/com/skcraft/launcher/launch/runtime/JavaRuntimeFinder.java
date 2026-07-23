/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.launch.runtime;

import com.skcraft.launcher.model.minecraft.JavaVersion;
import com.skcraft.launcher.util.Environment;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Finds the best Java runtime to use.
 */
public final class JavaRuntimeFinder {

    private static volatile List<JavaRuntime> cachedRuntimes;

    private JavaRuntimeFinder() {
    }

    /**
     * Get all available Java runtimes on the system
     *
     * @return List of available Java runtimes sorted by newest first
     */
    public static List<JavaRuntime> getAvailableRuntimes() {
        List<JavaRuntime> cached = cachedRuntimes;
        if (cached != null) {
            return cached;
        }

        synchronized (JavaRuntimeFinder.class) {
            if (cachedRuntimes != null) {
                return cachedRuntimes;
            }

            Environment env = Environment.getInstance();
            PlatformRuntimeFinder runtimeFinder = getRuntimeFinder(env);

            if (runtimeFinder == null) {
                cachedRuntimes = Collections.emptyList();
                return cachedRuntimes;
            }

            cachedRuntimes = Collections.unmodifiableList(
                    runtimeFinder.getSystemRuntimes(env).stream()
                            .distinct() // JavaRuntime.equals is by dir
                            .sorted()
                            .collect(Collectors.toList()));
            return cachedRuntimes;
        }
    }

    /**
     * Find the best runtime for a given Java version
     *
     * @param targetVersion Version to match
     * @return Java runtime if available, empty Optional otherwise
     */
    public static Optional<JavaRuntime> findBestJavaRuntime(JavaVersion targetVersion) {
        return getAvailableRuntimes().stream()
                .filter(runtime -> runtime.getMajorVersion() == targetVersion.getMajorVersion())
                .findFirst();
    }

    public static Optional<JavaRuntime> findAnyJavaRuntime() {
        return getAvailableRuntimes().stream().findFirst();
    }

    public static JavaRuntime getRuntimeFromPath(String path) {
        return getRuntimeFromPath(new File(path));
    }

    public static JavaRuntime getRuntimeFromPath(File target) {
        // Normalize target to root first
        if (target.isFile()) {
            // Probably referring directly to bin/java, back up two levels
            target = target.getParentFile().getParentFile();
        } else if (target.getName().equals("bin")) {
            // Probably copied the bin directory that java.exe is in
            target = target.getParentFile();
        }

        // Find the release file
        File releaseFile = new File(target, "release");
        if (!releaseFile.isFile()) {
            releaseFile = new File(target, "jre/release");
            // may still not exist - parseFromRelease below will return null if so
        }

        // Find the bin folder
        File binFolder = new File(target, "bin");
        if (!binFolder.isDirectory()) {
            binFolder = new File(target, "jre/bin");
        }

        if (!binFolder.isDirectory()) {
            // No bin folder, this isn't a usable install
            return null;
        }

        JavaReleaseFile release = JavaReleaseFile.parseFromRelease(releaseFile.getParentFile());
        if (release == null) {
            // Make some assumptions...
            return new JavaRuntime(binFolder.getParentFile(), null, true);
        }

        return new JavaRuntime(binFolder.getParentFile(), release.getVersion(), release.isArch64Bit());
    }

    private static PlatformRuntimeFinder getRuntimeFinder(Environment env) {
        switch (env.getPlatform()) {
            case WINDOWS:
                return new WindowsRuntimeFinder();
            case MAC_OS_X:
                return new MacRuntimeFinder();
            case LINUX:
                return new LinuxRuntimeFinder();
            default:
                return null;
        }
    }
}
