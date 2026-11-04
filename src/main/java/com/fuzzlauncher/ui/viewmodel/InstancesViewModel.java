package com.fuzzlauncher.ui.viewmodel;

import com.fuzzlauncher.application.port.InstanceOperations;
import com.fuzzlauncher.domain.instance.GameInstance;
import com.fuzzlauncher.domain.settings.LauncherSettings;
import com.fuzzlauncher.ui.task.FxTaskCoordinator;

import javafx.beans.property.*;
import javafx.collections.*;

import java.io.IOException;
import java.nio.file.Path;

public final class InstancesViewModel {
    private final PresentationState state;
    private final FxTaskCoordinator tasks;
    private final BooleanProperty busy;
    private final StringProperty status, error;
    private final ObjectProperty<LauncherSettings> settings;
    private final ObservableList<GameInstance> instances;
    private final ObjectProperty<GameInstance> selected;
    private final BooleanProperty gameRunning;
    private final InstanceOperations instanceOperations;

    InstancesViewModel(PresentationState state, FxTaskCoordinator tasks, InstanceOperations instanceOperations) {
        this.state = state;
        this.tasks = tasks;
        busy = tasks.busyProperty();
        status = tasks.statusProperty();
        error = tasks.errorProperty();
        settings = state.settings;
        instances = state.instances;
        selected = state.selected;
        gameRunning = state.gameRunning;
        this.instanceOperations = instanceOperations;
    }

    public void prepareInstanceDirectory(GameInstance instance) throws IOException {
        instanceOperations.prepareDirectory(Path.of(settings.get().gameRoot()), instance);
    }

    public boolean serverPack(GameInstance instance) throws IOException {
        return instanceOperations.serverPack(Path.of(settings.get().gameRoot()), instance);
    }

    public void selectInstance(GameInstance instance) {
        if (instance != null && instances.contains(instance)) {
            selected.set(instance);
        }
    }

    public void updateInstance(GameInstance instance) {
        if (busy.get()) {
            return;
        }
        if (gameRunning.get()) {
            error.set("游戏运行中，请先退出游戏再修改实例设置");
            return;
        }
        tasks.submit("保存实例设置", context -> instanceOperations.update(Path.of(settings.get().gameRoot()), instance), saved -> {
            int index = -1;
            for (int i = 0; i < instances.size(); i++) {
                if (instances.get(i).id().equals(saved.id())) {
                    index = i;
                }
            }
            if (index >= 0) {
                instances.set(index, saved);
            }
            selected.set(saved);
            status.set("实例设置已保存");
        });
    }

    public void unregister(GameInstance instance) {
        tasks.submit("删除实例", context -> {
            instanceOperations.delete(Path.of(settings.get().gameRoot()), instance, context);
            return true;
        }, ignored -> {
            instances.remove(instance);
            selected.set(instances.isEmpty() ? null : instances.getFirst());
            status.set("实例及其独立目录数据已永久删除");
        });
    }

    public void refreshInstances() {
        String selectedId = selected.get() == null ? null : selected.get().id();
        tasks.submit("刷新实例", context -> instanceOperations.refresh(Path.of(settings.get().gameRoot())), values -> {
            instances.setAll(values);
            selected.set(values.stream().filter(item -> item.id().equals(selectedId)).findFirst()
                .orElse(values.isEmpty() ? null : values.getFirst()));
            status.set("已读取 " + values.size() + " 个可用实例");
        });
    }
}
