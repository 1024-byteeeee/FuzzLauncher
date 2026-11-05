package com.fuzzlauncher.infrastructure.mod;

import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

final class SchematicDirectory {
    private static final int CONFIG_LIMIT = 1024 * 1024;

    private SchematicDirectory() {
    }

    static Path resolve(Path gameDirectory, Path fallback) {
        Path config = gameDirectory.resolve("config/litematica.json");
        if (!Files.isRegularFile(config)) {
            return fallback;
        }
        try (var input = Files.newInputStream(config)) {
            byte[] bytes = input.readNBytes(CONFIG_LIMIT + 1);
            if (bytes.length > CONFIG_LIMIT) {
                return fallback;
            }
            var generic = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("Generic");
            if (generic != null && generic.has("customSchematicBaseDirectoryEnabled")
                && generic.get("customSchematicBaseDirectoryEnabled").getAsBoolean()
                && generic.has("customSchematicBaseDirectory")) {
                String configured = generic.get("customSchematicBaseDirectory").getAsString();
                if (!configured.isBlank()) {
                    return gameDirectory.resolve(Path.of(configured)).normalize();
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // An invalid mod configuration must not prevent browsing the default folder.
        }
        return fallback;
    }
}
