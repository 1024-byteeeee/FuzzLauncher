package com.fuzzlauncher.domain;

public enum IsolationMode {
  ALL("全部隔离"),
  MODDED("仅模组版本隔离"),
  SHARED("不隔离");
  private final String label;

  IsolationMode(String label) {
    this.label = label;
  }

  @Override
  public String toString() {
    return label;
  }
}
