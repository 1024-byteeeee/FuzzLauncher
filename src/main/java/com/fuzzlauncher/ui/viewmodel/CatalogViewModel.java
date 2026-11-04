package com.fuzzlauncher.ui.viewmodel;

import com.fuzzlauncher.application.port.VersionCatalogPort;
import com.fuzzlauncher.domain.game.GameVersion;
import com.fuzzlauncher.domain.game.LoaderType;
import com.fuzzlauncher.ui.task.FxTaskCoordinator;

import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.*;

public final class CatalogViewModel {
    private final FxTaskCoordinator tasks;
    private final BooleanProperty busy;
    private final StringProperty status, error;
    private final ObservableList<GameVersion> versions;
    private final ObservableList<String> loaderVersions;
    private final VersionCatalogPort catalog;

    CatalogViewModel(PresentationState state, FxTaskCoordinator tasks, VersionCatalogPort catalog) {
        this.tasks = tasks;
        busy = tasks.busyProperty();
        status = tasks.statusProperty();
        error = tasks.errorProperty();
        versions = state.versions;
        loaderVersions = state.loaderVersions;
        this.catalog = catalog;
    }

    public void loadVersions() {
        loadVersions(null);
    }

    public void loadVersions(Object owner) {
        loadVersions(owner, false);
    }

    public void loadVersions(Object owner, boolean force) {
        tasks.submit("获取游戏版本", context -> {
            context.report("读取官方版本清单", -1);
            return catalog.games(force, values -> Platform.runLater(() -> {
                if (!tasks.isClosed() && !context.cancelled()) {
                    versions.setAll(values);
                }
            }));
        }, versions::setAll, owner, true);
    }

    public void loadLoaders(LoaderType type, GameVersion game) {
        loadLoaders(type, game, null);
    }

    public void loadLoaders(LoaderType type, GameVersion game, Object owner) {
        loadLoaders(type, game, owner, false);
    }

    public void loadLoaders(LoaderType type, GameVersion game, Object owner, boolean force) {
        if (busy.get()) {
            return;
        }
        loaderVersions.clear();
        if (type == null || game == null || type == LoaderType.VANILLA) {
            return;
        }
        tasks.submit("获取加载器版本", context -> {
            context.report("查询 " + type + " 兼容版本", -1);
            return catalog.loaders(type, game.id(), force, values -> Platform.runLater(() -> {
                if (!tasks.isClosed() && !context.cancelled()) {
                    loaderVersions.setAll(values);
                }
            }));
        }, values -> {
            loaderVersions.setAll(values);
            if (values.isEmpty()) {
                status.set("此游戏版本没有兼容的 " + type + " 加载器");
            }
        }, owner, true);
    }
}
