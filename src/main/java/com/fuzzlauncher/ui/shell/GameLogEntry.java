package com.fuzzlauncher.ui.shell;

import java.util.*;
import java.util.regex.Pattern;

record GameLogEntry(Level level, String text) {
    enum Level {
        FATAL, ERROR, WARN, INFO, DEBUG, OTHER;

        static Level from(String value) {
            if (value == null) {
                return OTHER;
            }
            return switch (value.toUpperCase(Locale.ROOT)) {
                case "FATAL" -> FATAL;
                case "ERROR", "SEVERE" -> ERROR;
                case "WARN", "WARNING" -> WARN;
                case "INFO" -> INFO;
                case "DEBUG", "TRACE" -> DEBUG;
                default -> OTHER;
            };
        }

        String cssClass() {
            return "game-log-" + name().toLowerCase(Locale.ROOT);
        }
    }

    private static final Pattern TEXT_LEVEL = Pattern.compile(
        "^\\s*(?:\\[[^\\]\\r\\n]+]\\s*)?\\[(?:[^\\]\\r\\n]+/)?(FATAL|ERROR|WARN(?:ING)?|INFO|DEBUG|TRACE|SEVERE)]",
        Pattern.CASE_INSENSITIVE);
    static List<GameLogEntry> parse(String text, int limit) {
        var entries = new ArrayDeque<GameLogEntry>();
        addPlain(entries, text, limit);
        return List.copyOf(entries);
    }

    private static void addPlain(Deque<GameLogEntry> entries, String text, int limit) {
        text.lines().filter(line -> !line.isBlank()).forEach(line -> {
            var matcher = TEXT_LEVEL.matcher(line);
            Level level = matcher.find() ? Level.from(matcher.group(1)) : Level.OTHER;
            String trimmed = line.stripLeading();
            if (level == Level.OTHER && !entries.isEmpty()
                && (trimmed.startsWith("at ") || trimmed.startsWith("Caused by:") || trimmed.startsWith("Suppressed:")
                    || trimmed.startsWith("... "))) {
                level = entries.getLast().level();
            }
            add(entries, new GameLogEntry(level, line), limit);
        });
    }

    private static void add(Deque<GameLogEntry> entries, GameLogEntry entry, int limit) {
        if (entries.size() == limit) {
            entries.removeFirst();
        }
        entries.addLast(entry);
    }
}
