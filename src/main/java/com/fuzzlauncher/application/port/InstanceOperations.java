package com.fuzzlauncher.application.port;

import com.fuzzlauncher.application.task.TaskContext;
import com.fuzzlauncher.domain.instance.GameInstance;
import com.fuzzlauncher.domain.settings.LauncherSettings;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface InstanceOperations {
    String initialize(LauncherSettings settings, List<GameInstance> instances);

    GameInstance update(Path root, GameInstance instance) throws IOException;

    void delete(Path root, GameInstance instance, TaskContext task) throws IOException;

    List<GameInstance> refresh(Path root) throws IOException;

    void prepareDirectory(Path root, GameInstance instance) throws IOException;

    boolean serverPack(Path root, GameInstance instance) throws IOException;
}
