package com.fuzzlauncher.infrastructure.mod;

import com.fuzzlauncher.domain.mod.ModFile;

import org.tomlj.*;

import java.io.IOException;
import java.util.*;

final class ForgeMetadataParser implements ModMetadataParser {
    private final String loader;
    private final String entry;

    ForgeMetadataParser(String loader, String entry) {
        this.loader = loader;
        this.entry = entry;
    }

    public String entry() {
        return entry;
    }

    @Override
    public void parse(String text, String jarVersion, List<ModFile.Metadata> output, List<String> icons) throws IOException {
        TomlParseResult result = Toml.parse(text);
        if (result.hasErrors()) {
            throw new IOException(result.errors().getFirst().toString());
        }
        TomlArray mods = result.getArray("mods");
        if (mods == null || mods.isEmpty()) {
            throw new IOException("缺少 [[mods]]");
        }
        for (int i = 0; i < mods.size(); i++) {
            TomlTable mod = mods.getTable(i);
            String id = mod.getString("modId");
            if (id == null || id.isBlank()) {
                throw new IOException("缺少 modId");
            }
            String version = mod.getString("version", () -> "未知").replace("${file.jarVersion}", jarVersion);
            output.add(new ModFile.Metadata(id, mod.getString("displayName", () -> id), version, mod.getString("authors", () -> ""),
                mod.getString("description", () -> ""), Set.of(loader)));
            String logo = mod.getString("logoFile", () -> result.getString("logoFile", () -> ""));
            if (!logo.isBlank()) {
                icons.add(logo);
            }
        }
    }
}
