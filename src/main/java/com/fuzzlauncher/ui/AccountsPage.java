package com.fuzzlauncher.ui;

import com.fuzzlauncher.domain.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import javafx.beans.binding.Bindings;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

/** AccountsPage presentation; all application operations are delegated to the ViewModel. */
public final class AccountsPage extends RetroPage {
  public AccountsPage(Stage stage, LauncherViewModel vm, Consumer<Integer> navigate) {
    super(stage, vm, navigate);
    setCenter(build());
  }

  private Node build() {
    Label microsoftAccount = accountInfo(false);
    Label offlineAccount = accountInfo(true);
    Button login = button("微软登录", vm::login);
    login.disableProperty().bind(vm.busyProperty().or(vm.gameRunningProperty()));
    Button microsoftLogout = logoutButton(false);
    Button offlineLogout = logoutButton(true);
    TextField username = new TextField();
    username.setPromptText("3–16 位英文字母、数字或下划线");
    username.setMaxWidth(420);
    if (vm.accountProperty().get() != null && vm.accountProperty().get().isOffline())
      username.setText(vm.accountProperty().get().name());
    username.disableProperty().bind(vm.busyProperty().or(vm.gameRunningProperty()));
    Button offline = button("离线登录", () -> vm.loginOffline(username.getText()));
    offline
        .disableProperty()
        .bind(vm.busyProperty().or(vm.gameRunningProperty()).or(username.textProperty().isEmpty()));
    Label offlineHint = new Label("使用本地用户名游玩单人游戏或允许离线账号的服务器。");
    offlineHint.setWrapText(true);
    VBox content =
        new VBox(
            25,
            label("微软账号", "section-title"),
            icon("player", 64),
            microsoftAccount,
            new HBox(15, login, microsoftLogout),
            label("离线账号", "section-title"),
            offlineAccount,
            username,
            offlineHint,
            new HBox(15, offline, offlineLogout));
    content.setPadding(new Insets(30));
    return content;
  }

  private Label accountInfo(boolean offline) {
    Label info = new Label();
    info.setId(offline ? "offline-account-info" : "microsoft-account-info");
    info.textProperty()
        .bind(
            Bindings.createStringBinding(
                () -> {
                  Account identity = vm.accountProperty().get();
                  if (identity == null || identity.isOffline() != offline) return "未登录";
                  return (offline ? "离线账号: " : "微软账号: ")
                      + identity.name()
                      + "\nUUID: "
                      + identity.uuid();
                },
                vm.accountProperty()));
    return info;
  }

  private Button logoutButton(boolean offline) {
    Button logout = button("退出登录", vm::logout);
    logout.setId(offline ? "offline-logout" : "microsoft-logout");
    logout
        .disableProperty()
        .bind(
            vm.busyProperty()
                .or(vm.gameRunningProperty())
                .or(
                    Bindings.createBooleanBinding(
                        () -> {
                          Account identity = vm.accountProperty().get();
                          return identity == null || identity.isOffline() != offline;
                        },
                        vm.accountProperty())));
    return logout;
  }
}
