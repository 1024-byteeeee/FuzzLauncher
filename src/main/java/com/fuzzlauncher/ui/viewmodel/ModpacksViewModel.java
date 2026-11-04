package com.fuzzlauncher.ui.viewmodel;

import com.fuzzlauncher.application.model.ModpackInfo;
import com.fuzzlauncher.application.port.ModpackOperations;
import com.fuzzlauncher.domain.instance.GameInstance;
import com.fuzzlauncher.domain.instance.IsolationPolicy;
import com.fuzzlauncher.domain.modpack.ModpackFormat;
import com.fuzzlauncher.domain.settings.LauncherSettings;
import com.fuzzlauncher.ui.task.FxTaskCoordinator;

import javafx.beans.property.*;
import javafx.collections.*;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;

public final class ModpacksViewModel {
    private final PresentationState state;
    private final FxTaskCoordinator tasks;
    private final BooleanProperty busy;
    private final StringProperty status, error;
    private final ObjectProperty<LauncherSettings> settings;
    private final ObservableList<GameInstance> instances;
    private final ObjectProperty<GameInstance> selected;
    private final BooleanProperty gameRunning;
    private final BooleanProperty installing;
    private final ModpackOperations modpacks;

    ModpacksViewModel(PresentationState state, FxTaskCoordinator tasks, ModpackOperations modpacks) {
        this.state = state;
        this.tasks = tasks;
        busy = tasks.busyProperty();
        status = tasks.statusProperty();
        error = tasks.errorProperty();
        settings = state.settings;
        instances = state.instances;
        selected = state.selected;
        gameRunning = state.gameRunning;
        installing = state.installing;
        this.modpacks = modpacks;
    }

    public void inspectModpack(Path archive, Consumer<ModpackInfo> ready) {
        if (busy.get() || gameRunning.get()) {
            return;
        }
        tasks.submit("读取整合包", context -> modpacks.inspect(archive), ready);
    }

    public void importModpack(Path archive, String name, boolean optional) {
        importModpack(archive, name, optional, Set.of());
    }

    public void importModpack(Path archive, String name, boolean optional, Set<String> excludedOptional) {
        if (busy.get() || gameRunning.get()) {
            return;
        }
        if (!modpacks.updatesAvailable()) {
            error.set("整合包服务尚未初始化");
            return;
        }
        Path root = Path.of(settings.get().gameRoot());
        installing.set(true);
        Set<String> excluded = Set.copyOf(excludedOptional);
        tasks.submit("导入整合包", context -> modpacks.importPack(root, archive, name, optional, excluded, context), instance -> {
            instances.add(instance);
            selected.set(instance);
            status.set("整合包导入完成：" + instance.name());
        });
    }

    public void exportModpack(GameInstance instance, ModpackFormat format, String name, String version, String author, String description,
        String fileApi, Set<String> entries, Path destination, boolean replace) {
        if (busy.get() || gameRunning.get()) {
            return;
        }
        Path source = new IsolationPolicy().gameDirectory(Path.of(settings.get().gameRoot()), instance, settings.get().isolation());
        installing.set(true);
        tasks.submit("导出整合包", context -> {
            modpacks.export(source, instance, format, name, version, author, description, fileApi, entries, destination, replace, context);
            return destination;
        }, path -> status.set("整合包已导出：" + path));
    }

    public void updateModpack(GameInstance instance) {
        if (busy.get() || gameRunning.get() || !modpacks.updatesAvailable()) {
            return;
        }
        Path root = Path.of(settings.get().gameRoot());
        installing.set(true);
        tasks.submit("更新服务器整合包", context -> modpacks.update(root, instance, context), updated -> {
            state.replaceInstance(updated);
            status.set("整合包更新检查完成，本地修改已保留");
        });
    }
}
