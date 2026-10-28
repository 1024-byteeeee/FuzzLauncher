package com.fuzzlauncher.domain;

public record LauncherSettings(
    String gameRoot, IsolationMode isolation, DownloadSource downloadSource) {
  public LauncherSettings {
    if (downloadSource == null) downloadSource = DownloadSource.AUTO;
  }

  public LauncherSettings(String gameRoot, IsolationMode isolation) {
    this(gameRoot, isolation, DownloadSource.AUTO);
  }
}
