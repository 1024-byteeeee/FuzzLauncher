package com.fuzzlauncher.infrastructure;

import com.fuzzlauncher.config.LauncherConfig;
import com.fuzzlauncher.domain.*;

import java.io.IOException;
import java.nio.file.*;

public final class SettingsStore {
    private final Path directory;

    public SettingsStore(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    public Path directory() {
        return directory;
    }

    public LauncherSettings load() throws IOException {
        Path file = directory.resolve("settings.json");
        if (!Files.exists(file) && !Files.exists(RecoverableJson.backup(file))) {
            return new LauncherSettings(LauncherPaths.directory().resolve(LauncherConfig.get().defaults().gameDirectory()).toString(),
                LauncherConfig.get().defaults().isolation());
        }
        return RecoverableJson.read(file, SettingsStore::decode);
    }

    public void save(LauncherSettings settings) throws IOException {
        RecoverableJson.write(directory.resolve("settings.json"), settings, SettingsStore::decode);
    }

    private static LauncherSettings decode(com.google.gson.JsonObject json) {
        RecoverableJson.enumField(json, "isolation", IsolationMode.class, "");
        RecoverableJson.enumField(json, "downloadSource", DownloadSource.class, "");
        RecoverableJson.enumField(json, "launcherVisibility", LauncherVisibility.class, "");
        RecoverableJson.integer(json, "downloadThreads", "");
        if (json.has("autoDownloadThreads")
            && (!json.get("autoDownloadThreads").isJsonPrimitive() || !json.getAsJsonPrimitive("autoDownloadThreads").isBoolean())) {
            throw new IllegalArgumentException("autoDownloadThreads 必须为布尔值");
        }
        LauncherSettings settings = JsonFiles.GSON.fromJson(json, LauncherSettings.class);
        if (settings == null || settings.gameRoot() == null || settings.gameRoot().isBlank() || settings.isolation() == null) {
            throw new IllegalArgumentException("gameRoot 或 isolation 字段缺失或无效");
        }
        Path.of(settings.gameRoot());
        return settings;
    }
}
