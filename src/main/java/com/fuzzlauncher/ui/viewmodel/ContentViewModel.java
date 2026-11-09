package com.fuzzlauncher.ui.viewmodel;

import com.fuzzlauncher.application.model.FileOperationResult;
import com.fuzzlauncher.application.task.TaskContext;
import com.fuzzlauncher.application.usecase.mod.ManageContent;
import com.fuzzlauncher.domain.instance.GameInstance;
import com.fuzzlauncher.domain.content.ContentFile;
import com.fuzzlauncher.domain.content.ContentKind;
import com.fuzzlauncher.domain.settings.LauncherSettings;
import com.fuzzlauncher.ui.task.FxTaskCoordinator;

import javafx.beans.property.*;

import java.nio.file.Path;
import java.util.*;

public final class ContentViewModel {
    private final FxTaskCoordinator tasks;
    private final BooleanProperty busy;
    private final StringProperty status, error;
    private final BooleanProperty gameRunning;
    private final ManageContent mods;

    ContentViewModel(PresentationState state, FxTaskCoordinator tasks, ManageContent mods) {
        this.tasks = tasks;
        busy = tasks.busyProperty();
        status = tasks.statusProperty();
        error = tasks.errorProperty();
        gameRunning = state.gameRunning;
        this.mods = mods;
    }

    public ContentKind kind() { return mods.kind(); }

    public String label() {
        return switch (kind()) {
            case MODS -> "模组";
            case RESOURCE_PACKS -> "资源包";
            case SCHEMATICS -> "原理图";
        };
    }

    public Path directory(LauncherSettings settings, GameInstance instance) {
        return mods.directory(settings, instance);
    }

    public boolean canImport(Path source) {
        return mods.canImport(source);
    }

    public List<ContentFile> scan(Path directory, TaskContext task) throws Exception {
        return mods.scan(directory, task);
    }

    public void importFiles(Path directory, List<Path> sources, boolean renameCollisions, Runnable changed) {
        if (directory == null || busy.get() || gameRunning.get() || sources.isEmpty()) {
            return;
        }
        List<Path> selectedFiles = List.copyOf(sources);
        tasks.submit("导入" + label(), task -> mods.importFiles(directory, selectedFiles, renameCollisions, task), report -> contentChanged("导入", report, changed));
    }

    public void setEnabled(Path directory, List<ContentFile> selected, boolean enabled, Runnable changed) {
        if (directory == null || busy.get() || gameRunning.get() || selected.isEmpty()) {
            return;
        }
        List<ContentFile> selectedFiles = List.copyOf(selected);
        tasks.submit((enabled ? "启用" : "禁用") + label(), task -> mods.setEnabled(directory, selectedFiles, enabled, task),
            report -> contentChanged(enabled ? "启用" : "禁用", report, changed));
    }

    public void delete(Path directory, List<ContentFile> selected, Runnable changed) {
        if (directory == null || busy.get() || gameRunning.get() || selected.isEmpty()) {
            return;
        }
        List<ContentFile> selectedFiles = List.copyOf(selected);
        tasks.submit("删除" + label(), task -> mods.delete(directory, selectedFiles, task), report -> contentChanged("删除", report, changed));
    }

    public void createFolder(Path directory, String name, Runnable changed) {
        if (directory == null || busy.get() || gameRunning.get()) {
            return;
        }
        tasks.submit("新建文件夹", task -> {
            mods.createFolder(directory, name, task);
            return name;
        }, created -> {
            status.set("已新建文件夹：" + created);
            changed.run();
        });
    }

    private void contentChanged(String action, FileOperationResult report, Runnable changed) {
        status.set(action + "完成：" + report.completed() + " 个" + label());
        changed.run();
        if (!report.failures().isEmpty()) {
            error.set("部分" + label() + "未能" + action + "：\n" + String.join("\n", report.failures()));
        }
    }
}
