package com.fuzzlauncher.domain;

public record LauncherSettings(
    String gameRoot, IsolationMode isolation, DownloadSource downloadSource, String dataDirectory) {
  public LauncherSettings {
    dataDirectory = dataDirectory == null ? "" : dataDirectory.strip();
    if (downloadSource == null) downloadSource = DownloadSource.AUTO;
  }

  public LauncherSettings(String gameRoot, IsolationMode isolation, DownloadSource source) {
    this(gameRoot, isolation, source, "");
  }

  public LauncherSettings(String gameRoot, IsolationMode isolation) {
    this(gameRoot, isolation, DownloadSource.AUTO);
  }
}
