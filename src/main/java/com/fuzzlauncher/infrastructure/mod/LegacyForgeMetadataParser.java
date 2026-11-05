package com.fuzzlauncher.infrastructure.mod;

import com.fuzzlauncher.domain.content.ContentFile;

import com.google.gson.*;

import java.util.*;

import static com.fuzzlauncher.infrastructure.mod.JsonMetadataFields.*;

final class LegacyForgeMetadataParser implements ModMetadataParser {
    public String entry() {
        return "mcmod.info";
    }

    @Override
    public void parse(String text, String jarVersion, List<ContentFile.Metadata> output, List<String> icons) {
        JsonElement json = JsonParser.parseString(text);
        JsonArray mods = json.isJsonArray() ? json.getAsJsonArray() : json.getAsJsonObject().getAsJsonArray("modList");
        if (mods == null || mods.isEmpty()) {
            throw new IllegalArgumentException("缺少 modList");
        }
        for (JsonElement value : mods) {
            JsonObject mod = value.getAsJsonObject();
            String id = required(mod, "modid");
            output.add(new ContentFile.Metadata(id, string(mod, "name", id), string(mod, "version", "未知"), people(mod.get("authorList")), string(mod, "description", ""), Set.of("Forge")));
            icon(mod.get("logoFile"), icons);
        }
    }
}
