package com.fuzzlauncher.application;

import com.fuzzlauncher.domain.GameVersion;
import com.google.gson.JsonObject;
import java.nio.file.Path;

/** Adapter boundary: each loader owns its installation protocol. */
public interface LoaderInstaller {
  JsonObject install(
      Path root, Path staging, GameVersion game, String loaderVersion, TaskContext task)
      throws Exception;
}
