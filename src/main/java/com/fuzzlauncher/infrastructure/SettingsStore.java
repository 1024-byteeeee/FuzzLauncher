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
        if (!Files.exists(file)) {
            return new LauncherSettings(LauncherPaths.directory().resolve(LauncherConfig.get().defaults().gameDirectory()).toString(),
                LauncherConfig.get().defaults().isolation());
        }
        LauncherSettings settings = JsonFiles.GSON.fromJson(JsonFiles.read(file), LauncherSettings.class);
        if (settings.gameRoot() == null || settings.isolation() == null) {
            throw new IOException("启动器设置不完整");
        }
        return settings;
    }

    public void save(LauncherSettings settings) throws IOException {
        JsonFiles.write(directory.resolve("settings.json"), settings);
    }
}
