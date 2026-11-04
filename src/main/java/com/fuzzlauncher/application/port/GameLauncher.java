package com.fuzzlauncher.application.port;

import com.fuzzlauncher.application.model.RunningGame;
import com.fuzzlauncher.application.task.TaskContext;
import com.fuzzlauncher.domain.account.Account;
import com.fuzzlauncher.domain.instance.GameInstance;
import com.fuzzlauncher.domain.settings.LauncherSettings;

import java.nio.file.Path;

public interface GameLauncher {
    RunningGame execute(LauncherSettings settings, GameInstance instance, Account account, TaskContext task) throws Exception;

    JvmArgumentPreview previewJvm(Path root, GameInstance instance) throws Exception;
}
