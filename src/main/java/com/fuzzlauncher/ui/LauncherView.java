package com.fuzzlauncher.ui;

import com.fuzzlauncher.domain.*;
import java.nio.file.*;
import java.util.*;
import javafx.beans.binding.Bindings;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

/** Retro presentation only. Operations and persistent state live in the ViewModel. */
public final class LauncherView extends RetroPage {
  private final BorderPane workspace = new BorderPane();
  private final List<Button> navigation = new ArrayList<>();
  private final Label title = new Label("游戏实例");
  private double dragX, dragY;

  public LauncherView(Stage stage, LauncherViewModel vm) {
    super(stage, vm, ignored -> {});
    getStyleClass().add("window");
    setTop(chrome());
    setLeft(navigation());
    setCenter(workspace);
    setBottom(status());
    vm.errorProperty()
        .addListener(
            (o, old, value) -> {
              if (value != null && !value.isBlank()) message(Alert.AlertType.ERROR, "操作未完成", value);
            });
    showPage(0);
  }

  private Node chrome() {
    HBox bar = new HBox(9, icon("grass", 28), label("Fuzz Launcher", "title"));
    bar.setAlignment(Pos.CENTER_LEFT);
    bar.getStyleClass().add("title-bar");
    Region space = new Region();
    HBox.setHgrow(space, Priority.ALWAYS);
    bar.getChildren().add(space);
    Button min = button("_", () -> stage.setIconified(true)),
        max = button("□", () -> stage.setMaximized(!stage.isMaximized())),
        close =
            button(
                "×",
                () -> {
                  if (!vm.busyProperty().get() || confirm("退出启动器", "当前任务将被取消。是否退出？")) {
                    vm.close();
                    stage.close();
                  }
                });
    for (Button b : List.of(min, max, close)) {
      b.getStyleClass().add("window-control");
      bar.getChildren().add(b);
    }
    bar.setOnMousePressed(
        e -> {
          dragX = e.getScreenX() - stage.getX();
          dragY = e.getScreenY() - stage.getY();
        });
    bar.setOnMouseDragged(
        e -> {
          if (!stage.isMaximized()) {
            stage.setX(e.getScreenX() - dragX);
            stage.setY(e.getScreenY() - dragY);
          }
        });
    HBox menus = new HBox(12);
    menus.getStyleClass().add("menu-bar");
    MenuButton file = new MenuButton("文件(F)");
    MenuItem download = new MenuItem("新建 / 下载实例");
    download.setOnAction(e -> showPage(1));
    MenuItem folder = new MenuItem("打开游戏根目录");
    folder.setOnAction(e -> openFolder(Path.of(vm.settingsProperty().get().gameRoot())));
    file.getItems().addAll(download, folder);
    MenuButton view = new MenuButton("查看(V)");
    for (int i = 0; i < 4; i++) {
      int page = i;
      MenuItem item = new MenuItem(new String[] {"游戏实例", "下载", "账号", "设置"}[i]);
      item.setOnAction(e -> showPage(page));
      view.getItems().add(item);
    }
    Button logs = button("日志(T)", () -> openFolder(vm.dataDirectory().resolve("logs")));
    logs.getStyleClass().add("menu-button");
    Button help =
        button(
            "帮助(H)",
            () ->
                message(
                    Alert.AlertType.INFORMATION,
                    "Fuzz Launcher 0.2.0",
                    "仅支持微软正版账号。\n"
                        + "下载页安装原版、Fabric、Forge 或 NeoForge。\n"
                        + "实例设置控制版本隔离与内存。\n"
                        + "游戏 Java 会自动检测或下载，和启动器内置 Java 分开管理。"));
    help.getStyleClass().add("menu-button");
    menus.getChildren().addAll(file, view, logs, help);
    return new VBox(bar, menus);
  }

  private Node navigation() {
    VBox box = new VBox(4);
    box.getStyleClass().add("navigation");
    box.setPrefWidth(215);
    box.setMinWidth(215);
    box.setPadding(new Insets(15, 10, 0, 5));
    String[] names = {"游戏实例", "下载", "账号", "设置"}, icons = {"grass", "globe", "player", "wrench"};
    for (int i = 0; i < names.length; i++) {
      int page = i;
      Button b = button(names[i], () -> showPage(page));
      b.setGraphic(icon(icons[i], 36));
      b.setGraphicTextGap(12);
      b.setAlignment(Pos.CENTER_LEFT);
      b.setMaxWidth(Double.MAX_VALUE);
      b.getStyleClass().add("nav-button");
      navigation.add(b);
      box.getChildren().add(b);
    }
    return box;
  }

  @Override
  protected void showPage(int page) {
    for (int i = 0; i < navigation.size(); i++) {
      navigation.get(i).getStyleClass().remove("selected-nav");
      if (i == page) navigation.get(i).getStyleClass().add("selected-nav");
    }
    title.setText(new String[] {"游戏实例", "下载游戏", "微软正版账号", "启动器设置"}[page]);
    workspace.setCenter(
        switch (page) {
          case 1 -> new DownloadsPage(stage, vm, this::showPage);
          case 2 -> new AccountsPage(stage, vm, this::showPage);
          case 3 -> new SettingsPage(stage, vm, this::showPage);
          default -> new InstancesPage(stage, vm, this::showPage);
        });
  }

  private Node status() {
    Label identity = new Label();
    identity
        .textProperty()
        .bind(
            Bindings.createStringBinding(
                () ->
                    vm.accountProperty().get() == null
                        ? "未登录"
                        : "用户: " + vm.accountProperty().get().name(),
                vm.accountProperty()));
    identity.getStyleClass().add("status-cell");
    Label status = new Label();
    status.textProperty().bind(vm.statusProperty());
    status.setMaxWidth(Double.MAX_VALUE);
    HBox.setHgrow(status, Priority.ALWAYS);
    status.getStyleClass().add("status-cell");
    ProgressBar progress = new ProgressBar();
    progress.progressProperty().bind(vm.progressProperty());
    progress.setPrefWidth(150);
    progress.setMaxHeight(20);
    Button cancel = button("取消", vm::cancel);
    cancel.disableProperty().bind(vm.busyProperty().not());
    HBox box = new HBox(5, identity, status, progress, cancel);
    box.setAlignment(Pos.CENTER_LEFT);
    box.getStyleClass().add("status-bar");
    return box;
  }
}
