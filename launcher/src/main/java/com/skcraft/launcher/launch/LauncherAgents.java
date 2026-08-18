package com.skcraft.launcher.launch;

import lombok.extern.java.Log;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Log
final class LauncherAgents {

    static final String WINDOW_RESOURCE =
            "/com/skcraft/launcher/agents/skcraft-window-agent.bin";
    static final String LOG4J_RESOURCE =
            "/com/skcraft/launcher/agents/creeperhost-log4jpatcher.bin";

    private static final Pattern RELEASE_VERSION =
            Pattern.compile("^1\\.(\\d+)(?:\\.(\\d+))?(?:\\D.*)?$");
    private static final Pattern SNAPSHOT_VERSION =
            Pattern.compile("^(\\d{2})w(\\d{2})[a-z].*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOG4J_PATCHER =
            Pattern.compile(".*log4j[-_]?patcher.*", Pattern.CASE_INSENSITIVE);

    private static final List<AgentSpec> AGENTS = Arrays.asList(
            new AgentSpec(
                    "CreeperHost Log4jPatcher",
                    LOG4J_RESOURCE,
                    "creeperhost-log4jpatcher-",
                    null,
                    context -> isLog4jVulnerable(context.gameVersion)
                            && !hasLog4jPatcher(context.existingFlags)),
            new AgentSpec(
                    "window",
                    WINDOW_RESOURCE,
                    "skcraft-window-agent-",
                    "startMaximized",
                    context -> context.maximizeWindow));

    private LauncherAgents() {
    }

    static void configure(
            JavaProcessBuilder builder,
            File agentsDir,
            String gameVersion,
            boolean maximizeWindow) {
        Context context = new Context(gameVersion, maximizeWindow, builder.getFlags());
        for (AgentSpec agent : AGENTS) {
            if (!agent.condition.test(context)) {
                continue;
            }

            try {
                File jar = EmbeddedAgentInstaller.install(
                        agentsDir, agent.resourcePath, agent.filePrefix);
                builder.getLauncherFlags().add(javaAgentArgument(jar, agent.options));
            } catch (IOException e) {
                log.log(Level.WARNING,
                        "Embedded " + agent.name + " agent is unavailable; continuing without it",
                        e);
            }
        }
    }

    static boolean isLog4jVulnerable(String gameVersion) {
        if (gameVersion == null) {
            return true;
        }

        Matcher matcher = RELEASE_VERSION.matcher(gameVersion.trim());
        if (matcher.matches()) {
            int minor = Integer.parseInt(matcher.group(1));
            int patch = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 0;
            return minor >= 7 && (minor < 18 || minor == 18 && patch < 1);
        }

        matcher = SNAPSHOT_VERSION.matcher(gameVersion.trim());
        if (matcher.matches()) {
            int year = Integer.parseInt(matcher.group(1));
            int week = Integer.parseInt(matcher.group(2));
            return (year > 13 && year < 22) || (year == 13 && week >= 39);
        }

        return true;
    }

    static boolean hasLog4jPatcher(List<String> flags) {
        for (String flag : flags) {
            if (flag != null
                    && flag.toLowerCase(Locale.ROOT).startsWith("-javaagent:")
                    && LOG4J_PATCHER.matcher(flag).matches()) {
                return true;
            }
        }
        return false;
    }

    private static String javaAgentArgument(File jar, String options) {
        String argument = "-javaagent:" + jar.getAbsolutePath();
        return options == null || options.isEmpty() ? argument : argument + "=" + options;
    }

    private static final class Context {
        private final String gameVersion;
        private final boolean maximizeWindow;
        private final List<String> existingFlags;

        private Context(
                String gameVersion, boolean maximizeWindow, List<String> existingFlags) {
            this.gameVersion = gameVersion;
            this.maximizeWindow = maximizeWindow;
            this.existingFlags = existingFlags;
        }
    }

    private static final class AgentSpec {
        private final String name;
        private final String resourcePath;
        private final String filePrefix;
        private final String options;
        private final Predicate<Context> condition;

        private AgentSpec(
                String name,
                String resourcePath,
                String filePrefix,
                String options,
                Predicate<Context> condition) {
            this.name = name;
            this.resourcePath = resourcePath;
            this.filePrefix = filePrefix;
            this.options = options;
            this.condition = condition;
        }
    }
}
