package com.fuzzlauncher;

import com.fuzzlauncher.application.*;
import com.fuzzlauncher.domain.*;
import com.fuzzlauncher.infrastructure.*;
import com.fuzzlauncher.infrastructure.auth.MicrosoftAuthentication;
import com.fuzzlauncher.infrastructure.game.*;
import com.fuzzlauncher.ui.*;
import java.nio.file.Path;
import java.util.*;
import javafx.application.*;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.stage.*;

/** Composition root. All dependencies are explicit constructor parameters. */
public final class FuzzApplication extends Application {
  private LauncherViewModel viewModel;
  private java.nio.channels.FileChannel instanceChannel;
  private java.nio.channels.FileLock instanceLock;
  private List<String> restartCommand;

  @Override
  public void start(Stage stage) {
    try {
      Path data = DataLocations.resolve();
      java.nio.file.Files.createDirectories(data);
      instanceChannel =
          java.nio.channels.FileChannel.open(
              data.resolve("launcher.lock"),
              java.nio.file.StandardOpenOption.CREATE,
              java.nio.file.StandardOpenOption.WRITE);
      instanceLock = instanceChannel.tryLock();
      if (instanceLock == null) throw new java.io.IOException("此数据目录已有 Fuzz Launcher 运行，请先关闭已有窗口");
      LauncherLog.configure(data);
      SettingsStore settings = new SettingsStore(data);
      java.nio.file.Files.createDirectories(Path.of(settings.load().gameRoot()));
      InstanceRepository instances = new JsonInstanceRepository(data);
      HttpTransport http = new HttpTransport();
      http.setDownloadSource(settings.load().downloadSource());
      VersionCatalog catalog = new VersionCatalog(http);
      VersionResolver resolver = new VersionResolver();
      GameFiles files = new GameFiles(http);
      JavaRuntimeManager runtimes = new JavaRuntimeManager(http, data.resolve("runtimes"));
      MicrosoftAuthentication authentication =
          new MicrosoftAuthentication(new HttpTransport(), data);
      Map<LoaderType, LoaderInstaller> installers =
          Map.of(
              LoaderType.FABRIC,
              new FabricInstaller(http),
              LoaderType.FORGE,
              new OfficialLoaderInstaller(LoaderType.FORGE, http, runtimes, resolver),
              LoaderType.NEOFORGE,
              new OfficialLoaderInstaller(LoaderType.NEOFORGE, http, runtimes, resolver));
      InstallGame install = new InstallGame(instances, files, resolver, installers);
      LaunchGame launch =
          new LaunchGame(
              authentication,
              new IsolationPolicy(),
              resolver,
              files,
              runtimes,
              new LaunchArguments(),
              data.resolve("logs"));
      viewModel =
          new LauncherViewModel(
              settings,
              instances,
              catalog,
              authentication,
              install,
              launch,
              new SkinAvatars(data, new HttpTransport()));
      viewModel.configureModpacks(new Modpacks(http, catalog, install, instances));
      viewModel.onRestartRequired(
          () -> {
            try {
              restartCommand = LauncherRestart.command();
              Platform.exit();
            } catch (java.io.IOException ex) {
              RetroDialogs.alert(
                      stage, Alert.AlertType.ERROR, "自动重启失败", "请手动重新打开启动器：" + ex.getMessage())
                  .showAndWait();
            }
          });
      viewModel
          .settingsProperty()
          .addListener(
              (observable, oldSettings, newSettings) ->
                  http.setDownloadSource(newSettings.downloadSource()));
      stage.initStyle(StageStyle.UNDECORATED);
      Scene scene = new Scene(new LauncherView(stage, viewModel), 1200, 860);
      scene.getStylesheets().add(getClass().getResource("retro.css").toExternalForm());
      stage.setScene(scene);
      stage.setTitle(Launcher.TITLE);
      stage
          .getIcons()
          .add(new javafx.scene.image.Image(getClass().getResourceAsStream("launcher.png")));
      stage.setMinWidth(1100);
      stage.setMinHeight(780);
      stage.setOnCloseRequest(
          event -> {
            if (viewModel.savingSettingsProperty().get() || viewModel.isCommitting())
              event.consume();
          });
      LauncherVisibilityController visibility =
          new LauncherVisibilityController(stage, Platform::exit);
      viewModel.onGameLifecycle(visibility::started, visibility::exited);
      stage.show();
      if (Boolean.getBoolean("fuzz.snapshot"))
        Platform.runLater(
            () -> {
              try {
                javax.imageio.ImageIO.write(
                    javafx.embed.swing.SwingFXUtils.fromFXImage(scene.snapshot(null), null),
                    "png",
                    Path.of("ui-preview.png").toFile());
              } catch (java.io.IOException ex) {
                throw new java.io.UncheckedIOException(ex);
              }
              Platform.exit();
            });
    } catch (Exception ex) {
      RetroDialogs.alert(stage, Alert.AlertType.ERROR, "启动器初始化失败", ex.getMessage()).showAndWait();
      Platform.exit();
    }
  }

  @Override
  public void stop() {
    if (viewModel != null) viewModel.close();
    LauncherLog.close();
    try {
      if (instanceLock != null && instanceLock.isValid()) instanceLock.release();
      if (instanceChannel != null) instanceChannel.close();
    } catch (java.io.IOException ex) {
      System.err.println("无法关闭启动器目录锁: " + ex.getMessage());
    }
    if (restartCommand != null) {
      List<String> command = restartCommand;
      restartCommand = null;
      try {
        LauncherRestart.launch(command);
      } catch (java.io.IOException ex) {
        RetroDialogs.alert(null, Alert.AlertType.ERROR, "自动重启失败", "请手动重新打开启动器：" + ex.getMessage())
            .showAndWait();
      }
    }
  }
}
