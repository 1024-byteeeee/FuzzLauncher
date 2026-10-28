package com.fuzzlauncher.ui;

import javafx.beans.binding.Bindings;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.shape.*;
import javafx.stage.*;

/** Progress stays visible until the installation task has actually finished or cancelled. */
final class InstallProgressDialog extends Dialog<ButtonType> {
  private double dragX, dragY;

  InstallProgressDialog(Stage owner, LauncherViewModel vm) {
    initOwner(owner);
    initModality(Modality.WINDOW_MODAL);
    initStyle(StageStyle.UNDECORATED);
    setTitle("Download");
    Label title = RetroPage.label("Download", "title");
    Region space = new Region();
    HBox.setHgrow(space, Priority.ALWAYS);
    SVGPath glyph = new SVGPath();
    glyph.setContent("M0 0 H3 L6 3 L9 0 H12 L8 5 L12 10 H9 L6 7 L3 10 H0 L4 5 Z");
    glyph.setFillRule(FillRule.EVEN_ODD);
    glyph.setSmooth(false);
    glyph.setMouseTransparent(true);
    StackPane graphic = new StackPane(glyph);
    graphic.setMinSize(14, 14);
    graphic.setPrefSize(14, 14);
    graphic.setMaxSize(14, 14);
    Button close = new Button();
    close.setGraphic(graphic);
    close.setFocusTraversable(false);
    close.setAccessibleText("关闭");
    close.getStyleClass().add("window-control");
    close.setOnAction(event -> close());
    HBox titleBar = new HBox(9, RetroPage.icon("grass", 28), title, space, close);
    titleBar.setAlignment(Pos.CENTER_LEFT);
    titleBar.getStyleClass().add("title-bar");
    titleBar.setOnMousePressed(
        event -> {
          Window window = getDialogPane().getScene().getWindow();
          dragX = event.getScreenX() - window.getX();
          dragY = event.getScreenY() - window.getY();
        });
    titleBar.setOnMouseDragged(
        event -> {
          Window window = getDialogPane().getScene().getWindow();
          window.setX(event.getScreenX() - dragX);
          window.setY(event.getScreenY() - dragY);
        });
    Label heading = new Label("正在下载并安装实例");
    VBox.setMargin(heading, new Insets(12));
    getDialogPane().setHeader(new VBox(titleBar, heading));
    getDialogPane().getStyleClass().addAll("window", "download-window");
    setOnShown(
        event ->
            ((Stage) getDialogPane().getScene().getWindow()).getIcons().setAll(owner.getIcons()));
    Label message = new Label();
    message.setWrapText(true);
    message.setMaxWidth(500);
    message.textProperty().bind(vm.statusProperty());
    ProgressBar bar = new ProgressBar();
    bar.setMaxWidth(Double.MAX_VALUE);
    bar.progressProperty().bind(vm.progressProperty());
    Label percent = new Label();
    percent
        .textProperty()
        .bind(
            Bindings.createStringBinding(
                () ->
                    vm.progressProperty().get() < 0
                        ? "正在准备…"
                        : String.format(
                            java.util.Locale.ROOT, "%.0f%%", vm.progressProperty().get() * 100),
                vm.progressProperty()));
    VBox content = new VBox(16, message, bar, percent);
    content.setPadding(new Insets(12));
    content.setPrefWidth(520);
    getDialogPane().setContent(content);
    getDialogPane().setId("install-progress-dialog");
    getDialogPane()
        .getStylesheets()
        .add(LauncherView.class.getResource("/com/fuzzlauncher/retro.css").toExternalForm());
    ButtonType cancel = new ButtonType("取消下载", ButtonBar.ButtonData.CANCEL_CLOSE);
    getDialogPane().getButtonTypes().add(cancel);
    Button cancelButton = (Button) getDialogPane().lookupButton(cancel);
    cancelButton.addEventFilter(
        javafx.event.ActionEvent.ACTION,
        event -> {
          vm.cancel();
          cancelButton.setDisable(true);
          event.consume();
        });
    setOnCloseRequest(
        event -> {
          if (vm.installingProperty().get()) {
            vm.cancel();
            event.consume();
          }
        });
    setOnHidden(
        event -> {
          message.textProperty().unbind();
          bar.progressProperty().unbind();
          percent.textProperty().unbind();
        });
  }
}
