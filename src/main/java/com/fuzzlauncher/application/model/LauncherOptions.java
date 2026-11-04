package com.fuzzlauncher.application.model;

import com.fuzzlauncher.domain.settings.DownloadSource;

import java.util.*;

public record LauncherOptions(int automaticDownloads, int defaultMemory, List<String> jvmArguments, Map<DownloadSource, Source> sources) {

    public LauncherOptions {
        jvmArguments = List.copyOf(jvmArguments);
        sources = Map.copyOf(sources);
    }
    public record Source(String label, String website) {
    }
}
