package com.fuzzlauncher.ui;

import com.fuzzlauncher.domain.*;
import java.util.*;
import javafx.beans.binding.Bindings;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;

/** Retro presentation only. Operations and persistent state live in the ViewModel. */
public final class LauncherView extends RetroPage {
  private final BorderPane workspace = new BorderPane();
  private final List<Button> navigation = new ArrayList<>();
  private final Label title = new Label("游戏实例");
  private double dragX, dragY;
  private InstallProgressDialog installDialog;

  public LauncherView(Stage stage, LauncherViewModel vm) {
    super(stage, vm, ignored -> {});
    getStyleClass().add("window");
    setTop(chrome());
    setLeft(navigation());
    setCenter(workspace);
    setBottom(status());
    vm.installingProperty()
        .addListener(
            (observable, old, installing) -> {
              if (installing) {
                installDialog = new InstallProgressDialog(stage, vm);
                installDialog.show();
              } else if (installDialog != null) {
                installDialog.close();
                installDialog = null;
              }
            });
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
    Button min = windowControl("M0 0 H12 V2 H0 Z", () -> stage.setIconified(true)),
        max =
            windowControl(
                "M0 0 H12 V10 H0 Z M2 3 V8 H10 V3 Z",
                () -> stage.setMaximized(!stage.isMaximized())),
        close =
            windowControl(
                "M0 0 H3 L6 3 L9 0 H12 L8 5 L12 10 H9 L6 7 L3 10 H0 L4 5 Z",
                () -> {
                  if (!vm.busyProperty().get() || confirm("退出启动器", "当前任务将被取消。是否退出？")) {
                    vm.close();
                    stage.close();
                  }
                });
    min.setAccessibleText("最小化");
    max.setAccessibleText("最大化或还原");
    close.setAccessibleText("关闭");
    for (Button b : List.of(min, max, close)) {
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
    return bar;
  }

  private Button windowControl(String path, Runnable action) {
    SVGPath glyph = new SVGPath();
    glyph.setContent(path);
    glyph.setFillRule(javafx.scene.shape.FillRule.EVEN_ODD);
    glyph.setSmooth(false);
    glyph.setMouseTransparent(true);
    StackPane graphic = new StackPane(glyph);
    graphic.setMinSize(14, 14);
    graphic.setPrefSize(14, 14);
    graphic.setMaxSize(14, 14);
    Button control = button("", action);
    control.setGraphic(graphic);
    control.getStyleClass().add("window-control");
    return control;
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
    title.setText(new String[] {"游戏实例", "下载游戏", "微软账号", "启动器设置"}[page]);
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
                        : (vm.accountProperty().get().isOffline() ? "离线: " : "用户: ")
                            + vm.accountProperty().get().name(),
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
    progress.minHeightProperty().bind(status.heightProperty());
    progress.prefHeightProperty().bind(status.heightProperty());
    progress.maxHeightProperty().bind(status.heightProperty());
    Button cancel = button("取消", vm::cancel);
    cancel.disableProperty().bind(vm.busyProperty().not());
    HBox box = new HBox(5, identity, status, progress, cancel);
    box.setAlignment(Pos.CENTER_LEFT);
    box.getStyleClass().add("status-bar");
    return box;
  }
}
