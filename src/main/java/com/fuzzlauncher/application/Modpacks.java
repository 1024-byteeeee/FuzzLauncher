package com.fuzzlauncher.application;

import com.fuzzlauncher.domain.*;
import com.fuzzlauncher.infrastructure.*;
import com.fuzzlauncher.infrastructure.ModpackArchive.Info;
import com.fuzzlauncher.infrastructure.game.VersionCatalog;
import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Imports into isolated instances and synchronizes only files owned by a server pack. */
public final class Modpacks {
  private final HttpTransport http;

  @FunctionalInterface
  public interface Versions {
    List<GameVersion> get() throws Exception;
  }

  private final Versions versions;
  private final InstallGame installer;
  private final InstanceRepository repository;

  public Modpacks(
      HttpTransport http,
      VersionCatalog catalog,
      InstallGame installer,
      InstanceRepository repository) {
    this(http, catalog::games, installer, repository);
  }

  public Modpacks(
      HttpTransport http, Versions versions, InstallGame installer, InstanceRepository repository) {
    this.http = http;
    this.versions = versions;
    this.installer = installer;
    this.repository = repository;
  }

  public GameInstance importPack(
      Path root, Path archive, String name, boolean optional, TaskContext task) throws Exception {
    return importPack(root, archive, name, optional, Set.of(), task);
  }

  public GameInstance importPack(
      Path root,
      Path archive,
      String name,
      boolean optional,
      Set<String> excludedOptional,
      TaskContext task)
      throws Exception {
    Info info = ModpackArchive.inspect(archive);
    // Validate the name and collision before downloading or touching an existing instance.
    GameInstance candidate = instance(info, "pack-" + UUID.randomUUID(), name, null);
    if (candidate.name().equalsIgnoreCase(candidate.gameVersion()))
      throw new IOException("整合包实例名称不能与共享的游戏版本目录同名，请换一个名称");
    InstanceDirectories.checkAvailable(root, candidate, repository.findAll());
    if (Files.exists(InstanceDirectories.directory(root, candidate)))
      throw new IOException("目标实例目录已存在，请换一个名称");
    GameVersion game = game(info.game());
    Path workRoot = root.toAbsolutePath().normalize().resolve(".fuzz-staging");
    Files.createDirectories(workRoot);
    Path work = Files.createTempDirectory(workRoot, "pack-import-");
    try {
      ModpackArchive.extract(
          archive, info, work.resolve("content"), optional, excludedOptional, http, task);
      task.checkCancelled();
      return installer.execute(
          root,
          game,
          candidate.loader(),
          candidate.loaderVersion(),
          name,
          IsolationOverride.ISOLATED,
          work.resolve("content"),
          task);
    } finally {
      ModpackArchive.deleteWorkspace(work, workRoot);
    }
  }

  private GameVersion game(String id) throws Exception {
    return versions.get().stream()
        .filter(g -> g.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new IOException("官方版本清单中没有 Minecraft " + id));
  }

  private static GameInstance instance(Info info, String id, String name, GameInstance previous) {
    String loader = info.loaderVersion();
    if ((info.loader() == LoaderType.FORGE
            || info.loader() == LoaderType.NEOFORGE && info.game().equals("1.20.1"))
        && !loader.startsWith(info.game() + "-")) loader = info.game() + "-" + loader;
    return new GameInstance(
        id,
        name,
        info.game(),
        info.loader(),
        loader,
        IsolationOverride.ISOLATED,
        previous == null ? 4096 : previous.memoryMb(),
        previous == null ? "" : previous.javaPath(),
        previous == null ? 4096 : previous.minMemoryMb(),
        previous == null ? List.of() : previous.jvmArguments(),
        previous == null ? List.of() : previous.gameArguments());
  }

