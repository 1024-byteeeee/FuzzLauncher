package com.fuzzlauncher.infrastructure.game.install;

import com.fuzzlauncher.application.task.TaskContext;
import com.fuzzlauncher.domain.game.GameVersion;
import com.fuzzlauncher.infrastructure.filesystem.BaseVersions;

import com.google.gson.JsonObject;

import java.nio.file.Path;

/** Adapter boundary: each loader owns its installation protocol. */
public interface LoaderInstaller {
    JsonObject install(Path root, Path staging, GameVersion game, String loaderVersion, TaskContext task) throws Exception;

    default JsonObject install(Path root, Path staging, GameVersion game, String loaderVersion, BaseVersions baseVersions, TaskContext task)
        throws Exception {
        return install(root, staging, game, loaderVersion, task);
    }
}
