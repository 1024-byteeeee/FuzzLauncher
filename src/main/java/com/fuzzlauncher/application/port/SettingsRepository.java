package com.fuzzlauncher.application.port;

import com.fuzzlauncher.domain.settings.LauncherSettings;

import java.io.IOException;
import java.nio.file.Path;

public interface SettingsRepository {
    Path directory();

    LauncherSettings load() throws IOException;

    void save(LauncherSettings settings) throws IOException;
}
