package com.fuzzlauncher.infrastructure.instance;

import com.fuzzlauncher.application.port.InstanceOperations;
import com.fuzzlauncher.application.port.InstanceRepository;
import com.fuzzlauncher.application.task.TaskContext;
import com.fuzzlauncher.domain.instance.GameInstance;
import com.fuzzlauncher.domain.settings.LauncherSettings;
import com.fuzzlauncher.infrastructure.filesystem.BaseVersions;
import com.fuzzlauncher.infrastructure.filesystem.InstanceDirectories;
import com.fuzzlauncher.infrastructure.game.runtime.JavaRuntimeManager;
import com.fuzzlauncher.infrastructure.modpack.ModpackArchive;
import com.fuzzlauncher.infrastructure.persistence.JsonFiles;
import com.fuzzlauncher.infrastructure.platform.LauncherLog;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;

public final class LocalInstanceOperations implements InstanceOperations {
    private final InstanceRepository repository;
    private final BaseVersions baseVersions;

    public LocalInstanceOperations(InstanceRepository repository, BaseVersions baseVersions) {
        this.repository = repository;
        this.baseVersions = baseVersions;
    }

    public String initialize(LauncherSettings settings, List<GameInstance> instances) {
        String warning = "";
        for (GameInstance instance : instances) {
            try {
                InstanceDirectories.migrate(Path.of(settings.gameRoot()), instance);
                baseVersions.migrate(Path.of(settings.gameRoot()), instance.gameVersion());
                InstanceCatalog.remember(Path.of(settings.gameRoot()), instance);
            } catch (IOException ex) {
                warning = "实例目录迁移未完成：" + ex.getMessage();
            }
        }
        return warning;
    }

    public GameInstance update(Path root, GameInstance instance) throws IOException {
        if (!instance.javaPath().isBlank()) {
            JavaRuntimeManager.configuredExecutable(instance.javaPath());
        }
        GameInstance previous = repository.findAll().stream().filter(item -> item.id().equals(instance.id())).findFirst()
            .orElseThrow(() -> new IOException("实例已不存在"));
        InstanceDirectories.save(root, previous, instance, repository);
        try {
            InstanceCatalog.remember(root, instance);
        } catch (IOException ex) {
            LauncherLog.LOGGER.warning("实例目录配置备份失败：" + ex.getMessage());
        }
        return instance;
    }

    public void delete(Path root, GameInstance instance, TaskContext task) throws IOException {
        InstanceCatalog.delete(root, instance, repository, task);
    }

    public List<GameInstance> refresh(Path root) throws IOException {
        return InstanceCatalog.refresh(root, repository, baseVersions);
    }

    public void prepareDirectory(Path root, GameInstance instance) throws IOException {
        InstanceDirectories.migrate(root, instance);
        InstanceDirectories.checkAvailable(root, instance, repository.findAll());
    }

    public boolean serverPack(Path root, GameInstance instance) throws IOException {
        Path file = InstanceDirectories.directory(root, instance).resolve(ModpackArchive.STATE);
        return Files.isRegularFile(file) && "SERVER".equals(JsonFiles.string(JsonFiles.read(file), "format", ""));
    }
}
