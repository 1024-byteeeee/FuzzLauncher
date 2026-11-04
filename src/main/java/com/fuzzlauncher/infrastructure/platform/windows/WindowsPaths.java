package com.fuzzlauncher.infrastructure.platform.windows;

import java.nio.file.Path;

/** Extended paths are only for Win32 calls; stored paths keep their ordinary representation. */
public final class WindowsPaths {
    private WindowsPaths() {
    }

    public static String extended(Path path) {
        String value = path.toAbsolutePath().normalize().toString();
        if (value.startsWith("\\\\?\\")) {
            return value;
        }
        return value.startsWith("\\\\") ? "\\\\?\\UNC\\" + value.substring(2) : "\\\\?\\" + value;
    }
}
