package com.fuzzlauncher.ui;

import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.*;

/** Progress stays visible until the installation task has actually finished or cancelled. */
final class InstallProgressDialog extends Dialog<ButtonType> {
  InstallProgressDialog(Stage owner, LauncherViewModel vm) {
    initOwner(owner);
    initModality(Modality.WINDOW_MODAL);
    setTitle("下载实例");
    setHeaderText("正在下载并安装实例");
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
