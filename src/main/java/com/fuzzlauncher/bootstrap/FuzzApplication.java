package com.fuzzlauncher.bootstrap;

import com.fuzzlauncher.application.model.LauncherInfo;
import com.fuzzlauncher.application.port.InstanceRepository;
import com.fuzzlauncher.domain.game.LoaderType;
import com.fuzzlauncher.domain.instance.IsolationPolicy;
import com.fuzzlauncher.infrastructure.auth.MicrosoftAuthentication;
import com.fuzzlauncher.infrastructure.auth.SkinAvatars;
import com.fuzzlauncher.infrastructure.config.LauncherConfig;
import com.fuzzlauncher.infrastructure.filesystem.BaseVersions;
import com.fuzzlauncher.infrastructure.game.catalog.VersionCatalog;
import com.fuzzlauncher.infrastructure.game.catalog.VersionResolver;
import com.fuzzlauncher.infrastructure.game.install.FabricInstaller;
import com.fuzzlauncher.infrastructure.game.install.GameFiles;
import com.fuzzlauncher.infrastructure.game.install.LoaderInstaller;
import com.fuzzlauncher.infrastructure.game.install.LocalGameInstallation;
import com.fuzzlauncher.infrastructure.game.install.OfficialLoaderInstaller;
import com.fuzzlauncher.infrastructure.game.launch.LaunchArguments;
import com.fuzzlauncher.infrastructure.game.launch.LocalGameLauncher;
import com.fuzzlauncher.infrastructure.game.runtime.JavaRuntimeManager;
import com.fuzzlauncher.infrastructure.modpack.LocalModpacks;
import com.fuzzlauncher.infrastructure.network.HttpTransport;
import com.fuzzlauncher.infrastructure.persistence.JsonInstanceRepository;
import com.fuzzlauncher.infrastructure.persistence.SettingsStore;
import com.fuzzlauncher.infrastructure.persistence.WindowStateStore;
import com.fuzzlauncher.infrastructure.platform.LauncherLog;
import com.fuzzlauncher.infrastructure.platform.LauncherPaths;
import com.fuzzlauncher.infrastructure.platform.LauncherRestart;
import com.fuzzlauncher.infrastructure.platform.windows.WindowsWindowMove;
import com.fuzzlauncher.infrastructure.recovery.OperationJournal;
import com.fuzzlauncher.infrastructure.settings.DataLocations;
import com.fuzzlauncher.ui.common.RetroDialogs;
import com.fuzzlauncher.ui.common.WindowDrag;
import com.fuzzlauncher.ui.shell.LauncherView;
import com.fuzzlauncher.ui.shell.LauncherVisibilityController;
import com.fuzzlauncher.ui.viewmodel.LauncherViewModel;

import javafx.application.*;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.image.Image;
import javafx.stage.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import javax.imageio.ImageIO;

/** Composition root. All dependencies are explicit constructor parameters. */
public final class FuzzApplication extends Application {
    private static final double MIN_WIDTH = 1100;
    private static final double MIN_HEIGHT = 890;
    private static final WindowStateStore.State DEFAULT_WINDOW = new WindowStateStore.State(MIN_WIDTH, MIN_HEIGHT, false);
    private LauncherViewModel viewModel;
    private WindowStateStore windowStore;
    private Stage mainStage;
    private double normalWidth;
    private double normalHeight;
    private FileChannel instanceChannel;
    private FileLock instanceLock;
    private FileChannel recoveryChannel;
    private FileLock recoveryLock;
    private List<String> restartCommand;

