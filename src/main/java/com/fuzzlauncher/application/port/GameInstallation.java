package com.fuzzlauncher.application.port;

import com.fuzzlauncher.application.task.TaskContext;
import com.fuzzlauncher.domain.game.GameVersion;
import com.fuzzlauncher.domain.game.LoaderType;
import com.fuzzlauncher.domain.instance.GameInstance;

import java.nio.file.Path;

public interface GameInstallation {
    GameInstance execute(Path root, GameVersion game, LoaderType loader, String loaderVersion, String name, TaskContext task) throws Exception;

    GameInstance reinstallLoader(Path root, GameInstance instance, String version, TaskContext task) throws Exception;
}