  public GameInstance update(Path root, GameInstance current, TaskContext task) throws Exception {
    Path folder = InstanceDirectories.directory(root, current);
    Path stateFile = folder.resolve(ModpackArchive.STATE);
    if (!Files.isRegularFile(stateFile)) return current;
    JsonObject state = JsonFiles.read(stateFile);
    if (!"SERVER".equals(JsonFiles.string(state, "format", ""))) return current;
    if (!current
        .id()
        .equals(
            JsonFiles.string(
                JsonFiles.read(folder.resolve(InstanceDirectories.PROFILE)), "id", "")))
      throw new IOException("服务器整合包实例身份不一致，拒绝更新");
    if (current.isolation() != IsolationOverride.ISOLATED)
      throw new IOException("服务器整合包必须保持独立游戏目录，请恢复版本隔离");
    Info local = ModpackArchive.parse(ModpackFormat.SERVER, state.getAsJsonObject("manifest"), "");
    String base = ModpackArchive.baseUrl(JsonFiles.string(local.manifest(), "fileApi", ""));
    task.report("检查服务器整合包更新", -1);
    JsonObject remote = http.getJson(base + "/server-manifest.json");
    Info info = ModpackArchive.parse(ModpackFormat.SERVER, remote, "");
    if (!base.equals(ModpackArchive.baseUrl(JsonFiles.string(remote, "fileApi", ""))))
      throw new IOException("服务器更新地址发生变化，请重新导入由管理员提供的整合包");
    GameInstance updated = instance(info, current.id(), current.name(), current);
    Map<String, String> before = managed(local.manifest()), after = managed(remote);
    List<String> changes = new ArrayList<>(), removals = new ArrayList<>();
    Map<String, String> snapshots = new HashMap<>();
    for (var entry : after.entrySet()) {
      Path file = secureTarget(folder, entry.getKey());
      if (entry.getKey().toLowerCase(Locale.ROOT).startsWith("mods/")
          && (Files.exists(file.resolveSibling(file.getFileName() + ".disabled"))
              || Files.exists(file.resolveSibling(file.getFileName() + ".old")))) continue;
      boolean exists = Files.exists(file, LinkOption.NOFOLLOW_LINKS);
      String actual = exists ? ModpackArchive.digest(file, "SHA-1") : null;
      String previous = before.get(entry.getKey());
      if (!Objects.equals(actual, entry.getValue())
          && (!exists || previous != null && previous.equalsIgnoreCase(actual))) {
        changes.add(entry.getKey());
        snapshots.put(entry.getKey(), actual);
      }
    }
    for (var entry : before.entrySet()) {
      Path file = secureTarget(folder, entry.getKey());
      if (!after.containsKey(entry.getKey())
          && Files.isRegularFile(file)
          && ModpackArchive.digest(file, "SHA-1").equalsIgnoreCase(entry.getValue())) {
        removals.add(entry.getKey());
        snapshots.put(entry.getKey(), entry.getValue());
      }
    }
    boolean components =
        !current.gameVersion().equals(updated.gameVersion())
            || current.loader() != updated.loader()
            || !Objects.equals(current.loaderVersion(), updated.loaderVersion());
    if (changes.isEmpty() && removals.isEmpty() && !components && remote.equals(local.manifest()))
      return current;
    Path workRoot = root.toAbsolutePath().normalize().resolve(".fuzz-staging");
    Files.createDirectories(workRoot);
    Path work = Files.createTempDirectory(workRoot, "pack-update-");
    java.util.concurrent.atomic.AtomicBoolean preserveBackup =
        new java.util.concurrent.atomic.AtomicBoolean();
    try {
      Path staged = work.resolve("new"), backup = work.resolve("backup");
      Files.createDirectories(staged);
      for (String path : changes) {
        task.checkCancelled();
        Path target = JsonFiles.safeResolve(staged, path);
        http.download(ModpackArchive.fileUrl(base, path), target, after.get(path), task);
        ModpackArchive.verify(target, after.get(path), "SHA-1");
      }
      JsonFiles.write(
          staged.resolve(ModpackArchive.STATE), Map.of("format", "SERVER", "manifest", remote));
      List<String> publish = new ArrayList<>(changes);
      publish.add(ModpackArchive.STATE);
      if (components) {
        JsonFiles.write(
            staged.resolve(InstanceDirectories.PROFILE),
            installer.prepareModpackProfile(root, updated, game(updated.gameVersion()), task));
        publish.add(InstanceDirectories.PROFILE);
      }
      List<String> backedUp = new ArrayList<>(), installed = new ArrayList<>();
      task.commit(
          () -> {
            try {
              for (var snapshot : snapshots.entrySet()) {
                Path file = secureTarget(folder, snapshot.getKey());
                String actual = Files.exists(file) ? ModpackArchive.digest(file, "SHA-1") : null;
                if (!Objects.equals(actual, snapshot.getValue()))
                  throw new IOException("文件在更新期间被修改，请重试：" + snapshot.getKey());
              }
              for (String path : concat(publish, removals)) {
                Path target = secureTarget(folder, path);
                if (Files.exists(target)) {
                  Path saved = JsonFiles.safeResolve(backup, path);
                  Files.createDirectories(saved.getParent());
                  Files.move(target, saved);
                  backedUp.add(path);
                }
              }
              for (String path : publish) {
                Path target = secureTarget(folder, path);
                Files.createDirectories(target.getParent());
                Files.move(JsonFiles.safeResolve(staged, path), target);
                installed.add(path);
              }
              repository.save(updated);
            } catch (Exception ex) {
              for (String path : installed) {
                try {
                  Files.deleteIfExists(JsonFiles.safeResolve(folder, path));
                } catch (IOException rollback) {
                  ex.addSuppressed(rollback);
                  preserveBackup.set(true);
                }
              }
              for (String path : backedUp) {
                try {
                  Path target = JsonFiles.safeResolve(folder, path);
                  Files.createDirectories(target.getParent());
                  Files.move(JsonFiles.safeResolve(backup, path), target);
                } catch (IOException rollback) {
                  ex.addSuppressed(rollback);
                  preserveBackup.set(true);
                }
              }
              throw ex;
            }
          });
      task.report("服务器整合包已更新到 " + info.version() + "，本地修改已保留", 1);
      return updated;
    } finally {
      if (!preserveBackup.get()) ModpackArchive.deleteWorkspace(work, workRoot);
      else LauncherLog.LOGGER.warning("整合包回滚未完成，已保留备份：" + work);
    }
  }

