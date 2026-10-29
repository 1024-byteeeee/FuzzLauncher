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
    ImageView avatar = icon("player", 64);
    avatar.setId("microsoft-avatar");
    Image defaultAvatar = avatar.getImage();
    avatar
        .imageProperty()
        .bind(
            Bindings.createObjectBinding(
                () ->
                    vm.accountAvatarProperty().get() == null
                        ? defaultAvatar
                        : vm.accountAvatarProperty().get(),
                vm.accountAvatarProperty()));
    Button login = button("微软登录", vm::login);
    login.setId("microsoft-login");
    login.disableProperty().bind(vm.busyProperty().or(vm.gameRunningProperty()));
    Button microsoftLogout = logoutButton(false);
    Button offlineLogout = logoutButton(true);
    TextField username = new TextField();
    username.setId("offline-username");
    username.setPromptText("3–16 位英文字母、数字或下划线");
    username.setMaxWidth(420);
    if (vm.accountProperty().get() != null && vm.accountProperty().get().isOffline())
      username.setText(vm.accountProperty().get().name());
    username.disableProperty().bind(vm.busyProperty().or(vm.gameRunningProperty()));
    Button offline = button("离线登录", () -> vm.loginOffline(username.getText()));
    offline.setId("offline-login");
    offline
        .disableProperty()
        .bind(vm.busyProperty().or(vm.gameRunningProperty()).or(username.textProperty().isEmpty()));
    Label offlineHint = new Label("使用本地用户名游玩单人游戏或允许离线账号的服务器。");
    offlineHint.setWrapText(true);
    VBox microsoftSection =
        new VBox(
            25,
            label("微软账号", "section-title"),
            avatar,
            microsoftAccount,
            new HBox(15, login, microsoftLogout));
    microsoftSection
        .disableProperty()
        .bind(
            Bindings.createBooleanBinding(
                () -> vm.accountProperty().get() != null && vm.accountProperty().get().isOffline(),
                vm.accountProperty()));
    VBox offlineSection =
        new VBox(
            25,
            label("离线账号", "section-title"),
            offlineAccount,
            username,
            offlineHint,
            new HBox(15, offline, offlineLogout));
    offlineSection
        .disableProperty()
        .bind(
            Bindings.createBooleanBinding(
                () -> vm.accountProperty().get() != null && !vm.accountProperty().get().isOffline(),
                vm.accountProperty()));
    VBox content = new VBox(25, microsoftSection, offlineSection);
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
