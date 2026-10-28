package com.fuzzlauncher.domain;

import java.util.Objects;

public record GameInstance(
    String id,
    String name,
    String gameVersion,
    LoaderType loader,
    String loaderVersion,
    IsolationOverride isolation,
    int memoryMb,
    String javaPath) {
  public GameInstance {
    javaPath = javaPath == null ? "" : javaPath.strip();
    if (id == null || !id.matches("[a-zA-Z0-9._-]+") || id.equals(".") || id.equals(".."))
      throw new IllegalArgumentException("非法实例 ID");
    if (name == null || name.isBlank() || name.length() > 80)
      throw new IllegalArgumentException("实例名称应为 1–80 个字符");
    Objects.requireNonNull(gameVersion);
    if (gameVersion.isBlank()) throw new IllegalArgumentException("游戏版本不能为空");
    Objects.requireNonNull(loader);
    if (loader != LoaderType.VANILLA && (loaderVersion == null || loaderVersion.isBlank()))
      throw new IllegalArgumentException("缺少加载器版本");
    Objects.requireNonNull(isolation);
    if (memoryMb < 512 || memoryMb > 65536)
      throw new IllegalArgumentException("内存应在 512–65536 MB 之间");
  }

  public GameInstance(
      String id,
      String name,
      String gameVersion,
      LoaderType loader,
      String loaderVersion,
      IsolationOverride isolation,
      int memoryMb) {
    this(id, name, gameVersion, loader, loaderVersion, isolation, memoryMb, "");
  }

  @Override
  public String toString() {
    return name;
  }

  public GameInstance withSettings(String newName, IsolationOverride mode, int memory) {
    return withSettings(newName, mode, memory, javaPath);
  }

  public GameInstance withSettings(
      String newName, IsolationOverride mode, int memory, String javaPath) {
    return new GameInstance(
        id, newName.strip(), gameVersion, loader, loaderVersion, mode, memory, javaPath);
  }
}
