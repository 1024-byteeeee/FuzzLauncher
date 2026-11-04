package com.fuzzlauncher.ui.viewmodel;

import com.fuzzlauncher.application.port.SettingsOperations;
import com.fuzzlauncher.application.port.SettingsRepository;
import com.fuzzlauncher.domain.game.GameVersion;
import com.fuzzlauncher.domain.instance.GameInstance;
import com.fuzzlauncher.domain.settings.LauncherSettings;
import com.fuzzlauncher.ui.task.FxTaskCoordinator;

import javafx.beans.property.*;
import javafx.collections.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

public final class SettingsViewModel {
    private final FxTaskCoordinator tasks;
    private final BooleanProperty busy;
    private final StringProperty status, error;
    private final ObjectProperty<LauncherSettings> settings;
    private final ObservableList<GameInstance> instances;
    private final ObjectProperty<GameInstance> selected;
    private final ObservableList<GameVersion> versions;
    private final ObservableList<String> loaderVersions;
    private final BooleanProperty gameRunning;
    private final BooleanProperty savingSettings;
    private final SettingsRepository settingsStore;
    private final SettingsOperations settingsOperations;
    private Runnable restart = () -> {
    };

    SettingsViewModel(PresentationState state, FxTaskCoordinator tasks, SettingsRepository settingsStore,
        SettingsOperations settingsOperations) {
        this.tasks = tasks;
        busy = tasks.busyProperty();
        status = tasks.statusProperty();
        error = tasks.errorProperty();
        settings = state.settings;
        instances = state.instances;
        selected = state.selected;
        versions = state.versions;
        loaderVersions = state.loaderVersions;
        gameRunning = state.gameRunning;
        savingSettings = state.savingSettings;
        this.settingsStore = settingsStore;
        this.settingsOperations = settingsOperations;
    }

    public void onRestartRequired(Runnable action) {
        restart = Objects.requireNonNull(action);
    }

    public boolean sameDirectory(Path first, Path second) throws IOException {
        return settingsOperations.sameDirectory(first, second);
    }

    public void saveSettings(LauncherSettings config) {
        saveSettings(config, false);
    }

    public void saveSettings(LauncherSettings config, boolean migrate) {
        if (busy.get()) {
            return;
        }
        if (gameRunning.get()) {
            error.set("游戏运行中，请先退出游戏再修改启动器设置");
            return;
        }
        LauncherSettings previous = settings.get();
        Path previousRoot = Path.of(previous.gameRoot()).toAbsolutePath().normalize();
        Path targetRoot = Path.of(config.gameRoot()).toAbsolutePath().normalize();
        boolean rootChanged;
        try {
            rootChanged = !settingsOperations.sameDirectory(targetRoot, previousRoot);
        } catch (IOException ex) {
            error.set("无法读取游戏根目录：" + ex.getMessage());
            return;
        }
        savingSettings.set(true);
        tasks.submit("保存启动器设置", context -> settingsOperations.save(previous, config, migrate, context), result -> {
            List<GameInstance> available = result.instances();
            LauncherSettings saved = config;
            boolean sourceChanged = settings.get().downloadSource() != saved.downloadSource();
            settings.set(saved);
            if (rootChanged) {
                instances.setAll(available);
                selected.set(available.isEmpty() ? null : available.getFirst());
            }
            if (sourceChanged) {
                versions.clear();
                loaderVersions.clear();
            }
            boolean changed = !saved.dataDirectory().isBlank()
                && !Path.of(saved.dataDirectory()).toAbsolutePath().normalize().equals(settingsStore.directory());
            status.set(changed || rootChanged ? "设置已保存，正在重启启动器…" : "设置已保存");
            if (!result.warning().isBlank()) {
                error.set(result.warning());
            }
            if (changed || rootChanged) {
                restart.run();
            }
        });
    }
}
