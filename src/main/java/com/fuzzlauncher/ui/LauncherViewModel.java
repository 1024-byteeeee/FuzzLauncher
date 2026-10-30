package com.fuzzlauncher.ui;

import com.fuzzlauncher.application.*;
import com.fuzzlauncher.domain.*;
import com.fuzzlauncher.infrastructure.*;
import com.fuzzlauncher.infrastructure.game.VersionCatalog;

import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.*;
import javafx.concurrent.Task;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Coordinates use cases. No HTTP, credential handling or launch argument construction belongs here.
 */
public final class LauncherViewModel implements AutoCloseable {
    @FunctionalInterface
    public interface Work<T> {
        T execute(TaskContext context) throws Exception;
    }

    private final SettingsStore settingsStore;
    private final InstanceRepository repository;
    private final VersionCatalog catalog;
    private final AccountAuthenticator authentication;
    private final InstallGame install;
    private final BaseVersions baseVersions;
    private final LaunchGame launch;
    private OperationJournal.Store recovery;

    public void configureRecovery(OperationJournal.Store recovery) {
        this.recovery = recovery;
    }

    private Modpacks modpacks;
    private final AccountAvatars avatarLoader;
    private final ObjectProperty<javafx.scene.image.Image> accountAvatar = new SimpleObjectProperty<>();
    private final ExecutorService avatarExecutor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("account-avatar").factory());
    private Future<?> avatarWork;
    private volatile int avatarGeneration;
    private volatile boolean closed;
    private final ExecutorService executor = Executors
        .newSingleThreadExecutor(Thread.ofPlatform().name("fuzz-operations").daemon(true).factory());
    private Task<?> active;
    private final Object commitGate = new Object();
    private boolean committing;

    public boolean isCommitting() {
        synchronized (commitGate) {
            return committing;
        }
    }

    private Object catalogOwner;
    private boolean catalogTask;
    private final BooleanProperty installing = new SimpleBooleanProperty();
    private final ObjectProperty<LauncherSettings> settings = new SimpleObjectProperty<>();
    private final ObservableList<GameInstance> instances = FXCollections.observableArrayList();
    private final ObjectProperty<GameInstance> selected = new SimpleObjectProperty<>();
    private final ObservableList<GameVersion> versions = FXCollections.observableArrayList();
    private final ObservableList<String> loaderVersions = FXCollections.observableArrayList();
    private final ObservableList<String> readOnlyLoaderVersions = FXCollections.unmodifiableObservableList(loaderVersions);
    private final ObjectProperty<Account> account = new SimpleObjectProperty<>();
    private final BooleanProperty busy = new SimpleBooleanProperty();
    private final DoubleProperty progress = new SimpleDoubleProperty(0);
    private final StringProperty status = new SimpleStringProperty("就绪");
    private final StringProperty error = new SimpleStringProperty("");
    private final BooleanProperty gameRunning = new SimpleBooleanProperty();
    private final BooleanProperty loggingIn = new SimpleBooleanProperty();

    public ReadOnlyBooleanProperty loggingInProperty() {
        return loggingIn;
    }

    private final BooleanProperty savingSettings = new SimpleBooleanProperty();

    public ReadOnlyBooleanProperty savingSettingsProperty() {
        return savingSettings;
    }

    private Runnable restart = () -> {
    };
    private Consumer<LauncherVisibility> gameStarted = ignored -> {
    };
    private java.util.function.IntConsumer gameExited = ignored -> {
    };

    public void onGameLifecycle(Consumer<LauncherVisibility> started, java.util.function.IntConsumer exited) {
        gameStarted = Objects.requireNonNull(started);
        gameExited = Objects.requireNonNull(exited);
    }

    public void onRestartRequired(Runnable action) {
        restart = Objects.requireNonNull(action);
    }

    public void configureModpacks(Modpacks service) {
        modpacks = Objects.requireNonNull(service);
    }

    public void inspectModpack(Path archive, Consumer<ModpackArchive.Info> ready) {
        if (busy.get() || gameRunning.get()) {
            return;
        }
        submit("读取整合包", context -> ModpackArchive.inspect(archive), ready);
    }

    public void importModpack(Path archive, String name, boolean optional) {
        importModpack(archive, name, optional, Set.of());
    }

    public void importModpack(Path archive, String name, boolean optional, Set<String> excludedOptional) {
        if (busy.get() || gameRunning.get()) {
            return;
        }
        if (modpacks == null) {
            error.set("整合包服务尚未初始化");
            return;
        }
        Path root = Path.of(settings.get().gameRoot());
        installing.set(true);
        Set<String> excluded = Set.copyOf(excludedOptional);
        submit("导入整合包", context -> modpacks.importPack(root, archive, name, optional, excluded, context), instance -> {
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
        submit("导出整合包", context -> {
            ModpackArchive.export(source, instance, format, name, version, author, description, fileApi, entries, destination, replace,
                context);
            return destination;
        }, path -> status.set("整合包已导出：" + path));
    }

    public void updateModpack(GameInstance instance) {
        if (busy.get() || gameRunning.get() || modpacks == null) {
            return;
        }
        Path root = Path.of(settings.get().gameRoot());
        installing.set(true);
        submit("更新服务器整合包", context -> modpacks.update(root, instance, context), updated -> {
            replaceInstance(updated);
            status.set("整合包更新检查完成，本地修改已保留");
        });
    }

    private void replaceInstance(GameInstance updated) {
        for (int i = 0; i < instances.size(); i++) {
            if (instances.get(i).id().equals(updated.id())) {
                instances.set(i, updated);
                break;
            }
        }
        if (selected.get() != null && selected.get().id().equals(updated.id())) {
            selected.set(updated);
        }
    }

    public LauncherViewModel(SettingsStore settingsStore, InstanceRepository repository, VersionCatalog catalog,
        AccountAuthenticator authentication, InstallGame install, LaunchGame launch) throws IOException {
        this(settingsStore, repository, catalog, authentication, install, launch, null);
    }

    public LauncherViewModel(SettingsStore settingsStore, InstanceRepository repository, VersionCatalog catalog,
        AccountAuthenticator authentication, InstallGame install, LaunchGame launch, AccountAvatars avatarLoader) throws IOException {
        this.settingsStore = settingsStore;
        this.repository = repository;
        this.catalog = catalog;
        this.authentication = authentication;
        this.install = install;
        this.baseVersions = install == null ? new BaseVersions() : install.baseVersions();
        this.launch = launch;
        this.avatarLoader = avatarLoader;
        settings.set(settingsStore.load());
        instances.setAll(repository.findAll());
        for (GameInstance instance : instances) {
            try {
                InstanceDirectories.migrate(Path.of(settings.get().gameRoot()), instance);
                baseVersions.migrate(Path.of(settings.get().gameRoot()), instance.gameVersion());
                InstanceCatalog.remember(Path.of(settings.get().gameRoot()), instance);
            } catch (IOException ex) {
                status.set("实例目录迁移未完成：" + ex.getMessage());
            }
        }
        if (!instances.isEmpty()) {
            selected.set(instances.getFirst());
        }
        account.addListener((observable, old, identity) -> loadAvatar(identity));
        account.set(authentication.savedAccount().orElse(null));
    }

    public ReadOnlyObjectProperty<javafx.scene.image.Image> accountAvatarProperty() {
        return accountAvatar;
    }

    private void loadAvatar(Account identity) {
        int generation = ++avatarGeneration;
        if (avatarWork != null) {
            avatarWork.cancel(true);
        }
        accountAvatar.set(null);
        if (identity == null || identity.isOffline() || avatarLoader == null || closed) {
            return;
        }
        avatarWork = avatarExecutor.submit(() -> {
            try {
                byte[] bytes = avatarLoader.load(identity, new TaskContext() {
                    public void report(String message, double progress) {
                    }

                    public boolean cancelled() {
                        return closed || generation != avatarGeneration || Thread.currentThread().isInterrupted();
                    }
                });
                if (bytes != null && !closed) {
                    Platform.runLater(() -> {
                        if (!closed && generation == avatarGeneration && identity.equals(account.get())) {
                            var image = new javafx.scene.image.Image(new java.io.ByteArrayInputStream(bytes));
                            if (!image.isError()) {
                                accountAvatar.set(image);
                            } else {
                                LauncherLog.LOGGER.warning("头像 PNG 解码失败");
                            }
                        }
                    });
                }
            } catch (InterruptedException | CancellationException ex) {
                Thread.currentThread().interrupt();
            } catch (Exception ex) {
                LauncherLog.LOGGER.warning("头像加载失败：" + ex.getMessage());
            }
        });
    }

    public ReadOnlyObjectProperty<LauncherSettings> settingsProperty() {
        return settings;
    }

    public ObservableList<GameInstance> instances() {
        return FXCollections.unmodifiableObservableList(instances);
    }

    public ReadOnlyObjectProperty<GameInstance> selectedProperty() {
        return selected;
    }

    public void selectInstance(GameInstance instance) {
        if (instance != null && instances.contains(instance)) {
            selected.set(instance);
        }
    }

    public ObservableList<GameVersion> versions() {
        return FXCollections.unmodifiableObservableList(versions);
    }

    public ObservableList<String> loaderVersions() {
        return readOnlyLoaderVersions;
    }

    public ReadOnlyObjectProperty<Account> accountProperty() {
        return account;
    }

    public ReadOnlyBooleanProperty busyProperty() {
        return busy;
    }

    public ReadOnlyDoubleProperty progressProperty() {
        return progress;
    }

    public ReadOnlyStringProperty statusProperty() {
        return status;
    }

    public ReadOnlyStringProperty errorProperty() {
        return error;
    }

    public ReadOnlyBooleanProperty gameRunningProperty() {
        return gameRunning;
    }

    public ReadOnlyBooleanProperty installingProperty() {
        return installing;
    }

    public Path dataDirectory() {
        return settingsStore.directory();
    }

    public void loadVersions() {
        loadVersions(null);
    }

    public void loadVersions(Object owner) {
        loadVersions(owner, false);
    }

    public void loadVersions(Object owner, boolean force) {
        submit("获取游戏版本", context -> {
            context.report("读取官方版本清单", -1);
            return catalog.games(force, values -> Platform.runLater(() -> {
                if (!closed && !context.cancelled()) {
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
        submit("获取加载器版本", context -> {
            context.report("查询 " + type + " 兼容版本", -1);
            return catalog.loaders(type, game.id(), force, values -> Platform.runLater(() -> {
                if (!closed && !context.cancelled()) {
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
        submit("安装游戏", context -> install.execute(root, game, type, loaderVersion, name, context), instance -> {
            instances.add(instance);
            selected.set(instance);
        });
    }

    public void login() {
        if (!canLogin()) {
            return;
        }
        loggingIn.set(true);
        submit("微软登录", authentication::login, session -> {
            account.set(session.account());
            status.set("已登录微软账号: " + session.account().name());
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
        submit("重装加载器", context -> {
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

    public void logout() {
        submit("退出登录", context -> {
            authentication.logout(context);
            return true;
        }, ignored -> {
            account.set(null);
            status.set("已退出登录");
        });
    }

    public void loginOffline(String name) {
        if (!canLogin()) {
            return;
        }
        submit("离线登录", context -> authentication.loginOffline(name, context), session -> {
            account.set(session.account());
            status.set("已使用离线账号: " + session.account().name());
        });
    }

    private boolean canLogin() {
        if (busy.get() || gameRunning.get()) {
            return false;
        }
        Account current = account.get();
        if (current != null) {
            error.set(current.isOffline() ? "请先退出当前离线账号，再登录" : "请先退出当前微软账号，再登录");
            return false;
        }
        return true;
    }

    public void launch() {
        GameInstance instance = selected.get();
        Account identity = account.get();
        LauncherSettings config = settings.get();
        if (instance == null) {
            error.set("请先安装并选择一个游戏实例");
            return;
        }
        if (modpacks != null && identity != null) {
            submit("检查整合包更新", context -> modpacks.update(Path.of(config.gameRoot()), instance, context), updated -> {
                replaceInstance(updated);
                launchPrepared(config, updated, identity);
            });
        } else {
            launchPrepared(config, instance, identity);
        }
    }

    private void launchPrepared(LauncherSettings config, GameInstance instance, Account identity) {
        submit("启动游戏", context -> launch.execute(config, instance, identity, context), running -> {
            gameRunning.set(true);
            status.set("游戏运行中");
            running.process().onExit().thenAccept(process -> Platform.runLater(() -> {
                if (closed) {
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

    public void updateInstance(GameInstance instance) {
        if (busy.get()) {
            return;
        }
        if (gameRunning.get()) {
            error.set("游戏运行中，请先退出游戏再修改实例设置");
            return;
        }
        submit("保存实例设置", context -> {
            if (!instance.javaPath().isBlank()) {
                com.fuzzlauncher.infrastructure.game.JavaRuntimeManager.configuredExecutable(instance.javaPath());
            }
            GameInstance previous = repository.findAll().stream().filter(item -> item.id().equals(instance.id())).findFirst()
                .orElseThrow(() -> new IOException("实例已不存在"));
            InstanceDirectories.save(Path.of(settings.get().gameRoot()), previous, instance, repository);
            try {
                InstanceCatalog.remember(Path.of(settings.get().gameRoot()), instance);
            } catch (IOException ex) {
                LauncherLog.LOGGER.warning("实例目录配置备份失败：" + ex.getMessage());
            }
            return instance;
        }, saved -> {
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
        submit("删除实例", context -> {
            InstanceCatalog.delete(Path.of(settings.get().gameRoot()), instance, repository, context);
            return true;
        }, ignored -> {
            instances.remove(instance);
            selected.set(instances.isEmpty() ? null : instances.getFirst());
            status.set("实例及其独立目录数据已永久删除");
        });
    }

    public void refreshInstances() {
        String selectedId = selected.get() == null ? null : selected.get().id();
        submit("刷新实例", context -> InstanceCatalog.refresh(Path.of(settings.get().gameRoot()), repository, baseVersions), values -> {
            instances.setAll(values);
            selected.set(values.stream().filter(item -> item.id().equals(selectedId)).findFirst()
                .orElse(values.isEmpty() ? null : values.getFirst()));
            status.set("已读取 " + values.size() + " 个可用实例");
        });
    }

    public void saveSettings(LauncherSettings config) {
        saveSettings(config, false);
    }

    Callable<com.fuzzlauncher.infrastructure.game.LaunchArguments.Preview> jvmPreview(GameInstance instance) {
        Path root = Path.of(settings.get().gameRoot());
        return () -> launch == null ? new com.fuzzlauncher.infrastructure.game.LaunchArguments.Preview(null, List.of())
            : launch.previewJvm(root, instance);
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
            rootChanged = !GameRootMigration.sameDirectory(targetRoot, previousRoot);
        } catch (IOException ex) {
            error.set("无法读取游戏根目录：" + ex.getMessage());
            return;
        }
        record SavedSettings(List<GameInstance> instances, String warning) {
        }
        savingSettings.set(true);
        submit("保存启动器设置", context -> {
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
                ? recovery.begin(rootChanged && migrate ? "迁移游戏目录" : "保存启动器设置", previousRoot, targetRoot,
                    DataLocations.bootstrapDirectory())
                : null;
            try {
                try (GameRootMigration migration = rootChanged && migrate
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
                            boolean dataChanged = !requested.toAbsolutePath().normalize()
                                .equals(dataDirectory().toAbsolutePath().normalize());
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
                    return new SavedSettings(available, warning);
                }
            } finally {
                if (operation != null) {
                    operation.recover();
                }
            }
        }, result -> {
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
                && !Path.of(saved.dataDirectory()).toAbsolutePath().normalize().equals(dataDirectory());
            status.set(changed || rootChanged ? "设置已保存，正在重启启动器…" : "设置已保存");
            if (!result.warning().isBlank()) {
                error.set(result.warning());
            }
            if (changed || rootChanged) {
                restart.run();
            }
        });
    }

    public void cancel() {
        if (savingSettings.get()) {
            return;
        }
        synchronized (commitGate) {
            if (!committing && active != null) {
                active.cancel(true);
            }
        }
    }

    public void cancelCatalogRequests(Object owner) {
        if (active != null && catalogTask && catalogOwner == owner) {
            Task<?> task = active;
            task.cancel(true);
            if (active == task) {
                finish(task);
            }
            if (!busy.get()) {
                status.set("已停止获取版本列表");
            }
        }
    }

    private <T> void submit(String title, Work<T> work, Consumer<T> success) {
        submit(title, work, success, null, false);
    }

    private <T> void submit(String title, Work<T> work, Consumer<T> success, Object owner, boolean catalog) {
        if (busy.get()) {
            return;
        }
        error.set("");
        busy.set(true);
        status.set(title);
        progress.set(-1);
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return work.execute(new TaskContext() {
                    @Override
                    public void report(String message, double value) {
                        updateMessage(message);
                        updateProgress(value < 0 ? -1 : value, 1);
                    }

                    @Override
                    public boolean cancelled() {
                        return isCancelled();
                    }

                    @Override
                    public void commit(TaskContext.Commit action) throws Exception {
                        synchronized (commitGate) {
                            checkCancelled();
                            committing = true;
                        }
                        action.run();
                    }
                });
            }
        };
        active = task;
        catalogOwner = owner;
        catalogTask = catalog;
        LauncherLog.LOGGER.info(title);
        status.bind(task.messageProperty());
        progress.bind(task.progressProperty());
        task.setOnSucceeded(event -> {
            if (!finish(task)) {
                return;
            }
            success.accept(task.getValue());
            LauncherLog.LOGGER.info(title + "完成");
        });
        task.setOnFailed(event -> {
            if (!finish(task)) {
                return;
            }
            Throwable cause = task.getException();
            while (cause instanceof ExecutionException || cause instanceof CompletionException) {
                cause = cause.getCause();
            }
            String message = Objects.toString(cause.getMessage(), cause.getClass().getSimpleName());
            status.set(title + "失败");
            LauncherLog.LOGGER.warning(title + "失败：" + message);
            error.set(message);
        });
        task.setOnCancelled(event -> {
            if (!finish(task)) {
                return;
            }
            status.set(catalog ? "已停止获取版本列表" : "任务已取消");
            LauncherLog.LOGGER.info(title + "已取消");
        });
        executor.execute(task);
    }

    private boolean finish(Task<?> task) {
        if (active != task) {
            return false;
        }
        active = null;
        synchronized (commitGate) {
            committing = false;
        }
        catalogTask = false;
        catalogOwner = null;
        status.unbind();
        progress.unbind();
        status.set(task.getMessage().isBlank() ? "就绪" : task.getMessage());
        progress.set(0);
        installing.set(false);
        savingSettings.set(false);
        loggingIn.set(false);
        busy.set(false);
        return true;
    }

    @Override
    public void close() {
        closed = true;
        avatarGeneration++;
        if (avatarWork != null) {
            avatarWork.cancel(true);
        }
        avatarExecutor.shutdownNow();
        synchronized (commitGate) {
            if (committing) {
                executor.shutdown();
            } else {
                cancel();
                executor.shutdownNow();
            }
        }
    }

    public boolean awaitShutdown() {
        try {
            return executor.awaitTermination(10, TimeUnit.SECONDS) && avatarExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
