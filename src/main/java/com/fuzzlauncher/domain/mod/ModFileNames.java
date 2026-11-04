package com.fuzzlauncher.domain.mod;

import java.nio.file.Path;
import java.util.Locale;

public final class ModFileNames {
    private ModFileNames() {
    }

    public static boolean supported(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        String name = logicalName(path.getFileName().toString());
        return name.endsWith(".jar") || name.endsWith(".zip") || name.endsWith(".litemod");
    }

    public static String logicalName(String name) {
        name = name.toLowerCase(Locale.ROOT);
        return name.endsWith(".disabled") ? name.substring(0, name.length() - ".disabled".length()) : name;
    }
}
