package com.fuzzlauncher.ui.viewmodel;

import com.fuzzlauncher.application.port.GameInstallation;
import com.fuzzlauncher.application.port.GameLauncher;
import com.fuzzlauncher.application.port.JvmArgumentPreview;
import com.fuzzlauncher.application.port.ModpackOperations;
import com.fuzzlauncher.application.port.VersionCatalogPort;
import com.fuzzlauncher.domain.account.Account;
import com.fuzzlauncher.domain.game.GameVersion;
import com.fuzzlauncher.domain.game.LoaderType;
import com.fuzzlauncher.domain.instance.GameInstance;
import com.fuzzlauncher.domain.settings.LauncherSettings;
import com.fuzzlauncher.domain.settings.LauncherVisibility;
import com.fuzzlauncher.ui.task.FxTaskCoordinator;

import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public final class GamesViewModel {
    private final PresentationState state;
    private final FxTaskCoordinator tasks;
    private final BooleanProperty busy;
    private final StringProperty status, error;
    private final ObjectProperty<LauncherSettings> settings;
    private final ObservableList<GameInstance> instances;
    private final ObjectProperty<GameInstance> selected;
    private final ObjectProperty<Account> account;
    private final BooleanProperty gameRunning;
    private final BooleanProperty installing;
    private final VersionCatalogPort catalog;
    private final GameInstallation install;
    private final GameLauncher launch;
    private final ModpackOperations modpacks;
    private Consumer<LauncherVisibility> gameStarted = ignored -> {
    };
    private IntConsumer gameExited = ignored -> {
    };

    GamesViewModel(PresentationState state, FxTaskCoordinator tasks, VersionCatalogPort catalog, GameInstallation install,
        GameLauncher launch, ModpackOperations modpacks) {
        this.state = state;
        this.tasks = tasks;
        busy = tasks.busyProperty();
        status = tasks.statusProperty();
        error = tasks.errorProperty();
        settings = state.settings;
        instances = state.instances;
        selected = state.selected;
        account = state.account;
        gameRunning = state.gameRunning;
        installing = state.installing;
        this.catalog = catalog;
        this.install = install;
        this.launch = launch;
        this.modpacks = modpacks;
    }

    public void onGameLifecycle(Consumer<LauncherVisibility> started, IntConsumer exited) {
        gameStarted = Objects.requireNonNull(started);
        gameExited = Objects.requireNonNull(exited);
    }

    public void install(GameVersion game, LoaderType type, String loaderVersion, String name) {
        if (busy.get()) {
            return;
        }
        if (game == null) {
            error.set("请选择游戏版本");
            return;
        }
        Path root = Path.of(settings.get().gameRoot());
        installing.set(true);
        tasks.submit("安装游戏", context -> install.execute(root, game, type, loaderVersion, name, context), instance -> {
            instances.add(instance);
            selected.set(instance);
        });
    }

    public void reinstallLoader(GameInstance instance, String version) {
        if (busy.get() || gameRunning.get()) {
            return;
        }
        if (instance == null || instance.loader() == LoaderType.VANILLA) {
            error.set("原版实例不能修改加载器版本");
            return;
        }
        installing.set(true);
        Path root = Path.of(settings.get().gameRoot());
        tasks.submit("重装加载器", context -> {
            if (version == null || !catalog.loaders(instance.loader(), instance.gameVersion()).contains(version)) {
                throw new IOException("所选加载器版本与当前游戏不兼容，请刷新版本列表");
            }
            context.checkCancelled();
            return install.reinstallLoader(root, instance, version, context);
        }, updated -> {
            for (int i = 0; i < instances.size(); i++) {
                if (instances.get(i).id().equals(updated.id())) {
                    instances.set(i, updated);
                    break;
                }
            }
            selected.set(updated);
            status.set("加载器已完整重装：" + updated.loader() + " " + updated.loaderVersion());
        });
    }

    public void launch() {
        GameInstance instance = selected.get();
        Account identity = account.get();
        LauncherSettings config = settings.get();
        if (instance == null) {
            error.set("请先安装并选择一个游戏实例");
            return;
        }
        if (modpacks.updatesAvailable() && identity != null) {
            tasks.submit("检查整合包更新", context -> modpacks.update(Path.of(config.gameRoot()), instance, context), updated -> {
                state.replaceInstance(updated);
                launchPrepared(config, updated, identity);
            });
        } else {
            launchPrepared(config, instance, identity);
        }
    }

    private void launchPrepared(LauncherSettings config, GameInstance instance, Account identity) {
        tasks.submit("启动游戏", context -> launch.execute(config, instance, identity, context), running -> {
            gameRunning.set(true);
            status.set("游戏运行中");
            running.process().onExit().thenAccept(process -> Platform.runLater(() -> {
                if (tasks.isClosed()) {
                    return;
                }
                gameRunning.set(false);
                gameExited.accept(process.exitValue());
                status.set("游戏已退出，退出码 " + process.exitValue());
                if (process.exitValue() != 0) {
                    error.set("游戏异常退出（" + process.exitValue() + "），请查看日志: " + running.log());
                }
            }));
            gameStarted.accept(config.launcherVisibility());
        });
    }

    public Callable<JvmArgumentPreview> jvmPreview(GameInstance instance) {
        Path root = Path.of(settings.get().gameRoot());
        return () -> launch == null ? JvmArgumentPreview.unavailable() : launch.previewJvm(root, instance);
    }
}
