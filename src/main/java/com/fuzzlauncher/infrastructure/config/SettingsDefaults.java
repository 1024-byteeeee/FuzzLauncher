package com.fuzzlauncher.infrastructure.config;

import com.fuzzlauncher.domain.instance.IsolationMode;
import com.fuzzlauncher.domain.settings.DownloadSource;
import com.fuzzlauncher.domain.settings.LauncherSettings;
import com.fuzzlauncher.domain.settings.LauncherVisibility;

import com.google.gson.JsonObject;

/** Applies release configuration at the I/O boundary, before constructing validated domain values. */
public final class SettingsDefaults {
    private SettingsDefaults() {
    }

    public static LauncherSettings create(String root, IsolationMode isolation) {
        return create(root, isolation, null);
    }

    public static LauncherSettings create(String root, IsolationMode isolation, DownloadSource source) {
        return create(root, isolation, source, "");
    }

    public static LauncherSettings create(String root, IsolationMode isolation, DownloadSource source, String data) {
        return create(root, isolation, source, data, null);
    }

    public static LauncherSettings create(String root, IsolationMode isolation, DownloadSource source, String data,
        LauncherVisibility visibility) {
        return create(root, isolation, source, data, visibility, null, null);
    }

    public static LauncherSettings create(String root, IsolationMode isolation, DownloadSource source, String data,
        LauncherVisibility visibility, Boolean automatic, Integer threads) {
        LauncherConfig config = LauncherConfig.get();
        return new LauncherSettings(root, isolation, source == null ? config.defaults().downloadSource() : source, data,
            visibility == null ? config.defaults().launcherVisibility() : visibility, automatic == null || automatic,
            threads == null ? config.network().maxConcurrency() : threads);
    }

    public static JsonObject withDefaults(JsonObject input) {
        JsonObject json = input.deepCopy();
        var defaults = LauncherConfig.get();
        if (!json.has("downloadSource") || json.get("downloadSource").isJsonNull()) {
            json.addProperty("downloadSource", defaults.defaults().downloadSource().name());
        }
        if (!json.has("launcherVisibility") || json.get("launcherVisibility").isJsonNull()) {
            json.addProperty("launcherVisibility", defaults.defaults().launcherVisibility().name());
        }
        if (!json.has("autoDownloadThreads") || json.get("autoDownloadThreads").isJsonNull()) {
            json.addProperty("autoDownloadThreads", true);
        }
        if (!json.has("downloadThreads") || json.get("downloadThreads").isJsonNull()) {
            json.addProperty("downloadThreads", defaults.network().maxConcurrency());
        }
        return json;
    }
}
