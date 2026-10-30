package com.fuzzlauncher.domain;

import com.fuzzlauncher.config.LauncherConfig;

public record LauncherSettings(String gameRoot, IsolationMode isolation, DownloadSource downloadSource, String dataDirectory,
    LauncherVisibility launcherVisibility, Boolean autoDownloadThreads, Integer downloadThreads) {
    public LauncherSettings {
        dataDirectory = dataDirectory == null ? "" : dataDirectory.strip();
        if (downloadSource == null) {
            downloadSource = LauncherConfig.get().defaults().downloadSource();
        }
        if (launcherVisibility == null) {
            launcherVisibility = LauncherConfig.get().defaults().launcherVisibility();
        }
        autoDownloadThreads = autoDownloadThreads == null ? true : autoDownloadThreads;
        downloadThreads = downloadThreads == null ? LauncherConfig.get().network().maxConcurrency() : downloadThreads;
        if (downloadThreads < 1 || downloadThreads > 128) {
            throw new IllegalArgumentException("下载并行数应在 1–128 之间");
        }
    }

    public LauncherSettings(String gameRoot, IsolationMode isolation, DownloadSource source, String dataDirectory,
        LauncherVisibility visibility) {
        this(gameRoot, isolation, source, dataDirectory, visibility, true, null);
    }

    public int effectiveDownloadThreads() {
        return autoDownloadThreads ? LauncherConfig.get().network().concurrency() : downloadThreads;
    }

    public LauncherSettings(String gameRoot, IsolationMode isolation, DownloadSource source, String dataDirectory) {
        this(gameRoot, isolation, source, dataDirectory, LauncherConfig.get().defaults().launcherVisibility());
    }

    public LauncherSettings(String gameRoot, IsolationMode isolation, DownloadSource source) {
        this(gameRoot, isolation, source, "");
    }

    public LauncherSettings(String gameRoot, IsolationMode isolation) {
        this(gameRoot, isolation, LauncherConfig.get().defaults().downloadSource());
    }
}
