package com.fuzzlauncher.domain;

public enum DownloadSource {
  AUTO("自动选择", "https://bmclapi2.bangbang93.com/"),
  OFFICIAL("官方源", "https://www.minecraft.net/"),
  @com.google.gson.annotations.SerializedName(
      value = "MIRROR",
      alternate = {"BMCLAPI", "NJU"})
  MIRROR("镜像源（BMCLAPI）", "https://bmclapi2.bangbang93.com/");

  private final String label;
  private final String website;

  DownloadSource(String label, String website) {
    this.label = label;
    this.website = website;
  }

  public String website() {
    return website;
  }

  @Override
  public String toString() {
    return label;
  }
}
