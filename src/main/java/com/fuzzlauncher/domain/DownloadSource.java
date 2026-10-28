package com.fuzzlauncher.domain;

public enum DownloadSource {
  OFFICIAL("官方源", "https://www.minecraft.net/"),
  BMCLAPI("BMCLAPI 镜像", "https://bmclapi2.bangbang93.com/"),
  NJU("南京大学镜像（依赖库）", "https://mirror.nju.edu.cn/bmclapi/");

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
