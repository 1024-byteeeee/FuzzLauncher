package com.fuzzlauncher.domain.modpack;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

public enum ModpackFormat {
    MCBBS("MCBBS 整合包", ".zip"), MULTIMC("MultiMC", ".zip"), SERVER("服务器自动更新整合包", ".zip"), MODRINTH("Modrinth", ".mrpack");

    private final String label;
    private final String extension;

    ModpackFormat(String label, String extension) {
        this.label = label;
        this.extension = extension;
    }

    public String extension() {
        return extension;
    }

    public static boolean supportsArchive(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            return false;
        }
        String name = fileName.toString().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).anyMatch(format -> name.endsWith(format.extension()));
    }

    @Override
    public String toString() {
        return label;
    }
}
