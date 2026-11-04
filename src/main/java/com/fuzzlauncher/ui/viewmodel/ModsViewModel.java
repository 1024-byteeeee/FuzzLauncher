package com.fuzzlauncher.ui.viewmodel;

import com.fuzzlauncher.application.model.FileOperationResult;
import com.fuzzlauncher.application.task.TaskContext;
import com.fuzzlauncher.application.usecase.mod.ManageMods;
import com.fuzzlauncher.domain.instance.GameInstance;
import com.fuzzlauncher.domain.mod.ModFile;
import com.fuzzlauncher.domain.settings.LauncherSettings;
import com.fuzzlauncher.ui.task.FxTaskCoordinator;

import javafx.beans.property.*;

import java.nio.file.Path;
import java.util.*;

public final class ModsViewModel {
    private final FxTaskCoordinator tasks;
    private final BooleanProperty busy;
    private final StringProperty status, error;
    private final ObjectProperty<LauncherSettings> settings;
    private final ObjectProperty<GameInstance> selected;
    private final BooleanProperty gameRunning;
    private final ManageMods mods;

    ModsViewModel(PresentationState state, FxTaskCoordinator tasks, ManageMods mods) {
        this.tasks = tasks;
        busy = tasks.busyProperty();
        status = tasks.statusProperty();
        error = tasks.errorProperty();
        settings = state.settings;
        selected = state.selected;
        gameRunning = state.gameRunning;
        this.mods = mods;
    }

    public Path modDirectory(GameInstance instance) {
        return mods.directory(settings.get(), instance);
    }

    public List<ModFile> scanMods(Path directory, TaskContext task) throws Exception {
        return mods.scan(directory, task);
    }

    public void importMods(GameInstance instance, List<Path> sources, boolean renameCollisions, Runnable changed) {
        if (instance == null || busy.get() || gameRunning.get() || sources.isEmpty()) {
            return;
        }
        Path directory = modDirectory(instance);
        List<Path> selectedFiles = List.copyOf(sources);
        tasks.submit("导入模组", task -> mods.importFiles(directory, selectedFiles, renameCollisions, task),
            report -> modsChanged("导入", report, changed));
    }

    public void setModsEnabled(GameInstance instance, List<ModFile> selected, boolean enabled, Runnable changed) {
        if (instance == null || busy.get() || gameRunning.get() || selected.isEmpty()) {
            return;
        }
        Path directory = modDirectory(instance);
        List<ModFile> selectedFiles = List.copyOf(selected);
        tasks.submit(enabled ? "启用模组" : "禁用模组", task -> mods.setEnabled(directory, selectedFiles, enabled, task),
            report -> modsChanged(enabled ? "启用" : "禁用", report, changed));
    }

    public void deleteMods(GameInstance instance, List<ModFile> selected, Runnable changed) {
        if (instance == null || busy.get() || gameRunning.get() || selected.isEmpty()) {
            return;
        }
        Path directory = modDirectory(instance);
        List<ModFile> selectedFiles = List.copyOf(selected);
        tasks.submit("删除模组", task -> mods.delete(directory, selectedFiles, task), report -> modsChanged("删除", report, changed));
    }

    private void modsChanged(String action, FileOperationResult report, Runnable changed) {
        status.set(action + "完成：" + report.completed() + " 个模组文件");
        changed.run();
        if (!report.failures().isEmpty()) {
            error.set("部分模组未能" + action + "：\n" + String.join("\n", report.failures()));
        }
    }
}
