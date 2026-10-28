package com.fuzzlauncher.domain;

public record LauncherSettings(
    String gameRoot, IsolationMode isolation, String microsoftClientId) {}
