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
  private Object catalogOwner;
  private boolean catalogTask;
  private final BooleanProperty installing = new SimpleBooleanProperty();
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
  private Runnable restart = () -> {};

  public void onRestartRequired(Runnable action) {
    restart = Objects.requireNonNull(action);
  }

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
    for (GameInstance instance : instances) {
      try {
        InstanceDirectories.migrate(Path.of(settings.get().gameRoot()), instance);
        InstanceCatalog.remember(Path.of(settings.get().gameRoot()), instance);
      } catch (IOException ex) {
        status.set("实例目录迁移未完成：" + ex.getMessage());
      }
    }
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
    submit(
        "获取游戏版本",
        context -> {
          context.report("读取官方版本清单", -1);
          return catalog.games();
        },
        versions::setAll,
        owner,
        true);
  }

  public void loadLoaders(LoaderType type, GameVersion game) {
    loadLoaders(type, game, null);
  }

  public void loadLoaders(LoaderType type, GameVersion game, Object owner) {
    if (busy.get()) return;
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
        },
        owner,
        true);
  }

  public void install(GameVersion game, LoaderType type, String loaderVersion, String name) {
    if (busy.get()) return;
    if (game == null) {
      error.set("请选择游戏版本");
      return;
    }
    Path root = Path.of(settings.get().gameRoot());
    installing.set(true);
    submit(
        "安装游戏",
        context -> install.execute(root, game, type, loaderVersion, name, context),
        instance -> {
          instances.add(instance);
          selected.set(instance);
        });
  }

  public void login() {
    if (!canLogin(false)) return;
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
    if (!canLogin(true)) return;
    submit(
        "离线登录",
        context -> authentication.loginOffline(name, context),
        session -> {
          account.set(session.account());
          status.set("已使用离线账号: " + session.account().name());
        });
  }

  private boolean canLogin(boolean offline) {
    if (busy.get() || gameRunning.get()) return false;
    Account current = account.get();
    if (current != null && current.isOffline() != offline) {
      error.set(current.isOffline() ? "请先退出离线账号，再登录微软账号" : "请先退出微软账号，再登录离线账号");
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
    submit(
        "启动游戏",
        context -> launch.execute(config, instance, identity, context),
        running -> {
          gameRunning.set(true);
          status.set("游戏运行中");
          running
              .process()
              .onExit()
              .thenAccept(
                  process ->
                      Platform.runLater(
                          () -> {
                            gameRunning.set(false);
                            status.set("游戏已退出，退出码 " + process.exitValue());
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
          GameInstance previous =
              repository.findAll().stream()
                  .filter(item -> item.id().equals(instance.id()))
                  .findFirst()
                  .orElseThrow(() -> new IOException("实例已不存在"));
          InstanceDirectories.save(
              Path.of(settings.get().gameRoot()), previous, instance, repository);
          try {
            InstanceCatalog.remember(Path.of(settings.get().gameRoot()), instance);
          } catch (IOException ex) {
            LauncherLog.LOGGER.warning("实例目录配置备份失败：" + ex.getMessage());
          }
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
        "删除实例",
        context -> {
          InstanceCatalog.delete(Path.of(settings.get().gameRoot()), instance, repository, context);
          return true;
        },
        ignored -> {
          instances.remove(instance);
          selected.set(instances.isEmpty() ? null : instances.getFirst());
          status.set("实例及其独立目录数据已永久删除");
        });
  }

  public void refreshInstances() {
    String selectedId = selected.get() == null ? null : selected.get().id();
    submit(
        "刷新实例",
        context -> InstanceCatalog.refresh(Path.of(settings.get().gameRoot()), repository),
        values -> {
          instances.setAll(values);
          selected.set(
              values.stream()
                  .filter(item -> item.id().equals(selectedId))
                  .findFirst()
                  .orElse(values.isEmpty() ? null : values.getFirst()));
          status.set("已读取 " + values.size() + " 个可用实例");
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
          Path requested =
              config.dataDirectory().isBlank() ? dataDirectory() : Path.of(config.dataDirectory());
          try {
            DataLocations.configure(DataLocations.bootstrapDirectory(), dataDirectory(), requested);
          } catch (IOException ex) {
            settingsStore.save(settings.get());
            throw ex;
          }
          return config;
        },
        saved -> {
          boolean sourceChanged = settings.get().downloadSource() != saved.downloadSource();
          settings.set(saved);
          if (sourceChanged) {
            versions.clear();
            loaderVersions.clear();
          }
          boolean changed =
              !saved.dataDirectory().isBlank()
                  && !Path.of(saved.dataDirectory())
                      .toAbsolutePath()
                      .normalize()
                      .equals(dataDirectory());
          status.set(changed ? "设置已保存，正在重启启动器…" : "设置已保存");
          if (changed) restart.run();
        });
  }

  public void cancel() {
    if (active != null) active.cancel(true);
  }

  public void cancelCatalogRequests(Object owner) {
    if (active != null && catalogTask && catalogOwner == owner) {
      Task<?> task = active;
      task.cancel(true);
      if (active == task) finish(task);
      if (!busy.get()) status.set("已停止获取版本列表");
    }
  }

  private <T> void submit(String title, Work<T> work, Consumer<T> success) {
    submit(title, work, success, null, false);
  }

  private <T> void submit(
      String title, Work<T> work, Consumer<T> success, Object owner, boolean catalog) {
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
    catalogOwner = owner;
    catalogTask = catalog;
    LauncherLog.LOGGER.info(title);
    status.bind(task.messageProperty());
    progress.bind(task.progressProperty());
    task.setOnSucceeded(
        event -> {
          if (!finish(task)) return;
          success.accept(task.getValue());
          LauncherLog.LOGGER.info(title + "完成");
        });
    task.setOnFailed(
        event -> {
          if (!finish(task)) return;
          Throwable cause = task.getException();
          while (cause instanceof ExecutionException || cause instanceof CompletionException) {
            cause = cause.getCause();
          }
          String message = Objects.toString(cause.getMessage(), cause.getClass().getSimpleName());
          status.set(title + "失败");
          LauncherLog.LOGGER.warning(title + "失败：" + message);
          error.set(message);
        });
    task.setOnCancelled(
        event -> {
          if (!finish(task)) return;
          status.set(catalog ? "已停止获取版本列表" : "任务已取消");
          LauncherLog.LOGGER.info(title + "已取消");
        });
    executor.execute(task);
  }

  private boolean finish(Task<?> task) {
    if (active != task) return false;
    active = null;
    catalogTask = false;
    catalogOwner = null;
    status.unbind();
    progress.unbind();
    status.set(task.getMessage().isBlank() ? "就绪" : task.getMessage());
    progress.set(0);
    installing.set(false);
    busy.set(false);
    return true;
  }

  @Override
  public void close() {
    cancel();
    executor.shutdownNow();
  }
}
