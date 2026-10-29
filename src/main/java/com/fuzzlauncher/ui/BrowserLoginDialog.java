package com.fuzzlauncher.ui;

import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.*;

/** Waits for the system browser to return authorization; no code or credential entry. */
final class BrowserLoginDialog extends Dialog<ButtonType> {
  BrowserLoginDialog(Stage owner, LauncherViewModel vm) {
    initOwner(owner);
    initModality(Modality.WINDOW_MODAL);
    RetroDialogs.decorate(this, owner, "微软登录");
    getDialogPane().setId("browser-login-dialog");
    Label instruction = new Label("请在默认浏览器中完成微软登录。网页授权完成后，启动器会自动继续验证游戏账号。");
    instruction.setWrapText(true);
    Label status = new Label();
    status.setId("browser-login-status");
    status.textProperty().bind(vm.statusProperty());
    status.setWrapText(true);
    ProgressBar progress = new ProgressBar();
    progress.progressProperty().bind(vm.progressProperty());
    progress.setMaxWidth(Double.MAX_VALUE);
    VBox content = new VBox(16, instruction, progress, status);
    content.setPadding(new Insets(12));
    content.setPrefWidth(560);
    getDialogPane().setContent(content);
    ButtonType cancel = new ButtonType("取消登录", ButtonBar.ButtonData.CANCEL_CLOSE);
    getDialogPane().getButtonTypes().add(cancel);
    ((Button) getDialogPane().lookupButton(cancel)).setOnAction(event -> vm.cancel());
    setOnCloseRequest(
        event -> {
          if (vm.loggingInProperty().get()) vm.cancel();
        });
    setOnHidden(
        event -> {
          status.textProperty().unbind();
          progress.progressProperty().unbind();
        });
  }
}
