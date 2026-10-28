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
    Label account = new Label();
    account
        .textProperty()
        .bind(
            Bindings.createStringBinding(
                () ->
                    vm.accountProperty().get() == null
                        ? "未登录"
                        : vm.accountProperty().get().name()
                            + "\nUUID: "
                            + vm.accountProperty().get().uuid(),
                vm.accountProperty()));
    Label instructions =
        new Label(
            "使用拥有 Minecraft Java 版权益的微软账号登录。\n将在系统浏览器打开微软授权页面，启动器不接收密码。\n每次启动前验证账号权限，不提供离线登录。");
    instructions.setWrapText(true);
    Button login = button("微软正版登录", vm::login);
    login.disableProperty().bind(vm.busyProperty().or(vm.gameRunningProperty()));
    Button logout = button("退出登录", vm::logout);
    logout
        .disableProperty()
        .bind(vm.busyProperty().or(vm.gameRunningProperty()).or(vm.accountProperty().isNull()));
    Button configure = button("配置 Microsoft Client ID", () -> showPage(3));
    VBox content =
        new VBox(
            25,
            label("微软正版账号", "section-title"),
            icon("player", 64),
            account,
            instructions,
            new HBox(15, login, logout),
            configure);
    content.setPadding(new Insets(30));
    return content;
  }
}
