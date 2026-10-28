package com.fuzzlauncher.domain;

public enum IsolationOverride {
  INHERIT("跟随全局"),
  ISOLATED("独立目录"),
  SHARED("共享目录");
  private final String label;

  IsolationOverride(String label) {
    this.label = label;
  }

  @Override
  public String toString() {
    return label;
  }
}
