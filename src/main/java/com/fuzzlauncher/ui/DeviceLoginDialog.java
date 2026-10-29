package com.fuzzlauncher.ui;

import com.fuzzlauncher.application.DeviceLogin;
import java.awt.Desktop;
import javafx.animation.*;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.*;

/**
 * One modal challenge window for one login operation, including retrieval and entitlement checks.
 */
final class DeviceLoginDialog extends Dialog<ButtonType> {
  DeviceLoginDialog(Stage owner, LauncherViewModel vm) {
    initOwner(owner);
    initModality(Modality.WINDOW_MODAL);
    RetroDialogs.decorate(this, owner, "微软登录");
    getDialogPane().setId("device-login-dialog");
    TextField code = new TextField();
    code.setId("device-login-code");
    code.setEditable(false);
    code.setPromptText("正在获取登录代码…");
    code.setStyle("-fx-font-size: 28px; -fx-alignment: center;");
    Label url = new Label();
    url.setId("device-login-url");
    url.setWrapText(true);
    Label remaining = new Label();
    remaining.setId("device-login-expiration");
    remaining.setWrapText(true);
    remaining.visibleProperty().bind(remaining.textProperty().isNotEmpty());
    remaining.managedProperty().bind(remaining.visibleProperty());
    Label status = new Label();
    status.textProperty().bind(vm.statusProperty());
    status.setWrapText(true);
    Button copy = new Button("复制代码");
    copy.setId("copy-device-code");
    Button open = new Button("打开登录网页");
    open.setId("open-device-login");
    Button refresh = new Button("刷新代码");
    refresh.setId("refresh-device-code");
    refresh.setOnAction(event -> vm.refreshDeviceLogin());
    Label instruction = new Label("打开微软登录网页，输入下方代码并完成授权。");
    instruction.setWrapText(true);
    Runnable update =
        () -> {
          DeviceLogin challenge = vm.deviceLoginProperty().get();
          boolean authorized = vm.deviceAuthorizedProperty().get();
          boolean valid = !authorized && challenge != null && !challenge.expired();
          instruction.setText(authorized ? "微软授权已完成，请等待游戏账号验证。" : "打开微软登录网页，输入下方代码并完成授权。");
          code.setVisible(!authorized);
          code.setManaged(!authorized);
          url.setVisible(!authorized);
          url.setManaged(!authorized);
          code.setText(challenge == null ? "" : challenge.userCode());
          url.setText(challenge == null ? "" : challenge.verificationUri().toString());
          copy.setDisable(!valid);
          open.setDisable(!valid);
          refresh.setDisable(authorized);
          if (authorized) remaining.setText("微软授权已完成，正在验证游戏账号…");
          else if (challenge == null) remaining.setText("正在联系微软…");
          else remaining.setText(valid ? "" : "代码已过期，请点击刷新代码");
        };
    copy.setOnAction(
        event -> {
          DeviceLogin challenge = vm.deviceLoginProperty().get();
          if (!vm.deviceAuthorizedProperty().get() && challenge != null && !challenge.expired()) {
            ClipboardContent clipboard = new ClipboardContent();
            clipboard.putString(challenge.userCode());
            Clipboard.getSystemClipboard().setContent(clipboard);
          }
        });
    open.setOnAction(
        event -> {
          DeviceLogin challenge = vm.deviceLoginProperty().get();
          if (vm.deviceAuthorizedProperty().get() || challenge == null || challenge.expired())
            return;
          try {
            Desktop.getDesktop().browse(challenge.browserUri());
          } catch (Exception ex) {
            RetroDialogs.alert(owner, Alert.AlertType.ERROR, "无法打开浏览器", "请手动打开弹窗中的微软登录网址并输入代码。")
                .showAndWait();
          }
        });
    VBox content =
        new VBox(14, instruction, code, url, remaining, new HBox(10, copy, open, refresh), status);
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
    ChangeListener<DeviceLogin> listener = (observable, old, value) -> update.run();
    vm.deviceLoginProperty().addListener(listener);
    ChangeListener<Boolean> authorizationListener = (observable, old, value) -> update.run();
    vm.deviceAuthorizedProperty().addListener(authorizationListener);
    Timeline timer =
        new Timeline(new KeyFrame(javafx.util.Duration.seconds(1), event -> update.run()));
    timer.setCycleCount(Animation.INDEFINITE);
    setOnHidden(
        event -> {
          timer.stop();
          vm.deviceLoginProperty().removeListener(listener);
          vm.deviceAuthorizedProperty().removeListener(authorizationListener);
          status.textProperty().unbind();
        });
    update.run();
    timer.play();
  }
}
