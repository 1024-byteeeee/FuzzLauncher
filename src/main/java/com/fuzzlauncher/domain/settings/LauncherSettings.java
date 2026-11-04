package com.fuzzlauncher.domain.settings;

import com.fuzzlauncher.domain.instance.IsolationMode;

import java.util.Objects;

public record LauncherSettings(String gameRoot, IsolationMode isolation, DownloadSource downloadSource, String dataDirectory,
    LauncherVisibility launcherVisibility, Boolean autoDownloadThreads, Integer downloadThreads) {
    public LauncherSettings {
        Objects.requireNonNull(gameRoot);
        Objects.requireNonNull(isolation);
        Objects.requireNonNull(downloadSource);
        Objects.requireNonNull(launcherVisibility);
        Objects.requireNonNull(autoDownloadThreads);
        Objects.requireNonNull(downloadThreads);
        if (gameRoot.isBlank()) {
            throw new IllegalArgumentException("游戏根目录不能为空");
        }
        dataDirectory = dataDirectory == null ? "" : dataDirectory.strip();
        if (downloadThreads < 1 || downloadThreads > 128) {
            throw new IllegalArgumentException("下载并行数应在 1–128 之间");
        }
    }

    public int effectiveDownloadThreads(int automaticConcurrency) {
        return autoDownloadThreads ? automaticConcurrency : downloadThreads;
    }
}
