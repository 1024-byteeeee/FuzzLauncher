package com.fuzzlauncher.ui;

import com.fuzzlauncher.application.*;
import com.fuzzlauncher.domain.*;
import com.fuzzlauncher.infrastructure.*;
import com.fuzzlauncher.infrastructure.game.VersionCatalog;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.collections.*;
import javafx.concurrent.Task;

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
  private final LaunchGame launch;
  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          Thread.ofPlatform().name("fuzz-operations").daemon(true).factory());
  private Task<?> active;
  private final ObjectProperty<LauncherSettings> settings = new SimpleObjectProperty<>();
  private final ObservableList<GameInstance> instances = FXCollections.observableArrayList();
  private final ObjectProperty<GameInstance> selected = new SimpleObjectProperty<>();
  private final ObservableList<GameVersion> versions = FXCollections.observableArrayList();
  private final ObservableList<String> loaderVersions = FXCollections.observableArrayList();
  private final ObjectProperty<Account> account = new SimpleObjectProperty<>();
  private final BooleanProperty busy = new SimpleBooleanProperty();
  private final DoubleProperty progress = new SimpleDoubleProperty(0);
  private final StringProperty status = new SimpleStringProperty("就绪");
  private final StringProperty error = new SimpleStringProperty("");
  private final BooleanProperty gameRunning = new SimpleBooleanProperty();

  public LauncherViewModel(
      SettingsStore settingsStore,
      InstanceRepository repository,
      VersionCatalog catalog,
      AccountAuthenticator authentication,
      InstallGame install,
      LaunchGame launch)
      throws IOException {
    this.settingsStore = settingsStore;
    this.repository = repository;
    this.catalog = catalog;
    this.authentication = authentication;
    this.install = install;
    this.launch = launch;
    settings.set(settingsStore.load());
    instances.setAll(repository.findAll());
    if (!instances.isEmpty()) selected.set(instances.getFirst());
    account.set(authentication.savedAccount().orElse(null));
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
    if (instance != null && instances.contains(instance)) selected.set(instance);
  }

  public ObservableList<GameVersion> versions() {
    return FXCollections.unmodifiableObservableList(versions);
  }

  public ObservableList<String> loaderVersions() {
    return FXCollections.unmodifiableObservableList(loaderVersions);
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

  public Path dataDirectory() {
    return settingsStore.directory();
  }

  public void loadVersions() {
    submit(
        "获取游戏版本",
        context -> {
          context.report("读取官方版本清单", -1);
          return catalog.games();
        },
        versions::setAll);
  }

  public void loadLoaders(LoaderType type, GameVersion game) {
    loaderVersions.clear();
    if (type == null || game == null || type == LoaderType.VANILLA) return;
    submit(
        "获取加载器版本",
        context -> {
          context.report("查询 " + type + " 兼容版本", -1);
          return catalog.loaders(type, game.id());
        },
        values -> {
          loaderVersions.setAll(values);
          if (values.isEmpty()) status.set("此游戏版本没有兼容的 " + type + " 加载器");
        });
  }

  public void install(GameVersion game, LoaderType type, String loaderVersion, String name) {
    if (game == null) {
      error.set("请选择游戏版本");
      return;
    }
    Path root = Path.of(settings.get().gameRoot());
    submit(
        "安装游戏",
        context -> install.execute(root, game, type, loaderVersion, name, context),
        instance -> {
          instances.add(instance);
          selected.set(instance);
        });
  }

  public void login() {
    submit(
        "微软登录",
        authentication::login,
        session -> {
          account.set(session.account());
          status.set("已登录正版账号: " + session.account().name());
        });
  }

  public void logout() {
    submit(
        "退出登录",
        context -> {
          authentication.logout(context);
          return true;
        },
        ignored -> {
          account.set(null);
          status.set("已退出登录");
        });
  }

  public void loginOffline(String name) {
    submit(
        "离线登录",
        context -> authentication.loginOffline(name, context),
        session -> {
          account.set(session.account());
          status.set("已使用离线账号: " + session.account().name());
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
    submit(
        "启动游戏",
        context -> launch.execute(config, instance, identity, context),
        running -> {
          gameRunning.set(true);
          status.set("游戏运行中，日志: " + running.log());
          running
              .process()
              .onExit()
              .thenAccept(
                  process ->
                      Platform.runLater(
                          () -> {
                            gameRunning.set(false);
                            status.set(
                                "游戏已退出，退出码 " + process.exitValue() + "，日志: " + running.log());
                            if (process.exitValue() != 0)
                              error.set(
                                  "游戏异常退出（" + process.exitValue() + "），请查看日志: " + running.log());
                          }));
        });
  }

  public void updateInstance(GameInstance instance) {
    submit(
        "保存实例设置",
        context -> {
          if (!instance.javaPath().isBlank())
            com.fuzzlauncher.infrastructure.game.JavaRuntimeManager.configuredExecutable(
                instance.javaPath());
          repository.save(instance);
          return instance;
        },
        saved -> {
          int index = -1;
          for (int i = 0; i < instances.size(); i++)
            if (instances.get(i).id().equals(saved.id())) index = i;
          if (index >= 0) instances.set(index, saved);
          selected.set(saved);
          status.set("实例设置已保存");
        });
  }

  public void unregister(GameInstance instance) {
    submit(
        "移除实例",
        context -> {
          repository.remove(instance.id());
          return true;
        },
        ignored -> {
          instances.remove(instance);
          selected.set(instances.isEmpty() ? null : instances.getFirst());
          status.set("实例已从列表移除，文件与存档保留");
        });
  }

  public void saveSettings(LauncherSettings config) {
    boolean hasInstances = !instances.isEmpty();
    Path previousRoot = Path.of(settings.get().gameRoot()).toAbsolutePath().normalize();
    submit(
        "保存启动器设置",
        context -> {
          if (hasInstances
              && !Path.of(config.gameRoot()).toAbsolutePath().normalize().equals(previousRoot))
            throw new IOException("已有实例时不可直接切换游戏根目录，请先备份并迁移实例");
          settingsStore.save(config);
          return config;
        },
        saved -> {
          boolean sourceChanged = settings.get().downloadSource() != saved.downloadSource();
          settings.set(saved);
          if (sourceChanged) {
            versions.clear();
            loaderVersions.clear();
          }
          status.set("设置已保存");
        });
  }

  public void cancel() {
    if (active != null) active.cancel(true);
  }

  private <T> void submit(String title, Work<T> work, Consumer<T> success) {
    if (busy.get()) return;
    error.set("");
    busy.set(true);
    status.set(title);
    progress.set(-1);
    Task<T> task =
        new Task<>() {
          @Override
          protected T call() throws Exception {
            return work.execute(
                new TaskContext() {
                  @Override
                  public void report(String message, double value) {
                    updateMessage(message);
                    updateProgress(value < 0 ? -1 : value, 1);
                  }

                  @Override
                  public boolean cancelled() {
                    return isCancelled();
                  }
                });
          }
        };
    active = task;
    status.bind(task.messageProperty());
    progress.bind(task.progressProperty());
    task.setOnSucceeded(
        event -> {
          finish(task);
          success.accept(task.getValue());
        });
    task.setOnFailed(
        event -> {
          finish(task);
          Throwable cause = task.getException();
          while (cause instanceof ExecutionException || cause instanceof CompletionException) {
            cause = cause.getCause();
          }
          String message = Objects.toString(cause.getMessage(), cause.getClass().getSimpleName());
          error.set(message);
          status.set(title + "失败");
        });
    task.setOnCancelled(
        event -> {
          finish(task);
          status.set("任务已取消；已有缓存和诊断文件保留");
        });
    executor.execute(task);
  }

  private void finish(Task<?> task) {
    status.unbind();
    progress.unbind();
    status.set(task.getMessage().isBlank() ? "就绪" : task.getMessage());
    progress.set(0);
    busy.set(false);
    active = null;
  }

  @Override
  public void close() {
    cancel();
    executor.shutdownNow();
  }
}
