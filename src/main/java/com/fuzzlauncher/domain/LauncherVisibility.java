package com.fuzzlauncher.domain;

public enum LauncherVisibility {
  EXIT("游戏启动后结束启动器"),
  HIDE("游戏启动后隐藏启动器"),
  KEEP("保持启动器可见"),
  HIDE_AND_REOPEN("隐藏启动器并在游戏结束后重新打开");

  private final String label;

  LauncherVisibility(String label) {
    this.label = label;
  }

  @Override
  public String toString() {
    return label;
  }
}
