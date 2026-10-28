package com.fuzzlauncher.ui;

import com.fuzzlauncher.domain.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

/** SettingsPage presentation; all application operations are delegated to the ViewModel. */
public final class SettingsPage extends RetroPage {
  public SettingsPage(Stage stage, LauncherViewModel vm, Consumer<Integer> navigate) {
    super(stage, vm, navigate);
    setCenter(build());
  }

  private Node build() {
    LauncherSettings config = vm.settingsProperty().get();
    TextField root = new TextField(config.gameRoot());
    root.setEditable(vm.instances().isEmpty());
    TextField dataPath =
        new TextField(
            config.dataDirectory().isBlank()
                ? vm.dataDirectory().toString()
                : config.dataDirectory());
    dataPath.setId("launcher-data-path");
    dataPath.setMinWidth(0);
    HBox.setHgrow(dataPath, Priority.ALWAYS);
    Button browse =
        button(
            "浏览",
            () -> {
              Path initial;
              try {
                initial = Path.of(dataPath.getText());
              } catch (InvalidPathException ex) {
                initial = vm.dataDirectory();
              }
              RetroPathDialog.directory(stage, "选择启动器数据目录", initial)
                  .ifPresent(chosen -> dataPath.setText(chosen.toString()));
            });
    browse.setMinWidth(Region.USE_PREF_SIZE);
    HBox dataControl = new HBox(6, dataPath, browse);
    dataControl.setMinWidth(0);
    ComboBox<IsolationMode> mode = new RetroComboBox<>();
    mode.getItems().setAll(IsolationMode.values());
    mode.setValue(config.isolation());
    mode.setMaxWidth(Double.MAX_VALUE);
    ComboBox<DownloadSource> source = new RetroComboBox<>();
    source.setId("download-source");
    source.getItems().setAll(DownloadSource.values());
    source.setValue(config.downloadSource());
    source.setMaxWidth(Double.MAX_VALUE);
    Button save =
        button(
            "保存设置",
            () -> {
              try {
                if (root.getText().isBlank()) throw new IllegalArgumentException("游戏根目录不能为空");
                Path normalized = Path.of(root.getText()).toAbsolutePath().normalize();
                Path requested =
                    dataPath.getText().isBlank()
                        ? vm.dataDirectory()
                        : Path.of(dataPath.getText()).toAbsolutePath().normalize();
                if (mode.getValue() != config.isolation()
                    && !confirm("更改全局隔离模式", "跟随全局设置的实例将切换游戏目录。\n旧存档和模组保留在原目录，不会自动迁移。是否继续？")) return;
                vm.saveSettings(
                    new LauncherSettings(
                        normalized.toString(),
                        mode.getValue(),
                        source.getValue(),
                        requested.toString()));
              } catch (IllegalArgumentException ex) {
                message(Alert.AlertType.ERROR, "设置无效", ex.getMessage());
              }
            });
    save.disableProperty().bind(vm.busyProperty().or(vm.gameRunningProperty()));
    Button data = button("打开启动器数据目录", () -> openFolder(vm.dataDirectory()));
    Button logs = button("打开启动器日志目录", () -> openFolder(vm.dataDirectory().resolve("logs")));
    VBox content =
        new VBox(
            25,
            label("启动器设置", "section-title"),
            form(
                new String[] {"游戏根目录:", "数据文件目录:", "默认隔离:", "下载源:"},
                new Node[] {root, dataControl, mode, source}),
            new HBox(10, save, data, logs));
    content.setPadding(new Insets(30));
    return content;
  }
}
