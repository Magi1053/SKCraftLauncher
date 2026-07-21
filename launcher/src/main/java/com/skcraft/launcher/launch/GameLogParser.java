/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.launch;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses game output lines from both legacy plain-text streams and modern
 * log4j XML events.
 */
public final class GameLogParser {

    private static final String LOG4J_EVENT_START = "<log4j:Event";
    private static final String LOG4J_EVENT_END = "</log4j:Event>";

    private static final Pattern ATTR_PATTERN = Pattern.compile("(\\w+)=\"([^\"]*)\"");
    private static final Pattern MESSAGE_PATTERN = Pattern.compile(
            "(?s)<log4j:Message(?:\\s[^>]*)?>(.*?)</log4j:Message>");
    private static final Pattern PLAIN_LEVEL_PATTERN = Pattern.compile(
            "\\[[^\\]\\r\\n]*/(FATAL|ERROR|WARN(?:ING)?|INFO|DEBUG|TRACE)\\]",
            Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)
                    .withZone(ZoneId.systemDefault());

    private final StringBuilder pendingXml = new StringBuilder();

    public List<GameLogLine> parseLine(String line) {
        List<GameLogLine> out = new ArrayList<>();
        if (line == null) {
            return out;
        }

        if (pendingXml.length() > 0) {
            pendingXml.append(line).append('\n');
            if (line.contains(LOG4J_EVENT_END)) {
                out.add(parseLog4jEvent(pendingXml.toString()));
                pendingXml.setLength(0);
            }
            return out;
        }

        String trimmed = line.trim();
        if (trimmed.startsWith(LOG4J_EVENT_START)) {
            if (trimmed.contains(LOG4J_EVENT_END)) {
                out.add(parseLog4jEvent(trimmed));
            } else {
                pendingXml.append(line).append('\n');
            }
            return out;
        }

        out.add(new GameLogLine(line, guessLevel(line)));
        return out;
    }

    public List<GameLogLine> flushPending() {
        List<GameLogLine> out = new ArrayList<>();
        if (pendingXml.length() > 0) {
            String pending = pendingXml.toString().trim();
            out.add(new GameLogLine(pending, guessLevel(pending)));
            pendingXml.setLength(0);
        }
        return out;
    }

    private static GameLogLine parseLog4jEvent(String xmlEvent) {
        String logger = "";
        String level = "INFO";
        String thread = "main";
        String timeText = "";

        Matcher attrMatcher = ATTR_PATTERN.matcher(xmlEvent);
        while (attrMatcher.find()) {
            String key = attrMatcher.group(1);
            String value = attrMatcher.group(2);
            if ("logger".equalsIgnoreCase(key)) {
                logger = decodeXml(value);
            } else if ("level".equalsIgnoreCase(key)) {
                level = decodeXml(value);
            } else if ("thread".equalsIgnoreCase(key)) {
                thread = decodeXml(value);
            } else if ("timestamp".equalsIgnoreCase(key)) {
                try {
                    long ms = Long.parseLong(value);
                    timeText = TIME_FORMATTER.format(Instant.ofEpochMilli(ms));
                } catch (NumberFormatException ignored) {
                }
            }
        }

        Matcher msgMatcher = MESSAGE_PATTERN.matcher(xmlEvent);
        String message;
        if (msgMatcher.find()) {
            message = decodeXml(msgMatcher.group(1));
        } else {
            message = decodeXml(xmlEvent);
        }
        message = message
                .replace("<![CDATA[", "")
                .replace("]]>", "")
                .replace("\r", "")
                .replace('\n', ' ')
                .trim();

        if (timeText.isEmpty()) {
            timeText = TIME_FORMATTER.format(Instant.now());
        }
        if (logger.isEmpty()) {
            logger = "Minecraft";
        }
        String text = "[" + timeText + "] [" + thread + "/" + level + "] [" + logger + "]: " + message;
        return new GameLogLine(text, GameLogLevel.fromName(level));
    }

    private static GameLogLevel guessLevel(String line) {
        Matcher matcher = PLAIN_LEVEL_PATTERN.matcher(line);
        return matcher.find() ? GameLogLevel.fromName(matcher.group(1)) : GameLogLevel.INFO;
    }

    private static String decodeXml(String value) {
        return value
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
    }
}
