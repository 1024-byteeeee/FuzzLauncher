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
    ComboBox<IsolationMode> mode = new ComboBox<>();
    mode.getItems().setAll(IsolationMode.values());
    mode.setValue(config.isolation());
    mode.setMaxWidth(Double.MAX_VALUE);
    Label hint = new Label("已有实例时游戏根目录固定；切换隔离模式不会自动移动已有数据。");
    hint.setWrapText(true);
    hint.setStyle("-fx-font-size: 15px;");
    Button save =
        button(
            "保存设置",
            () -> {
              try {
                if (root.getText().isBlank()) throw new IllegalArgumentException("游戏根目录不能为空");
                Path normalized = Path.of(root.getText()).toAbsolutePath().normalize();
                if (mode.getValue() != config.isolation()
                    && !confirm("更改全局隔离模式", "跟随全局设置的实例将切换游戏目录。\n旧存档和模组保留在原目录，不会自动迁移。是否继续？")) return;
                vm.saveSettings(new LauncherSettings(normalized.toString(), mode.getValue()));
              } catch (IllegalArgumentException ex) {
                message(Alert.AlertType.ERROR, "设置无效", ex.getMessage());
              }
            });
    save.disableProperty().bind(vm.busyProperty().or(vm.gameRunningProperty()));
    Button data = button("打开启动器数据目录", () -> openFolder(vm.dataDirectory()));
    VBox content =
        new VBox(
            25,
            label("启动器设置", "section-title"),
            form(new String[] {"游戏根目录:", "默认隔离:"}, new Node[] {root, mode}),
            hint,
            new HBox(10, save, data));
    content.setPadding(new Insets(30));
    return content;
  }
}
