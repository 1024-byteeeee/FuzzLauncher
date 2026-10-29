package com.fuzzlauncher.application;

import com.fuzzlauncher.domain.*;
import com.fuzzlauncher.infrastructure.*;
import com.fuzzlauncher.infrastructure.game.*;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class InstallGame {
  private final InstanceRepository instances;
  private final GameFiles files;
  private final VersionResolver resolver;
  private final Map<LoaderType, LoaderInstaller> installers;

  public InstallGame(
      InstanceRepository instances,
      GameFiles files,
      VersionResolver resolver,
      Map<LoaderType, LoaderInstaller> installers) {
    this.instances = instances;
    this.files = files;
    this.resolver = resolver;
    this.installers = Map.copyOf(installers);
  }

  public GameInstance execute(
      Path root,
      GameVersion game,
      LoaderType loader,
      String loaderVersion,
      String name,
      TaskContext task)
      throws Exception {
    return execute(root, game, loader, loaderVersion, name, IsolationOverride.INHERIT, null, task);
  }

  public GameInstance execute(Path root, GameVersion game, LoaderType loader, String loaderVersion,
      String name, IsolationOverride isolation, Path content, TaskContext task) throws Exception {
    if (loader != LoaderType.VANILLA && (loaderVersion == null || loaderVersion.isBlank()))
      throw new IllegalArgumentException("请选择兼容的加载器版本");
    String id = "fuzz-" + UUID.randomUUID();
    GameInstance instance =
        new GameInstance(
            id,
            name.strip(),
            game.id(),
            loader,
            loaderVersion,
            isolation,
            4096,
            "",
            4096,
            List.of(),
            List.of());
    InstanceDirectories.checkAvailable(root, instance, instances.findAll());
    root = root.toAbsolutePath().normalize();
    Files.createDirectories(root);
    Path versions = root.resolve("versions");
    Path base = JsonFiles.safeResolve(versions, game.id());
    boolean baseExisted = Files.exists(base, LinkOption.NOFOLLOW_LINKS);
    boolean baseInUse =
        instances.findAll().stream().anyMatch(item -> item.gameVersion().equals(game.id()));
    Path pending = InstanceDirectories.directory(root, instance);
    Path stagingRoot = root.resolve(".fuzz-staging");
    Files.createDirectories(stagingRoot);
    Path staging = Files.createTempDirectory(stagingRoot, "install-");
    boolean published = false, createdDirectory = false, committed = false;
    Exception failure = null;
    try {
      task.checkCancelled();
      files.installVanilla(root, game, task);
      JsonObject profile;
      if (loader == LoaderType.VANILLA) {
        profile = new JsonObject();
        profile.addProperty("inheritsFrom", game.id());
      } else
        profile =
            Objects.requireNonNull(installers.get(loader), "缺少加载器适配器")
                .install(root, staging, game, loaderVersion, task);
      task.checkCancelled();
      profile.addProperty("id", id);
      profile.add("fuzzInstance", JsonFiles.GSON.toJsonTree(instance));
      if (!profile.has("inheritsFrom")) profile.addProperty("inheritsFrom", game.id());
      Path stagedVersion = staging.resolve("profile");
      Files.createDirectories(stagedVersion);
      if (content != null) {
        try (var paths = Files.walk(content)) {
          for (Path source : paths.toList()) {
            task.checkCancelled();
            if (Files.isSymbolicLink(source)) throw new IOException("整合包包含目录链接");
            Path target = stagedVersion.resolve(content.relativize(source));
            if (Files.isDirectory(source)) Files.createDirectories(target);
            else Files.copy(source, target);
          }
        }
      }
      JsonFiles.write(stagedVersion.resolve(InstanceDirectories.PROFILE), profile);
      InstanceDirectories.checkAvailable(root, instance, instances.findAll());
      createdDirectory = !Files.exists(pending);
      if (createdDirectory) Files.move(stagedVersion, pending);
      else if (content == null)
        Files.move(
            stagedVersion.resolve(InstanceDirectories.PROFILE),
            pending.resolve(InstanceDirectories.PROFILE));
      else throw new IOException("整合包实例目录已存在，请使用不同名称");
      published = true;
      files.prepare(root, resolver.resolveInstance(root, instance), task);
      task.checkCancelled();
      task.commit(() -> instances.save(instance));
      committed = true;
      task.report("安装完成: " + name, 1);
      return instance;
    } catch (Exception ex) {
      failure = ex;
      throw ex;
    } finally {
      // Cleanup must run even on an interrupted installation thread, without checking cancellation.
      List<Path> discard = new ArrayList<>();
      if (!committed) {
        if (published)
          discard.add(createdDirectory ? pending : pending.resolve(InstanceDirectories.PROFILE));
        if (!baseExisted && !baseInUse) discard.add(base);
      }
      discard.add(staging);
      for (Path path : discard) {
        try {
          deleteInstallationPath(root, path);
        } catch (IOException ex) {
          LauncherLog.LOGGER.warning("清理安装残留失败：" + ex.getMessage());
          if (failure != null) failure.addSuppressed(ex);
        }
      }
      try {
        Files.deleteIfExists(stagingRoot);
      } catch (DirectoryNotEmptyException ignored) {
        /* Other installation workspaces are not ours. */
      } catch (IOException ex) {
        LauncherLog.LOGGER.warning("清理安装临时目录失败：" + ex.getMessage());
      }
    }
  }

  public GameInstance reinstallLoader(Path root, GameInstance instance, String version, TaskContext task)
      throws Exception {
    return new ReinstallLoader(instances, files, resolver, installers).execute(root, instance, version, task);
  }

  public JsonObject prepareModpackProfile(Path root, GameInstance instance, GameVersion game,
      TaskContext task) throws Exception {
    Path stagingRoot = root.toAbsolutePath().normalize().resolve(".fuzz-staging");
    Files.createDirectories(stagingRoot);
    Path staging = Files.createTempDirectory(stagingRoot, "pack-components-");
    try {
      files.installVanilla(root, game, task);
      JsonObject profile = instance.loader() == LoaderType.VANILLA ? new JsonObject()
          : Objects.requireNonNull(installers.get(instance.loader()), "缺少加载器适配器")
              .install(root, staging, game, instance.loaderVersion(), task);
      profile.addProperty("id", instance.id());
      profile.addProperty("inheritsFrom", game.id());
      profile.add("fuzzInstance", JsonFiles.GSON.toJsonTree(instance));
      files.prepare(root, resolver.resolveProfile(root, profile), task);
      return profile;
    } finally { ModpackArchive.deleteWorkspace(staging, stagingRoot); }
  }

  private static void deleteInstallationPath(Path root, Path target) throws IOException {
    target = target.toAbsolutePath().normalize();
    if (target.equals(root) || !target.startsWith(root)) throw new IOException("拒绝清理游戏目录之外的路径");
    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return;
    Path realRoot = root.toRealPath();
    if (!target.getParent().toRealPath().startsWith(realRoot))
      throw new IOException("安装路径指向游戏目录之外");
    Files.walkFileTree(
        target,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(
              Path directory, java.nio.file.attribute.BasicFileAttributes attributes)
              throws IOException {
            if (!directory.toRealPath().startsWith(realRoot)) throw new IOException("安装目录包含外部目录链接");
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(
              Path file, java.nio.file.attribute.BasicFileAttributes attributes)
              throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path directory, IOException error)
              throws IOException {
            if (error != null) throw error;
            Files.delete(directory);
            return FileVisitResult.CONTINUE;
          }
        });
  }
}
