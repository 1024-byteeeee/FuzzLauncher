package com.fuzzlauncher.application.port;

import com.fuzzlauncher.application.model.RunningGame;
import com.fuzzlauncher.application.task.TaskContext;
import com.fuzzlauncher.domain.account.Account;
import com.fuzzlauncher.domain.instance.GameInstance;
import com.fuzzlauncher.domain.settings.LauncherSettings;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public interface GameLauncher {
    RunningGame execute(LauncherSettings settings, GameInstance instance, Account account, TaskContext task) throws Exception;

    void stop(RunningGame game) throws IOException;

    void followLog(RunningGame game, BooleanSupplier cancelled, Consumer<LogSnapshot> output) throws IOException, InterruptedException;

    JvmArgumentPreview previewJvm(Path root, GameInstance instance) throws Exception;

    record LogSnapshot(String text, boolean truncated) {
    }
}
