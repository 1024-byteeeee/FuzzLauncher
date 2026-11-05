package com.fuzzlauncher.domain.content;

import com.fuzzlauncher.domain.mod.ModFileNames;

import java.nio.file.Path;

public enum ContentKind {
    MODS("mods"),
    RESOURCE_PACKS("resourcepacks"),
    SCHEMATICS("schematics");

    private final String directory;

    ContentKind(String directory) {
        this.directory = directory;
    }

    public String directory() { return directory; }

    public boolean supportsArchive(Path path) {
        if (path == null || path.getFileName() == null) {
            return false;
        }
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return switch (this) {
            case MODS -> ModFileNames.supported(path);
            case RESOURCE_PACKS -> ModFileNames.logicalName(name).endsWith(".zip");
            case SCHEMATICS -> name.endsWith(".litematic") || name.endsWith(".schem")
                || name.endsWith(".schematic") || name.endsWith(".nbt");
        };
    }

    public boolean supportsFolders() {
        return this == RESOURCE_PACKS;
    }
}
