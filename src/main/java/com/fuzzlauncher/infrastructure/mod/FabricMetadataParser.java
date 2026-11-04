package com.fuzzlauncher.infrastructure.mod;

import com.fuzzlauncher.domain.mod.ModFile;

import com.google.gson.*;

import java.util.*;

import static com.fuzzlauncher.infrastructure.mod.JsonMetadataFields.*;

final class FabricMetadataParser implements ModMetadataParser {
    public String entry() {
        return "fabric.mod.json";
    }

    @Override
    public void parse(String text, String jarVersion, List<ModFile.Metadata> output, List<String> icons) {
        JsonObject json = JsonParser.parseString(text).getAsJsonObject();
        String id = required(json, "id");
        output.add(new ModFile.Metadata(id, string(json, "name", id), string(json, "version", "未知"), people(json.get("authors")),
            string(json, "description", ""), Set.of("Fabric")));
        icon(json.get("icon"), icons);
    }
}