  private static List<String> concat(List<String> first, List<String> second) {
    List<String> result = new ArrayList<>(first);
    result.addAll(second);
    return result;
  }

  private static Map<String, String> managed(JsonObject manifest) throws IOException {
    Map<String, String> result = new LinkedHashMap<>();
    Set<String> insensitive = new HashSet<>();
    for (JsonElement element : ModpackArchive.array(manifest, "files")) {
      JsonObject file = element.getAsJsonObject();
      String path = JsonFiles.string(file, "path", ""), hash = JsonFiles.string(file, "hash", "");
      ModpackArchive.validateContent(path);
      ModpackArchive.hashValue(hash, 40);
      if (!insensitive.add(path.toLowerCase(Locale.ROOT)))
        throw new IOException("服务器清单路径重复：" + path);
      result.put(path, hash.toLowerCase(Locale.ROOT));
    }
    return result;
  }

  private static Path secureTarget(Path root, String path) throws IOException {
    Path target = JsonFiles.safeResolve(root, path);
    for (Path parent = target;
        parent != null && parent.startsWith(root);
        parent = parent.getParent())
      if (Files.isSymbolicLink(parent)
          || Files.exists(parent) && !parent.toRealPath().startsWith(root.toRealPath()))
        throw new IOException("整合包目录包含外部链接，拒绝覆盖：" + path);
    return target;
  }
}
