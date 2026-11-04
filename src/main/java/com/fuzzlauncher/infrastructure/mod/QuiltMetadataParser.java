package com.fuzzlauncher.infrastructure.mod;

import com.fuzzlauncher.domain.mod.ModFile;

import com.google.gson.*;

import java.util.*;

import static com.fuzzlauncher.infrastructure.mod.JsonMetadataFields.*;

final class QuiltMetadataParser implements ModMetadataParser {
    public String entry() {
        return "quilt.mod.json";
    }

    @Override
    public void parse(String text, String jarVersion, List<ModFile.Metadata> output, List<String> icons) {
        JsonObject json = JsonParser.parseString(text).getAsJsonObject();
        JsonObject loader = json.getAsJsonObject("quilt_loader");
        String id = required(loader, "id");
        JsonObject metadata = loader.has("metadata") ? loader.getAsJsonObject("metadata") : new JsonObject();
        String authors = metadata.has("contributors") ? String.join(", ", metadata.getAsJsonObject("contributors").keySet()) : "";
        output.add(new ModFile.Metadata(id, string(metadata, "name", id), string(loader, "version", "未知"), authors,
            string(metadata, "description", ""), Set.of("Quilt")));
        icon(metadata.get("icon"), icons);
    }
}
