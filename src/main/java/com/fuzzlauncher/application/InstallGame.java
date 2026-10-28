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
    if (loader != LoaderType.VANILLA && (loaderVersion == null || loaderVersion.isBlank()))
      throw new IllegalArgumentException("请选择兼容的加载器版本");
    String id = "fuzz-" + UUID.randomUUID();
    GameInstance instance =
        new GameInstance(
            id, name.strip(), game.id(), loader, loaderVersion, IsolationOverride.INHERIT, 4096);
    InstanceDirectories.checkAvailable(root, instance, instances.findAll());
    Files.createDirectories(root);
    Path staging = Files.createTempDirectory(root.resolve(createStagingRoot(root)), "install-");
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
    if (!profile.has("inheritsFrom")) profile.addProperty("inheritsFrom", game.id());
    Path stagedVersion = staging.resolve("profile");
    Files.createDirectories(stagedVersion);
    JsonFiles.write(stagedVersion.resolve(InstanceDirectories.PROFILE), profile);
    // Resolve and verify the complete manifest before publishing the instance.
    Path pending = InstanceDirectories.directory(root, instance);
    InstanceDirectories.checkAvailable(root, instance, instances.findAll());
    boolean createdDirectory = !Files.exists(pending);
    if (createdDirectory) Files.move(stagedVersion, pending);
    else
      Files.move(
          stagedVersion.resolve(InstanceDirectories.PROFILE),
          pending.resolve(InstanceDirectories.PROFILE));
    boolean committed = false;
    try {
      files.prepare(root, resolver.resolveInstance(root, instance), task);
      task.checkCancelled();
      instances.save(instance);
      committed = true;
      task.report("安装完成: " + name, 1);
      return instance;
    } finally {
      if (!committed) {
        // Preserve diagnostic files and keep incomplete versions outside the visible repository.
        if (createdDirectory) Files.move(pending, staging.resolve("incomplete-profile"));
        else {
          Path incomplete = Files.createDirectory(staging.resolve("incomplete-profile"));
          Files.move(
              pending.resolve(InstanceDirectories.PROFILE),
              incomplete.resolve(InstanceDirectories.PROFILE));
        }
      }
    }
  }

  private static String createStagingRoot(Path root) throws IOException {
    Files.createDirectories(root.resolve(".fuzz-staging"));
    return ".fuzz-staging";
  }
}
