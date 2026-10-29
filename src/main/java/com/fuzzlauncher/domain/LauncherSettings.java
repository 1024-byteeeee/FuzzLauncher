package com.fuzzlauncher.domain;

public record LauncherSettings(
    String gameRoot,
    IsolationMode isolation,
    DownloadSource downloadSource,
    String dataDirectory,
    LauncherVisibility launcherVisibility) {
  public LauncherSettings {
    dataDirectory = dataDirectory == null ? "" : dataDirectory.strip();
    if (downloadSource == null) downloadSource = DownloadSource.AUTO;
    if (launcherVisibility == null) launcherVisibility = LauncherVisibility.KEEP;
  }

  public LauncherSettings(
      String gameRoot, IsolationMode isolation, DownloadSource source, String dataDirectory) {
    this(gameRoot, isolation, source, dataDirectory, LauncherVisibility.KEEP);
  }

  public LauncherSettings(String gameRoot, IsolationMode isolation, DownloadSource source) {
    this(gameRoot, isolation, source, "");
  }

  public LauncherSettings(String gameRoot, IsolationMode isolation) {
    this(gameRoot, isolation, DownloadSource.AUTO);
  }
}
