package com.fuzzlauncher.domain;

import java.util.List;
import java.util.Objects;

public record GameInstance(
    String id,
    String name,
    String gameVersion,
    LoaderType loader,
    String loaderVersion,
    IsolationOverride isolation,
    int memoryMb,
    String javaPath,
    int minMemoryMb,
    List<String> jvmArguments,
    List<String> gameArguments) {
  public GameInstance {
    name = name == null ? null : name.strip();
    javaPath = javaPath == null ? "" : javaPath.strip();
    if (id == null || !id.matches("[a-zA-Z0-9._-]+") || id.equals(".") || id.equals(".."))
      throw new IllegalArgumentException("非法实例 ID");
    if (name == null || name.isBlank() || name.length() > 80)
      throw new IllegalArgumentException("实例名称应为 1–80 个字符");
    if (name.equals(".")
        || name.equals("..")
        || name.endsWith(".")
        || name.codePoints().anyMatch(Character::isISOControl)
        || name.matches(".*[<>:\"/\\\\|?*\\p{Cntrl}].*")
        || name.matches("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?"))
      throw new IllegalArgumentException("实例名称不能包含 Windows 文件名禁用字符或保留名称");
    Objects.requireNonNull(gameVersion);
    if (gameVersion.isBlank()) throw new IllegalArgumentException("游戏版本不能为空");
    Objects.requireNonNull(loader);
    if (loader != LoaderType.VANILLA && (loaderVersion == null || loaderVersion.isBlank()))
      throw new IllegalArgumentException("缺少加载器版本");
    Objects.requireNonNull(isolation);
    if (memoryMb < 512 || memoryMb > 65536)
      throw new IllegalArgumentException("最大内存应在 512–65536 MB 之间");
    // Old instance files only stored the maximum; retain their previous 256 MB initial heap.
    if (minMemoryMb == 0) minMemoryMb = 256;
    if (minMemoryMb < 128 || minMemoryMb > 65536)
      throw new IllegalArgumentException("最小内存应在 128–65536 MB 之间");
    if (minMemoryMb > memoryMb) throw new IllegalArgumentException("最小内存不能大于最大内存");
    jvmArguments = jvmArguments == null ? List.of() : List.copyOf(jvmArguments);
    gameArguments = gameArguments == null ? List.of() : List.copyOf(gameArguments);
  }

  public GameInstance(
      String id,
      String name,
      String gameVersion,
      LoaderType loader,
      String loaderVersion,
      IsolationOverride isolation,
      int memoryMb,
      String javaPath) {
    this(id, name, gameVersion, loader, loaderVersion, isolation, memoryMb, javaPath, 256, List.of(), List.of());
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
        id,
        newName.strip(),
        gameVersion,
        loader,
        loaderVersion,
        mode,
        memory,
        javaPath,
        minMemoryMb,
        jvmArguments,
        gameArguments);
  }

  public GameInstance withSettings(
      String newName,
      IsolationOverride mode,
      int minimumMemory,
      int maximumMemory,
      String javaPath) {
    if (minimumMemory < 128) throw new IllegalArgumentException("最小内存应在 128–65536 MB 之间");
    return new GameInstance(
        id,
        newName.strip(),
        gameVersion,
        loader,
        loaderVersion,
        mode,
        maximumMemory,
        javaPath,
        minimumMemory,
        jvmArguments,
        gameArguments);
  }

  public GameInstance withLaunchArguments(
      int minimumMemory, int maximumMemory, List<String> jvm, List<String> game) {
    return new GameInstance(
        id,
        name,
        gameVersion,
        loader,
        loaderVersion,
        isolation,
        maximumMemory,
        javaPath,
        minimumMemory,
        jvm,
        game);
  }
}
