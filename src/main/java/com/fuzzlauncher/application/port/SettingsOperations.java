package com.fuzzlauncher.application.port;

import com.fuzzlauncher.application.model.FileOperationResult;
import com.fuzzlauncher.application.model.SettingsSaved;
import com.fuzzlauncher.application.task.TaskContext;
import com.fuzzlauncher.domain.settings.LauncherSettings;

import java.io.IOException;
import java.nio.file.Path;

public interface SettingsOperations {
    boolean sameDirectory(Path first, Path second) throws IOException;

    FileOperationResult clearLogs(TaskContext task) throws IOException;

    SettingsSaved save(LauncherSettings previous, LauncherSettings requested, boolean migrate, TaskContext task) throws Exception;
}
