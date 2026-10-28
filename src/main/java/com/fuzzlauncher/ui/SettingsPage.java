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
    TextField client = new TextField(Objects.toString(config.microsoftClientId(), ""));
    client.setPromptText("Fuzz 自有的 Microsoft 应用 Client ID");
    Label hint =
        new Label(
            "Client ID 是公开的应用标识，无需 Client Secret。\n"
                + "需注册个人微软账号应用、配置桌面应用 localhost 回调，并申请 Minecraft API 准入。\n"
                + "已有实例时游戏根目录固定；切换隔离模式不会自动移动已有数据。");
    hint.setWrapText(true);
    hint.setStyle("-fx-font-size: 15px;");
    Button save =
        button(
            "保存设置",
            () -> {
              try {
                if (root.getText().isBlank()) throw new IllegalArgumentException("游戏根目录不能为空");
                Path normalized = Path.of(root.getText()).toAbsolutePath().normalize();
                String id = client.getText().strip();
                if (!id.isBlank() && !id.matches("[0-9a-fA-F-]{36}"))
                  throw new IllegalArgumentException("Client ID 应为应用注册页面显示的 UUID");
                if (mode.getValue() != config.isolation()
                    && !confirm("更改全局隔离模式", "跟随全局设置的实例将切换游戏目录。\n旧存档和模组保留在原目录，不会自动迁移。是否继续？")) return;
                vm.saveSettings(new LauncherSettings(normalized.toString(), mode.getValue(), id));
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
            form(new String[] {"游戏根目录:", "默认隔离:", "Client ID:"}, new Node[] {root, mode, client}),
            hint,
            new HBox(10, save, data));
    content.setPadding(new Insets(30));
    return content;
  }
}
