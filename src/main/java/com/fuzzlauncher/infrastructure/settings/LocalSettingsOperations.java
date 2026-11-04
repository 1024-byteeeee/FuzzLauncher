package com.fuzzlauncher.infrastructure.settings;

import com.fuzzlauncher.application.model.SettingsSaved;
import com.fuzzlauncher.application.port.InstanceRepository;
import com.fuzzlauncher.application.port.SettingsOperations;
import com.fuzzlauncher.application.port.SettingsRepository;
import com.fuzzlauncher.application.task.TaskContext;
import com.fuzzlauncher.domain.instance.GameInstance;
import com.fuzzlauncher.domain.settings.LauncherSettings;
import com.fuzzlauncher.infrastructure.filesystem.BaseVersions;
import com.fuzzlauncher.infrastructure.instance.InstanceCatalog;
import com.fuzzlauncher.infrastructure.persistence.JsonFiles;
import com.fuzzlauncher.infrastructure.platform.LauncherLog;
import com.fuzzlauncher.infrastructure.platform.LauncherPaths;
import com.fuzzlauncher.infrastructure.recovery.OperationJournal;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class LocalSettingsOperations implements SettingsOperations {
    private final SettingsRepository settingsStore;
    private final InstanceRepository repository;
    private final BaseVersions baseVersions;
    private final OperationJournal.Store recovery;

    public LocalSettingsOperations(SettingsRepository settingsStore, InstanceRepository repository, BaseVersions baseVersions,
        OperationJournal.Store recovery) {
        this.settingsStore = settingsStore;
        this.repository = repository;
        this.baseVersions = baseVersions;
        this.recovery = recovery;
    }

    private Path dataDirectory() {
        return settingsStore.directory();
    }

    public boolean sameDirectory(Path first, Path second) throws IOException {
        return GameRootMigration.sameDirectory(first, second);
    }

    public SettingsSaved save(LauncherSettings previous, LauncherSettings config, boolean migrate, TaskContext context) throws Exception {
        Path previousRoot = Path.of(previous.gameRoot()).toAbsolutePath().normalize();
        Path targetRoot = Path.of(config.gameRoot()).toAbsolutePath().normalize();
        boolean rootChanged = !sameDirectory(targetRoot, previousRoot);

        if (java.nio.file.Files.exists(targetRoot) && !java.nio.file.Files.isDirectory(targetRoot)) {
            throw new IOException("游戏根目录不是文件夹");
        }
        List<GameInstance> oldInstances = repository.findAll();
        if (rootChanged) {
            for (GameInstance item : oldInstances) {
                InstanceCatalog.remember(previousRoot, item);
            }
        }
        Path requested = config.dataDirectory().isBlank() ? dataDirectory() : Path.of(config.dataDirectory());
        OperationJournal operation = recovery != null
            ? recovery.begin(rootChanged && migrate ? "迁移游戏目录" : "保存启动器设置", previousRoot, targetRoot, DataLocations.bootstrapDirectory())
            : null;
        try {
            try (
                GameRootMigration migration = rootChanged && migrate
                    ? GameRootMigration.prepare(previousRoot, targetRoot,
                        List.of(dataDirectory(), LauncherPaths.directory(), DataLocations.bootstrapDirectory()), context, operation)
                    : null) {
                Files.createDirectories(targetRoot);
                List<GameInstance> available = rootChanged
                    ? InstanceCatalog.scan(targetRoot, migrate ? oldInstances : List.of(), baseVersions)
                    : oldInstances;
                context.checkCancelled();
                try {
                    if (operation != null) {
                        operation.configuration(dataDirectory().resolve("instances.json"), Map.of("instances", available),
                            Map.of("instances", oldInstances));
                        operation.configuration(dataDirectory().resolve("settings.json"), config, previous);
                        Path pointer = DataLocations.bootstrapDirectory().resolve("data-location.json");
                        boolean dataChanged = !requested.toAbsolutePath().normalize().equals(dataDirectory().toAbsolutePath().normalize());
                        if (dataChanged || Files.exists(pointer)) {
                            com.google.gson.JsonObject location = new com.google.gson.JsonObject();
                            location.addProperty("directory", requested.toAbsolutePath().normalize().toString());
                            if (dataChanged) {
                                location.addProperty("previous", dataDirectory().toAbsolutePath().normalize().toString());
                            }
                            operation.file(pointer, JsonFiles.GSON.toJson(location).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        }
                    }
                    if (rootChanged) {
                        repository.replaceAll(available);
                    }
                    settingsStore.save(config);
                    DataLocations.configure(DataLocations.bootstrapDirectory(), dataDirectory(), requested);
                } catch (IOException | RuntimeException ex) {
                    try {
                        if (rootChanged) {
                            repository.replaceAll(oldInstances);
                        }
                        settingsStore.save(previous);
                    } catch (IOException rollback) {
                        ex.addSuppressed(rollback);
                    }
                    throw ex;
                }
                if (operation != null) {
                    operation.commit();
                }
                String warning = "";
                if (migration != null) {
                    migration.commit();
                    context.report("正在清理旧游戏目录", -1);
                    try {
                        migration.cleanSource();
                    } catch (IOException ex) {
                        warning = "游戏文件已完整迁移到新目录，但部分旧文件未能清理，请检查后手动清理：" + ex.getMessage();
                        LauncherLog.LOGGER.warning(warning);
                    }
                }
                return new SettingsSaved(available, warning);
            }
        } finally {
            if (operation != null) {
                operation.recover();
            }
        }
    }
}
