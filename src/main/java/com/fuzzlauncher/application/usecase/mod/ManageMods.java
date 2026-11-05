package com.fuzzlauncher.application.usecase.mod;

import com.fuzzlauncher.application.model.FileOperationResult;
import com.fuzzlauncher.application.port.ModRepository;
import com.fuzzlauncher.application.task.TaskContext;
import com.fuzzlauncher.domain.instance.GameInstance;
import com.fuzzlauncher.domain.instance.IsolationPolicy;
import com.fuzzlauncher.domain.mod.ModFile;
import com.fuzzlauncher.domain.settings.LauncherSettings;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Resolves the same game directory as launch before delegating local mod operations. */
public final class ManageMods {
    private final ModRepository files;

    public ManageMods(ModRepository files) {
        this.files = Objects.requireNonNull(files);
    }

    public Path directory(LauncherSettings settings, GameInstance instance) {
        return new IsolationPolicy().gameDirectory(Path.of(settings.gameRoot()), instance, settings.isolation()).resolve("mods").toAbsolutePath().normalize();
    }

    public List<ModFile> scan(Path directory, TaskContext task) throws Exception {
        return files.scan(directory, task);
    }

    public FileOperationResult importFiles(Path directory, List<Path> sources, boolean renameCollisions, TaskContext task) throws Exception {
        return files.importFiles(directory, sources, renameCollisions, task);
    }

    public FileOperationResult setEnabled(Path directory, List<ModFile> selected, boolean enabled, TaskContext task) throws Exception {
        return files.setEnabled(directory, selected, enabled, task);
    }

    public FileOperationResult delete(Path directory, List<ModFile> selected, TaskContext task) throws Exception {
        return files.delete(directory, selected, task);
    }
}
