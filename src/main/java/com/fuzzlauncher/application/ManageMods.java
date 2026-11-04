package com.fuzzlauncher.application;

import com.fuzzlauncher.domain.*;
import com.fuzzlauncher.infrastructure.mod.ModFiles;

import java.nio.file.Path;
import java.util.List;

/** Resolves the same game directory as launch before delegating local mod operations. */
public final class ManageMods {
    private final ModFiles files = new ModFiles();

    public Path directory(LauncherSettings settings, GameInstance instance) {
        return new IsolationPolicy().gameDirectory(Path.of(settings.gameRoot()), instance, settings.isolation()).resolve("mods")
            .toAbsolutePath().normalize();
    }

    public List<ModFile> scan(Path directory, TaskContext task) throws Exception {
        return files.scan(directory, task);
    }

    public ModFiles.Report importFiles(Path directory, List<Path> sources, boolean renameCollisions, TaskContext task) throws Exception {
        return files.importFiles(directory, sources, renameCollisions, task);
    }

    public ModFiles.Report setEnabled(Path directory, List<ModFile> selected, boolean enabled, TaskContext task) throws Exception {
        return files.setEnabled(directory, selected, enabled, task);
    }

    public ModFiles.Report delete(Path directory, List<ModFile> selected, TaskContext task) throws Exception {
        return files.delete(directory, selected, task);
    }
}