    @Override
    public void start(Stage stage) {
        WindowDrag.configure(stage, WindowsWindowMove::begin);
        try {
            Platform.setImplicitExit(false);
            Path bootstrap = DataLocations.bootstrapDirectory();
            Files.createDirectories(bootstrap);
            recoveryChannel = FileChannel.open(bootstrap.resolve("recovery.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            recoveryLock = recoveryChannel.tryLock();
            if (recoveryLock == null) {
                throw new IOException("此配置位置已有启动器运行，不能同时恢复操作");
            }
            Path operations = bootstrap.resolve("operations");
            if (!StartupRecovery.operations(stage, operations)) {
                Platform.exit();
                return;
            }
            Path data = DataLocations.resolve();
            Files.createDirectories(data);
            instanceChannel = FileChannel.open(data.resolve("launcher.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            instanceLock = instanceChannel.tryLock();
            if (instanceLock == null) {
                throw new IOException("此数据目录已有 Fuzz Launcher 运行，请先关闭已有窗口");
            }
            LauncherLog.configure(data);
            SettingsStore settings = new SettingsStore(data);
            InstanceRepository instances = new JsonInstanceRepository(data);
            if (!StartupRecovery.configurations(stage, data, () -> {
                LauncherConfig.initialize(data);
                settings.load();
                instances.findAll();
            })) {
                Platform.exit();
                return;
            }
            Files.createDirectories(Path.of(settings.load().gameRoot()));
            HttpTransport http = new HttpTransport();
            http.setDownloadSource(settings.load().downloadSource());
            http.setDownloadConcurrency(settings.load().effectiveDownloadThreads(LauncherConfig.get().network().concurrency()));
            VersionCatalog catalog = new VersionCatalog(http, data.resolve("cache/catalogs"));
            BaseVersions baseVersions = new BaseVersions(LauncherPaths.gameStorageDirectory().resolve("baseVerisons"),
                LauncherPaths.directory().resolve("baseVerisons"));
            VersionResolver resolver = new VersionResolver(baseVersions);
            GameFiles files = new GameFiles(http, baseVersions);
            JavaRuntimeManager runtimes = new JavaRuntimeManager(data.resolve("runtimes"));
            MicrosoftAuthentication authentication = new MicrosoftAuthentication(new HttpTransport(), data);
            Map<LoaderType, LoaderInstaller> installers = Map.of(
                LoaderType.FABRIC, new FabricInstaller(http),
                LoaderType.FORGE, new OfficialLoaderInstaller(LoaderType.FORGE, http, runtimes, resolver),
                LoaderType.NEOFORGE, new OfficialLoaderInstaller(LoaderType.NEOFORGE, http, runtimes, resolver));
            OperationJournal.Store recovery = new OperationJournal.Store(operations, data);
            LocalGameInstallation install = new LocalGameInstallation(instances, files, resolver, installers, recovery);
            LocalGameLauncher launch = new LocalGameLauncher(authentication, new IsolationPolicy(), resolver, files, runtimes,
                new LaunchArguments(baseVersions), data.resolve("logs"));
            viewModel = ViewModels.create(settings, instances, catalog, authentication, install, launch,
                new SkinAvatars(data, new HttpTransport()), new LocalModpacks(http, catalog, install, instances), recovery);
            viewModel.settingsModel().onRestartRequired(() -> {
                try {
                    restartCommand = LauncherRestart.command();
                    Platform.exit();
                } catch (IOException ex) {
                    RetroDialogs.alert(stage, Alert.AlertType.ERROR, "自动重启失败", "请手动重新打开启动器：" + ex.getMessage()).showAndWait();
                }
            });
            viewModel.settingsProperty().addListener((observable, oldSettings, newSettings) -> {
                http.setDownloadSource(newSettings.downloadSource());
                http.setDownloadConcurrency(newSettings.effectiveDownloadThreads(LauncherConfig.get().network().concurrency()));
            });
            stage.initStyle(StageStyle.UNDECORATED);
            windowStore = new WindowStateStore(bootstrap);
            WindowStateStore.State window = loadWindowState();
            var screen = Screen.getPrimary().getVisualBounds();
            normalWidth = Math.clamp(window.width(), MIN_WIDTH, Math.max(MIN_WIDTH, screen.getWidth()));
            normalHeight = Math.clamp(window.height(), MIN_HEIGHT, Math.max(MIN_HEIGHT, screen.getHeight()));
            Scene scene = new Scene(new LauncherView(stage, viewModel), normalWidth, normalHeight);
            scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/com/fuzzlauncher/retro.css")).toExternalForm());
            stage.setScene(scene);
            stage.setTitle(LauncherInfo.TITLE);
            stage.getIcons().add(new Image(Objects.requireNonNull(getClass().getResourceAsStream("/com/fuzzlauncher/launcher.png"))));
            stage.setMinWidth(MIN_WIDTH);
            stage.setMinHeight(MIN_HEIGHT);
            stage.setOnCloseRequest(event -> {
                if (viewModel.savingSettingsProperty().get() || viewModel.isCommitting()) {
                    event.consume();
                }
            });
            LauncherVisibilityController visibility = new LauncherVisibilityController(stage, Platform::exit);
            viewModel.gamesModel().onGameLifecycle(visibility::started, visibility::exited);
            stage.show();
            mainStage = stage;
            stage.widthProperty().addListener((observable, old, width) -> {
                if (!stage.isMaximized() && !stage.isIconified() && width.doubleValue() >= MIN_WIDTH) {
                    normalWidth = width.doubleValue();
                }
            });
            stage.heightProperty().addListener((observable, old, height) -> {
                if (!stage.isMaximized() && !stage.isIconified() && height.doubleValue() >= MIN_HEIGHT) {
                    normalHeight = height.doubleValue();
                }
            });
            if (window.maximized()) {
                stage.setMaximized(true);
            }
            Platform.setImplicitExit(true);
            if (Boolean.getBoolean("fuzz.snapshot")) {
                Platform.runLater(() -> {
                    try {
                        ImageIO.write(SwingFXUtils.fromFXImage(scene.snapshot(null), null), "png", Path.of("ui-preview.png").toFile());
                    } catch (IOException ex) {
                        throw new UncheckedIOException(ex);
                    }
                    Platform.exit();
                });
            }
        } catch (Exception ex) {
            RetroDialogs.alert(stage, Alert.AlertType.ERROR, "启动器初始化失败", ex.getMessage()).showAndWait();
            Platform.exit();
        }
    }

    private WindowStateStore.State loadWindowState() {
        try {
            return windowStore.load().orElse(DEFAULT_WINDOW);
        } catch (IOException | com.google.gson.JsonParseException ex) {
            LauncherLog.LOGGER.warning("无法读取主窗口尺寸：" + ex.getMessage());
            return DEFAULT_WINDOW;
        }
    }

    @Override
    public void stop() {
        if (mainStage != null && !Boolean.getBoolean("fuzz.snapshot")) {
            try {
                windowStore.save(new WindowStateStore.State(normalWidth, normalHeight, mainStage.isMaximized()));
            } catch (IOException ex) {
                LauncherLog.LOGGER.warning("无法保存主窗口尺寸：" + ex.getMessage());
            }
        }
        if (viewModel != null) {
            viewModel.close();
            if (!viewModel.awaitShutdown()) {
                LauncherLog.LOGGER.warning("后台操作尚未退出，目录锁保持到进程结束；下次启动会检查恢复记录");
                LauncherLog.close();
                return;
            }
        }
        LauncherLog.close();
        try {
            if (instanceLock != null && instanceLock.isValid()) {
                instanceLock.release();
            }
            if (instanceChannel != null) {
                instanceChannel.close();
            }
            if (recoveryLock != null && recoveryLock.isValid()) {
                recoveryLock.release();
            }
            if (recoveryChannel != null) {
                recoveryChannel.close();
            }
        } catch (IOException ex) {
            System.err.println("无法关闭启动器目录锁: " + ex.getMessage());
        }
        if (restartCommand != null) {
            List<String> command = restartCommand;
            restartCommand = null;
            try {
                LauncherRestart.launch(command);
            } catch (IOException ex) {
                RetroDialogs.alert(null, Alert.AlertType.ERROR, "自动重启失败", "请手动重新打开启动器：" + ex.getMessage()).showAndWait();
            }
        }
    }
}
