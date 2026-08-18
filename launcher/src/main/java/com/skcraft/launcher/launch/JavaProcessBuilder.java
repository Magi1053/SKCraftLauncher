/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.launch;

import com.skcraft.launcher.launch.runtime.JavaRuntime;
import com.skcraft.launcher.util.Environment;
import com.skcraft.launcher.util.Platform;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A tool to build the entire command line used to launch a Java process.
 * It combines flags, memory settings, arguments, the class path, and
 * the main class.
 */
@ToString
public class JavaProcessBuilder {

    private static final Pattern argsPattern = Pattern.compile("(?:([^\"]\\S*)|\"(.+?)\")\\s*");

    @Getter @Setter private JavaRuntime runtime;
    @Getter @Setter private int minMemory;
    @Getter @Setter private int maxMemory;

    @Getter private final List<File> classPath = new ArrayList<File>();
    private final List<String> launcherFlags = new ArrayList<String>();
    @Getter private final List<String> flags = new ArrayList<String>();
    @Getter private final List<String> args = new ArrayList<String>();
    @Getter @Setter private String mainClass;

    /**
     * Launcher-owned JVM flags, kept separate from user and modpack flags.
     */
    public List<String> getLauncherFlags() {
        return launcherFlags;
    }

    private File getJavaBinPath() throws IOException {
        File path = runtime.getDir().getAbsoluteFile();

        // Try the parent directory
        if (!path.exists()) {
            throw new IOException(
                    "The configured Game Runtime path '" + path + "' doesn't exist.");
        } else if (path.isFile()) {
            path = path.getParentFile();
        }

        File binDir = new File(path, "bin");
        if (binDir.isDirectory()) {
            path = binDir;
        }

        return path;
    }

    /**
     * Prefer {@code javaw.exe} on Windows so the game has no console HWND for
     * {@link GameWindowWatcher} to mistake as the client window.
     */
    static File resolveJavaExecutable(File binDir) {
        if (Environment.detectPlatform() == Platform.WINDOWS) {
            File javaw = new File(binDir, "javaw.exe");
            if (javaw.isFile()) {
                return javaw;
            }
            File javaExe = new File(binDir, "java.exe");
            if (javaExe.isFile()) {
                return javaExe;
            }
        }
        return new File(binDir, "java");
    }

    public JavaProcessBuilder classPath(File file) {
        getClassPath().add(file);
        return this;
    }

    public JavaProcessBuilder classPath(String path) {
        getClassPath().add(new File(path));
        return this;
    }

    public String buildClassPath() {
        StringBuilder builder = new StringBuilder();
        boolean first = true;

        for (File file : classPath) {
            if (first) {
                first = false;
            } else {
                builder.append(File.pathSeparator);
            }

            builder.append(file.getAbsolutePath());
        }

        return builder.toString();
    }

    public List<String> buildCommand() throws IOException {
        List<String> command = new ArrayList<String>();

        if (getRuntime() != null) {
            command.add(resolveJavaExecutable(getJavaBinPath()).getAbsolutePath());
        } else {
            command.add("java");
        }

        command.addAll(launcherFlags);
        command.addAll(flags);

        if (minMemory > 0) {
            command.add("-Xms" + minMemory + "M");
        }

        if (maxMemory > 0) {
            command.add("-Xmx" + maxMemory + "M");
        }

        command.add(mainClass);
        command.addAll(args);

        return command;
    }

    /**
     * Split the given string as simple command line arguments.
     *
     * <p>This is not to be used for security purposes.</p>
     *
     * @param str the string
     * @return the split args
     */
    public static List<String> splitArgs(String str) {
        Matcher matcher = argsPattern.matcher(str);
        List<String> parts = new ArrayList<String>();
        while (matcher.find()) {
            parts.add(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
        }
        return parts;
    }

}
