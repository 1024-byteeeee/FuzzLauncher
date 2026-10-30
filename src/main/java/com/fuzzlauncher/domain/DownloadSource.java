package com.fuzzlauncher.domain;

import com.fuzzlauncher.config.LauncherConfig;

public enum DownloadSource {
    AUTO, OFFICIAL, @com.google.gson.annotations.SerializedName(value = "MIRROR", alternate = { "BMCLAPI", "NJU" })
    MIRROR;

    public String website() {
        return LauncherConfig.get().downloads().sources().get(name()).website();
    }

    @Override
    public String toString() {
        return LauncherConfig.get().downloads().sources().get(name()).label();
    }
}
