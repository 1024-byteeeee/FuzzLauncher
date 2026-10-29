package com.fuzzlauncher.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.fuzzlauncher.FuzzApplication;
import com.fuzzlauncher.application.*;
import com.fuzzlauncher.domain.*;
import com.fuzzlauncher.infrastructure.*;
import java.nio.file.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.control.skin.ComboBoxListViewSkin;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfSystemProperty(named = "fuzz.uiTests", matches = "true")
class UiSmokeTest {
  @TempDir Path temp;

  @Test
  void navigationSelectionAndAllPageLayouts() throws Exception {
    JsonInstanceRepository repository = new JsonInstanceRepository(temp);
    repository.save(
        new GameInstance(
            "first", "原版实例", "1.20.4", LoaderType.VANILLA, null, IsolationOverride.INHERIT, 4096));
    repository.save(
        new GameInstance(
            "second",
            "Fabric 实例",
            "1.21.1",
            LoaderType.FABRIC,
            "0.16.0",
            IsolationOverride.ISOLATED,
            4096));
    System.setProperty("fuzz.dataDir", temp.toString());
    new SettingsStore(temp)
        .save(new LauncherSettings(temp.resolve("game").toString(), IsolationMode.ALL));
    CountDownLatch started = new CountDownLatch(1);
    Platform.startup(started::countDown);
    assertTrue(started.await(10, TimeUnit.SECONDS));
    CompletableFuture<Void> result = new CompletableFuture<>();
    Platform.runLater(
        () -> {
          FuzzApplication app = new FuzzApplication();
          Stage stage = new Stage();
          try {
            app.start(stage);
            LauncherView view = (LauncherView) stage.getScene().getRoot();
            view.applyCss();
            view.layout();
            ListView<?> list = (ListView<?>) view.lookup(".list-view");
            list.getSelectionModel().select(1);
            view.applyCss();
            view.layout();
            assertTrue(
                view.lookupAll(".text-field").stream()
                    .filter(TextField.class::isInstance)
                    .map(TextField.class::cast)
                    .anyMatch(field -> field.getText().equals("1.21.1")));
            Files.createDirectories(Path.of("build/ui-check"));
            for (int page = 0; page < 4; page++) {
              view.showPage(page);
              view.applyCss();
              view.layout();
              for (Node field : view.lookupAll(".text-field")) {
                var bounds = field.localToScene(field.getBoundsInLocal());
                assertTrue(
                    bounds.getMaxX() <= stage.getScene().getWidth() + 2,
                    "Field overflow on page " + page);
              }
              javax.imageio.ImageIO.write(
                  javafx.embed.swing.SwingFXUtils.fromFXImage(
                      stage.getScene().snapshot(null), null),
                  "png",
                  Path.of("build/ui-check/page-" + page + ".png").toFile());
            }
            verifyDropdownHighlight(view);
            verifyDisabledMenuHighlight(stage);
            verifyLauncherVisibility(stage, view, app);
            verifyInstanceEditingLock(view, app, repository);
            verifyAccountExclusion(stage);
            verifyAvatarLifecycle(stage);
            verifyDeviceLoginDialog(stage);
            verifyLoaderReinstallation(stage);
            result.complete(null);
          } catch (Throwable ex) {
            result.completeExceptionally(ex);
          } finally {
            app.stop();
            stage.close();
            Platform.exit();
          }
        });
    try {
      result.get(30, TimeUnit.SECONDS);
    } finally {
      System.clearProperty("fuzz.dataDir");
    }
  }

  private void verifyAvatarLifecycle(Stage stage) throws Exception {
    var original = stage.getScene().getRoot();
    Account microsoft =
        new Account("skin-fixture", "0123456789abcdef0123456789abcdef", "SkinFixture");
    var identity = new java.util.concurrent.atomic.AtomicReference<>(microsoft);
    AccountAuthenticator auth =
        new AccountAuthenticator() {
          public java.util.Optional<Account> savedAccount() {
            return java.util.Optional.ofNullable(identity.get());
          }

          public GameSession login(TaskContext task) {
            identity.set(microsoft);
            return new GameSession(microsoft, "fixture", java.time.Instant.MAX);
          }

          public GameSession loginOffline(String name, TaskContext task) {
            identity.set(Account.offline(name));
            return GameSession.offline(identity.get());
          }

          public GameSession refresh(Account account, TaskContext task) {
            throw new AssertionError("No refresh expected");
          }

          public void logout(TaskContext task) {
            identity.set(null);
          }
        };
    var image = new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_ARGB);
    image.setRGB(0, 0, java.awt.Color.BLUE.getRGB());
    var bytes = new java.io.ByteArrayOutputStream();
    javax.imageio.ImageIO.write(image, "png", bytes);
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    CountDownLatch loading = new CountDownLatch(1),
        release = new CountDownLatch(1),
        returned = new CountDownLatch(1);
    Path data = temp.resolve("avatar-state");
    new SettingsStore(data)
        .save(new LauncherSettings(data.resolve("game").toString(), IsolationMode.ALL));
    try (var vm =
        new LauncherViewModel(
            new SettingsStore(data),
            new JsonInstanceRepository(data),
            null,
            auth,
            null,
            null,
            (account, task) -> {
              if (calls.incrementAndGet() == 2) {
                loading.countDown();
                // Simulate a transport completing a stale response despite cancellation.
                boolean waiting = true;
                while (waiting)
                  try {
                    release.await();
                    waiting = false;
                  } catch (InterruptedException ex) {
                    Thread.interrupted();
                  }
                returned.countDown();
              }
              return bytes.toByteArray();
            })) {
      AccountsPage page = new AccountsPage(stage, vm, ignored -> {});
      stage.getScene().setRoot(page);
      page.applyCss();
      page.layout();
      var avatar = (javafx.scene.image.ImageView) page.lookup("#microsoft-avatar");
      var fallback = avatar.getImage();
      Object loop = new Object();
      javafx.animation.Timeline poll =
          new javafx.animation.Timeline(
              new javafx.animation.KeyFrame(
                  javafx.util.Duration.millis(20),
                  event -> {
                    if (vm.accountAvatarProperty().get() != null)
                      Platform.exitNestedEventLoop(loop, true);
                  }));
      poll.setCycleCount(200);
      poll.setOnFinished(event -> Platform.exitNestedEventLoop(loop, false));
      poll.play();
      try {
        assertEquals(true, Platform.enterNestedEventLoop(loop));
      } finally {
        poll.stop();
      }
      assertSame(vm.accountAvatarProperty().get(), avatar.getImage());
      assertEquals(Color.BLUE, avatar.getImage().getPixelReader().getColor(0, 0));
      awaitAccountOperation(vm, vm::logout);
      assertNull(vm.accountAvatarProperty().get());
      assertSame(fallback, avatar.getImage());
      awaitAccountOperation(vm, vm::login);
      assertTrue(loading.await(5, TimeUnit.SECONDS));
      awaitAccountOperation(vm, vm::logout);
      awaitAccountOperation(vm, () -> vm.loginOffline("OfflineFixture"));
      release.countDown();
      assertTrue(returned.await(5, TimeUnit.SECONDS));
      Object drain = new Object();
      var pause = new javafx.animation.PauseTransition(javafx.util.Duration.millis(200));
      pause.setOnFinished(event -> Platform.exitNestedEventLoop(drain, true));
      pause.play();
      Platform.enterNestedEventLoop(drain);
      assertNull(
          vm.accountAvatarProperty().get(),
          "Late Microsoft response must not replace offline avatar");
      assertSame(fallback, avatar.getImage());
      assertEquals(2, calls.get());
    } finally {
      release.countDown();
      stage.getScene().setRoot(original);
    }
  }

  private void verifyDeviceLoginDialog(Stage stage) throws Exception {
    var original = stage.getScene().getRoot();
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var release = new CountDownLatch(1);
    var releaseAuthorized = new CountDownLatch(1);
    Account microsoft =
        new Account("device-fixture", "0123456789abcdef0123456789abcdef", "DeviceFixture");
    AccountAuthenticator auth =
        new AccountAuthenticator() {
          public java.util.Optional<Account> savedAccount() {
            return java.util.Optional.empty();
          }

          public GameSession login(TaskContext task) throws Exception {
            int attempt = calls.incrementAndGet();
            task.deviceLogin(
                new DeviceLogin(
                    attempt >= 4 ? "NEW-CODE" : "ABCD-EFGH",
                    java.net.URI.create("https://microsoft.com/devicelogin"),
                    java.time.Instant.now().plusSeconds(attempt == 3 ? -1 : 120)));
            if (attempt == 1) {
              // A late authenticator response must not resurrect the cancelled challenge or
              // account.
              while (release.getCount() != 0) {
                try {
                  release.await();
                } catch (InterruptedException ignored) {
                  Thread.interrupted();
                }
              }
              try {
                task.deviceLogin(
                    new DeviceLogin(
                        "STALE-CODE",
                        java.net.URI.create("https://microsoft.com/devicelogin"),
                        java.time.Instant.now().plusSeconds(120)));
              } catch (CancellationException expected) {
                /* The stale callback is rejected at its source. */
              }
            } else if (attempt == 2) {
              task.deviceLoginCompleted();
              releaseAuthorized.await();
            } else if (attempt >= 3) {
              while (true) {
                task.checkCancelled();
                Thread.sleep(20);
              }
            }
            return new GameSession(microsoft, "fixture", java.time.Instant.MAX);
          }

          public GameSession loginOffline(String name, TaskContext task) {
            return GameSession.offline(Account.offline(name));
          }

          public GameSession refresh(Account account, TaskContext task) {
            throw new AssertionError();
          }

          public void logout(TaskContext task) {}
        };
    Path data = temp.resolve("device-login-state");
    new SettingsStore(data)
        .save(new LauncherSettings(data.resolve("game").toString(), IsolationMode.ALL));
    try (var vm =
        new LauncherViewModel(
            new SettingsStore(data), new JsonInstanceRepository(data), null, auth, null, null)) {
      LauncherView view = new LauncherView(stage, vm);
      stage.getScene().setRoot(view);
      vm.login();
      assertTrue(vm.loggingInProperty().get());
      DialogPane pane = devicePane();
      pane.applyCss();
      pane.layout();
      Label linkRow = (Label) pane.lookup("#device-login-url");
      assertEquals("正在联系微软…", linkRow.getText());
      double pendingLinkY = linkRow.localToScene(linkRow.getBoundsInLocal()).getMinY();
      Node copyButton = pane.lookup("#copy-device-code");
      double pendingButtonsY = copyButton.localToScene(copyButton.getBoundsInLocal()).getMinY();
      assertTrue(((Button) pane.lookup("#copy-device-code")).isDisabled());
      waitForFx(() -> vm.deviceLoginProperty().get() != null);
      pane.applyCss();
      pane.layout();
      assertEquals("https://microsoft.com/devicelogin", linkRow.getText());
      assertEquals(pendingLinkY, linkRow.localToScene(linkRow.getBoundsInLocal()).getMinY(), 0.01);
      assertEquals(
          pendingButtonsY, copyButton.localToScene(copyButton.getBoundsInLocal()).getMinY(), 0.01);
      assertEquals("ABCD-EFGH", ((TextField) pane.lookup("#device-login-code")).getText());
      assertFalse(((Button) pane.lookup("#copy-device-code")).isDisabled());
      assertFalse(((Button) pane.lookup("#open-device-login")).isDisabled());
      assertFalse(pane.lookup("#device-login-expiration").isVisible());
      assertFalse(pane.lookup("#device-login-expiration").isManaged());
      var clipboard = javafx.scene.input.Clipboard.getSystemClipboard();
      var previous = new java.util.HashMap<javafx.scene.input.DataFormat, Object>();
      for (var type : clipboard.getContentTypes()) previous.put(type, clipboard.getContent(type));
      try {
        ((Button) pane.lookup("#copy-device-code")).fire();
        assertEquals("ABCD-EFGH", clipboard.getString());
      } finally {
        if ("ABCD-EFGH".equals(clipboard.getString())) clipboard.setContent(previous);
      }
      javax.imageio.ImageIO.write(
          javafx.embed.swing.SwingFXUtils.fromFXImage(pane.getScene().snapshot(null), null),
          "png",
          Path.of("build/ui-check/device-login.png").toFile());
      Window loginWindow = pane.getScene().getWindow();
      ((Button) pane.lookupButton(pane.getButtonTypes().getFirst())).fire();
      waitForFx(() -> !vm.busyProperty().get());
      assertFalse(vm.loggingInProperty().get());
      assertNull(vm.deviceLoginProperty().get());
      assertFalse(loginWindow.isShowing());
      release.countDown();
      awaitAccountOperation(vm, () -> vm.loginOffline("OfflineFixture"));
      assertTrue(vm.accountProperty().get().isOffline());
      assertNull(vm.deviceLoginProperty().get());
      awaitAccountOperation(vm, vm::logout);
      vm.login();
      waitForFx(() -> vm.deviceAuthorizedProperty().get());
      DialogPane authorized = devicePane();
      assertNull(vm.deviceLoginProperty().get());
      assertFalse(authorized.lookup("#device-login-code").isVisible());
      assertTrue(((Button) authorized.lookup("#copy-device-code")).isDisabled());
      assertTrue(((Button) authorized.lookup("#open-device-login")).isDisabled());
      assertTrue(((Button) authorized.lookup("#refresh-device-code")).isDisabled());
      assertTrue(
          ((Label) authorized.lookup("#device-login-expiration")).getText().contains("授权已完成"));
      releaseAuthorized.countDown();
      waitForFx(() -> !vm.busyProperty().get());
      assertEquals(microsoft, vm.accountProperty().get());
      assertFalse(vm.loggingInProperty().get());
      awaitAccountOperation(vm, vm::logout);
      vm.login();
      waitForFx(() -> vm.deviceLoginProperty().get() != null);
      DialogPane expired = devicePane();
      assertTrue(((Button) expired.lookup("#copy-device-code")).isDisabled());
      assertTrue(((Button) expired.lookup("#open-device-login")).isDisabled());
      assertTrue(((Label) expired.lookup("#device-login-expiration")).getText().contains("过期"));
      Window expiredWindow = expired.getScene().getWindow();
      ((Button) expired.lookup("#refresh-device-code")).fire();
      waitForFx(() -> calls.get() == 4 && vm.deviceLoginProperty().get() != null);
      assertFalse(expiredWindow.isShowing());
      DialogPane refreshed = devicePane();
      assertEquals("NEW-CODE", ((TextField) refreshed.lookup("#device-login-code")).getText());
      assertFalse(((Button) refreshed.lookup("#open-device-login")).isDisabled());
      ((Button) refreshed.lookup(".window-control")).fire();
      waitForFx(() -> !vm.loggingInProperty().get());
      assertNull(vm.accountProperty().get());
    } finally {
      release.countDown();
      releaseAuthorized.countDown();
      stage.getScene().setRoot(original);
    }
  }

  private static DialogPane devicePane() {
    return (DialogPane)
        Window.getWindows().stream()
            .filter(
                window ->
                    window.getScene() != null
                        && window.getScene().lookup("#device-login-dialog") != null)
            .findFirst()
            .orElseThrow()
            .getScene()
            .getRoot();
  }

  private void verifyLoaderReinstallation(Stage stage) throws Exception {
    var originalRoot = stage.getScene().getRoot();
    Path data = temp.resolve("loader-ui"), game = data.resolve("game");
    new SettingsStore(data).save(new LauncherSettings(game.toString(), IsolationMode.ALL));
    GameInstance vanilla = rootFixture(game, "plain", "Plain");
    var instanceJson =
        JsonFiles.GSON.toJsonTree(rootFixture(game, "fabric", "Fabric")).getAsJsonObject();
    instanceJson.addProperty("loader", "FABRIC");
    instanceJson.addProperty("loaderVersion", "1");
    GameInstance fabric = JsonFiles.GSON.fromJson(instanceJson, GameInstance.class);
    var profile = JsonFiles.read(InstanceDirectories.profile(game, fabric));
    profile.add("fuzzInstance", instanceJson);
    JsonFiles.write(InstanceDirectories.profile(game, fabric), profile);
    JsonInstanceRepository backing = new JsonInstanceRepository(data);
    backing.save(vanilla);
    backing.save(fabric);
    var commitReached = new CountDownLatch(1);
    var releaseCommit = new CountDownLatch(1);
    var releaseQuery = new java.util.concurrent.atomic.AtomicReference<>(new CountDownLatch(1));
    var holdQuery = new java.util.concurrent.atomic.AtomicBoolean();
    InstanceRepository repository =
        new InstanceRepository() {
          public java.util.List<GameInstance> findAll() throws java.io.IOException {
            return backing.findAll();
          }

          public void remove(String id) throws java.io.IOException {
            backing.remove(id);
          }

          public void save(GameInstance value) throws java.io.IOException {
            if ("2".equals(value.loaderVersion())) {
              commitReached.countDown();
              try {
                if (!releaseCommit.await(5, TimeUnit.SECONDS))
                  throw new java.io.IOException("Commit test timeout");
              } catch (InterruptedException ex) {
                throw new java.io.IOException("Commit was interrupted", ex);
              }
            }
            backing.save(value);
          }
        };
    var server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          if (holdQuery.get()) {
            try {
              releaseQuery.get().await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
              Thread.currentThread().interrupt();
            }
          }
          byte[] bytes =
              "[{\"loader\":{\"version\":\"2\"}},{\"loader\":{\"version\":\"1\"}}]"
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8);
          try {
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
          } finally {
            exchange.close();
          }
        });
    server.start();
    var constructor =
        HttpTransport.class.getDeclaredConstructor(java.util.function.UnaryOperator.class);
    constructor.setAccessible(true);
    HttpTransport http =
        constructor.newInstance(
            (java.util.function.UnaryOperator<String>)
                url -> "http://127.0.0.1:" + server.getAddress().getPort() + "/loaders");
    LoaderInstaller installer =
        (root, workspace, version, selected, task) -> {
          Path artifact = root.resolve("libraries/fixture/" + selected + ".jar");
          Files.createDirectories(artifact.getParent());
          Files.writeString(artifact, "fresh " + selected);
          return com.google.gson.JsonParser.parseString(
                  "{\"mainClass\":\"Fixture\",\"libraries\":[{\"name\":\"fixture:loader:"
                      + selected
                      + "\",\"downloads\":{\"artifact\":{\"path\":\"fixture/"
                      + selected
                      + ".jar\",\"url\":\"\"}}}]}")
              .getAsJsonObject();
        };
    var files = new com.fuzzlauncher.infrastructure.game.GameFiles(http);
    var resolver = new com.fuzzlauncher.infrastructure.game.VersionResolver();
    var install =
        new InstallGame(
            repository, files, resolver, java.util.Map.of(LoaderType.FABRIC, installer));
    AccountAuthenticator auth =
        new AccountAuthenticator() {
          public java.util.Optional<Account> savedAccount() {
            return java.util.Optional.empty();
          }

          public GameSession login(TaskContext task) {
            throw new AssertionError();
          }

          public GameSession loginOffline(String name, TaskContext task) {
            throw new AssertionError();
          }

          public GameSession refresh(Account account, TaskContext task) {
            throw new AssertionError();
          }

          public void logout(TaskContext task) {}
        };
    try (var vm =
        new LauncherViewModel(
            new SettingsStore(data),
            repository,
            new com.fuzzlauncher.infrastructure.game.VersionCatalog(http),
            auth,
            install,
            null)) {
      var view = new LauncherView(stage, vm);
      stage.getScene().setRoot(view);
      view.applyCss();
      view.layout();
      assertTrue(view.lookup("#change-instance-loader").isDisabled());
      vm.selectInstance(fabric);
      view.applyCss();
      view.layout();
      assertFalse(view.lookup("#change-instance-loader").isDisabled());
      // The query result listener must survive GC while a network response is pending.
      assertSame(vm.loaderVersions(), vm.loaderVersions());
      holdQuery.set(true);
      ((Button) view.lookup("#change-instance-loader")).fire();
      waitForFx(() -> vm.busyProperty().get());
      System.gc();
      releaseQuery.get().countDown();
      holdQuery.set(false);
      waitForFx(() -> !vm.busyProperty().get() && vm.loaderVersions().size() == 2);
      DialogPane chooser = loaderPane();
      @SuppressWarnings("unchecked")
      var versions = (ComboBox<String>) chooser.lookup("#replacement-loader-version");
      assertEquals("1", versions.getValue());
      assertEquals("请选择加载器版本", versions.getPromptText());
      versions.setValue("2");
      javax.imageio.ImageIO.write(
          javafx.embed.swing.SwingFXUtils.fromFXImage(chooser.getScene().snapshot(null), null),
          "png",
          Path.of("build/ui-check/loader-version.png").toFile());
      Platform.runLater(
          () -> {
            DialogPane confirm =
                (DialogPane)
                    Window.getWindows().stream()
                        .filter(
                            window ->
                                window.getScene() != null
                                    && window.getScene().getRoot() instanceof DialogPane pane
                                    && pane.getButtonTypes().contains(ButtonType.OK))
                        .findFirst()
                        .orElseThrow()
                        .getScene()
                        .getRoot();
            ((Button) confirm.lookupButton(ButtonType.OK)).fire();
          });
      ((Button) chooser.lookupButton(chooser.getButtonTypes().getFirst())).fire();
      waitForFx(() -> commitReached.getCount() == 0);
      assertTrue(vm.isCommitting());
      vm.cancel(); // Commit cannot be cancelled after publication has begun, and must not block FX.
      assertTrue(vm.busyProperty().get());
      releaseCommit.countDown();
      waitForFx(() -> !vm.busyProperty().get());
      assertEquals("2", vm.selectedProperty().get().loaderVersion());
      assertFalse(vm.isCommitting());
      assertEquals("fresh 2", Files.readString(game.resolve("libraries/fixture/2.jar")));
      assertEquals(
          "world fabric",
          Files.readString(
              InstanceDirectories.directory(game, fabric).resolve("saves/world/level.dat")));
      assertEquals("Fabric 2", ((TextField) view.lookup("#instance-loader")).getText());
      assertFalse(vm.installingProperty().get());
      releaseQuery.set(new CountDownLatch(1));
      holdQuery.set(true);
      ((Button) view.lookup("#change-instance-loader")).fire();
      waitForFx(() -> vm.busyProperty().get());
      ((Button) loaderPane().lookup(".window-control")).fire();
      waitForFx(() -> !vm.busyProperty().get());
      releaseQuery.get().countDown();
      assertEquals("2", vm.selectedProperty().get().loaderVersion());
    } finally {
      releaseCommit.countDown();
      releaseQuery.get().countDown();
      server.stop(0);
      stage.getScene().setRoot(originalRoot);
    }
  }

  private static DialogPane loaderPane() {
    return (DialogPane)
        Window.getWindows().stream()
            .filter(
                window ->
                    window.getScene() != null
                        && window.getScene().lookup("#loader-version-dialog") != null)
            .findFirst()
            .orElseThrow()
            .getScene()
            .getRoot();
  }

  private static void waitForFx(java.util.function.BooleanSupplier done) {
    if (done.getAsBoolean()) return;
    Object loop = new Object();
    var timer =
        new javafx.animation.Timeline(
            new javafx.animation.KeyFrame(
                javafx.util.Duration.millis(20),
                event -> {
                  if (done.getAsBoolean()) Platform.exitNestedEventLoop(loop, true);
                }));
    timer.setCycleCount(250);
    timer.setOnFinished(event -> Platform.exitNestedEventLoop(loop, false));
    timer.play();
    try {
      assertEquals(
          true, Platform.enterNestedEventLoop(loop), "Timed out waiting for device login UI");
    } finally {
      timer.stop();
    }
  }

  private static void verifyDisabledMenuHighlight(Stage stage) {
    MenuItem disabled = new MenuItem("禁用项目");
    disabled.setDisable(true);
    MenuItem enabled = new MenuItem("可用项目");
    ContextMenu menu = new ContextMenu(disabled, enabled);
    menu.show(stage, stage.getX() + 50, stage.getY() + 50);
    try {
      Node root = menu.getScene().getRoot();
      root.applyCss();
      Node blocked = disabled.getStyleableNode();
      var idleBackground = ((javafx.scene.layout.Region) blocked).getBackground();
      var idleText = ((Label) blocked.lookup(".label")).getTextFill();
      blocked.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("focused"), true);
      blocked.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("hover"), true);
      root.applyCss();
      var background = ((javafx.scene.layout.Region) blocked).getBackground();
      assertEquals(idleBackground, background, "Disabled menu background must not change on hover");
      assertEquals(Color.web("#c0c0c0"), background.getFills().getFirst().getFill());
      assertEquals(1.0, blocked.getOpacity());
      assertEquals(idleText, ((Label) blocked.lookup(".label")).getTextFill());
      assertEquals(Color.GRAY, idleText);
      enabled
          .getStyleableNode()
          .pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("focused"), true);
      root.applyCss();
      assertEquals(
          Color.NAVY,
          ((javafx.scene.layout.Region) enabled.getStyleableNode())
              .getBackground()
              .getFills()
              .getFirst()
              .getFill());
    } finally {
      menu.hide();
    }
  }

  private static void verifyLauncherVisibility(Stage stage, LauncherView view, FuzzApplication app)
      throws Exception {
    view.showPage(3);
    view.applyCss();
    @SuppressWarnings("unchecked")
    var choice = (ComboBox<LauncherVisibility>) view.lookup("#launcher-visibility");
    assertEquals(java.util.List.of(LauncherVisibility.values()), choice.getItems());
    assertEquals(LauncherVisibility.KEEP, choice.getValue());
    var vmField = FuzzApplication.class.getDeclaredField("viewModel");
    vmField.setAccessible(true);
    var vm = (LauncherViewModel) vmField.get(app);
    LauncherSettings original = vm.settingsProperty().get();
    choice.setValue(LauncherVisibility.HIDE_AND_REOPEN);
    Button save =
        view.lookupAll(".button").stream()
            .filter(Button.class::isInstance)
            .map(Button.class::cast)
            .filter(button -> "保存设置".equals(button.getText()))
            .findFirst()
            .orElseThrow();
    awaitAccountOperation(vm, save::fire);
    assertEquals(
        LauncherVisibility.HIDE_AND_REOPEN,
        new SettingsStore(vm.dataDirectory()).load().launcherVisibility());
    view.showPage(0);
    view.showPage(3);
    assertEquals(
        LauncherVisibility.HIDE_AND_REOPEN,
        ((ComboBox<?>) view.lookup("#launcher-visibility")).getValue());
    awaitAccountOperation(vm, () -> vm.saveSettings(original));
    var quits = new java.util.concurrent.atomic.AtomicInteger();
    var controller = new LauncherVisibilityController(stage, quits::incrementAndGet);
    try {
      controller.started(LauncherVisibility.KEEP);
      assertTrue(stage.isShowing());
      controller.exited(0);
      assertEquals(0, quits.get());
      controller.started(LauncherVisibility.HIDE_AND_REOPEN);
      assertFalse(stage.isShowing());
      assertFalse(Platform.isImplicitExit());
      controller.exited(0);
      assertTrue(stage.isShowing());
      assertFalse(stage.isIconified());
      assertTrue(Platform.isImplicitExit());
      controller.started(LauncherVisibility.HIDE);
      assertFalse(stage.isShowing());
      controller.exited(1);
      assertTrue(stage.isShowing(), "Crash must restore the window before displaying its error");
      controller.started(LauncherVisibility.HIDE);
      controller.exited(0);
      assertEquals(1, quits.get());
      stage.show();
      controller.started(LauncherVisibility.EXIT);
      assertEquals(2, quits.get());
    } finally {
      stage.setIconified(false);
      stage.show();
      Platform.setImplicitExit(true);
    }
  }

  private static void verifyRootBrowser(Stage stage, LauncherViewModel vm, Path data)
      throws Exception {
    var original = stage.getScene().getRoot();
    Path chosen = Files.createDirectories(data.resolve("picked-root")).toRealPath();
    SettingsPage settings = new SettingsPage(stage, vm, ignored -> {});
    stage.getScene().setRoot(settings);
    settings.applyCss();
    settings.layout();
    Button browse = (Button) settings.lookup("#browse-game-root");
    assertFalse(browse.isDisabled());
    Platform.runLater(
        () -> {
          Window window =
              Window.getWindows().stream()
                  .filter(w -> w.getScene() != null && w.getScene().lookup("#path-dialog") != null)
                  .findFirst()
                  .orElseThrow();
          DialogPane pane = (DialogPane) window.getScene().getRoot();
          ((TextField) pane.lookup("#path-location")).setText(chosen.toString());
          ((Button) pane.lookupButton(ButtonType.OK)).fire();
        });
    browse.fire();
    assertEquals(chosen.toString(), ((TextField) settings.lookup("#game-root-path")).getText());
    stage.getScene().setRoot(original);
  }

  private static GameInstance rootFixture(Path root, String id, String name) throws Exception {
    GameInstance item =
        new GameInstance(
            id, name, "base", LoaderType.VANILLA, null, IsolationOverride.INHERIT, 4096);
    JsonFiles.write(
        root.resolve("versions/base/base.json"),
        com.google.gson.JsonParser.parseString(
            "{\"id\":\"base\",\"mainClass\":\"Main\",\"libraries\":[]}"));
    Files.writeString(root.resolve("versions/base/base.jar"), "fixture client");
    var marker = new com.google.gson.JsonObject();
    marker.addProperty("id", id);
    marker.addProperty("inheritsFrom", "base");
    marker.add("fuzzInstance", JsonFiles.GSON.toJsonTree(item));
    JsonFiles.write(InstanceDirectories.profile(root, item), marker);
    Path world = InstanceDirectories.directory(root, item).resolve("saves/world/level.dat");
    Files.createDirectories(world.getParent());
    Files.writeString(world, "world " + id);
    return item;
  }

  private static void verifyGameRootSwitch(Stage stage, LauncherViewModel vm, Path data)
      throws Exception {
    var restarts = new java.util.concurrent.atomic.AtomicInteger();
    vm.onRestartRequired(restarts::incrementAndGet);
    var original = vm.settingsProperty().get();
    Path oldRoot = Path.of(original.gameRoot()), newRoot = data.resolve("other-game-root");
    GameInstance old = rootFixture(oldRoot, "old-root", "Old world"),
        target = rootFixture(newRoot, "new-root", "New world");
    awaitAccountOperation(vm, vm::refreshInstances);
    assertEquals(java.util.List.of(old), vm.instances());
    verifyRootBrowser(
        stage, vm, data); // The browse button must also work when instances already exist.
    var changed =
        new LauncherSettings(
            newRoot.toString(),
            original.isolation(),
            original.downloadSource(),
            original.dataDirectory());
    awaitAccountOperation(vm, () -> vm.saveSettings(changed));
    assertEquals(1, restarts.get());
    assertEquals(java.util.List.of(target), vm.instances());
    assertEquals(target, vm.selectedProperty().get());
    assertEquals(changed, new SettingsStore(data).load());
    assertEquals(
        "world old-root",
        Files.readString(
            InstanceDirectories.directory(oldRoot, old).resolve("saves/world/level.dat")));
    awaitAccountOperation(vm, () -> vm.saveSettings(original));
    assertEquals(2, restarts.get());
    assertEquals(java.util.List.of(old), vm.instances());
    assertEquals(
        "world new-root",
        Files.readString(
            InstanceDirectories.directory(newRoot, target).resolve("saves/world/level.dat")));
    Path occupied = Files.createDirectories(data.resolve("occupied-data"));
    Files.writeString(occupied.resolve("keep.txt"), "keep");
    var failed =
        new LauncherSettings(
            newRoot.toString(),
            original.isolation(),
            original.downloadSource(),
            occupied.toString());
    awaitAccountOperation(vm, () -> vm.saveSettings(failed), false);
    assertEquals(2, restarts.get());
    assertEquals(original, vm.settingsProperty().get());
    assertEquals(original, new SettingsStore(data).load());
    assertEquals(java.util.List.of(old), new JsonInstanceRepository(data).findAll());
    Path emptyRoot = data.resolve("empty-game-root");
    awaitAccountOperation(
        vm,
        () ->
            vm.saveSettings(
                new LauncherSettings(
                    emptyRoot.toString(), original.isolation(), original.downloadSource())));
    assertTrue(vm.instances().isEmpty());
    assertNull(vm.selectedProperty().get());
    awaitAccountOperation(vm, () -> vm.saveSettings(original));
    assertEquals(java.util.List.of(old), vm.instances());
    Path movedRoot = data.resolve("moved-game-root");
    var migrated =
        new LauncherSettings(
            movedRoot.toString(),
            original.isolation(),
            original.downloadSource(),
            original.dataDirectory(),
            original.launcherVisibility());
    awaitAccountOperation(vm, () -> vm.saveSettings(migrated, true));
    assertEquals(5, restarts.get());
    assertEquals(java.util.List.of(old), vm.instances());
    assertFalse(Files.exists(oldRoot));
    assertEquals(
        "world old-root",
        Files.readString(
            InstanceDirectories.directory(movedRoot, old).resolve("saves/world/level.dat")));
    // A post-copy configuration failure must roll back the destination, not remove source files.
    Path rolledBack = data.resolve("rolled-back-root");
    var badMigration =
        new LauncherSettings(
            rolledBack.toString(),
            original.isolation(),
            original.downloadSource(),
            occupied.toString(),
            original.launcherVisibility());
    awaitAccountOperation(vm, () -> vm.saveSettings(badMigration, true), false);
    assertEquals(5, restarts.get());
    assertFalse(Files.exists(rolledBack));
    assertEquals(migrated, new SettingsStore(data).load());
    assertTrue(Files.isRegularFile(InstanceDirectories.profile(movedRoot, old)));
    awaitAccountOperation(vm, () -> vm.saveSettings(original, true));
    assertEquals(6, restarts.get());
    assertFalse(Files.exists(movedRoot));
    verifyMigrationQuestion(stage, vm, data, restarts);
  }

  private static void verifyMigrationQuestion(
      Stage stage,
      LauncherViewModel vm,
      Path data,
      java.util.concurrent.atomic.AtomicInteger restarts)
      throws Exception {
    Node original = stage.getScene().getRoot();
    Path target = data.resolve("ui-root");
    try {
      SettingsPage page = new SettingsPage(stage, vm, ignored -> {});
      stage.getScene().setRoot(page);
      ((TextField) page.lookup("#game-root-path")).setText(target.toString());
      Button save =
          page.lookupAll(".button").stream()
              .filter(Button.class::isInstance)
              .map(Button.class::cast)
              .filter(button -> "保存设置".equals(button.getText()))
              .findFirst()
              .orElseThrow();
      int before = restarts.get();
      for (boolean cancel : new boolean[] {true, false}) {
        Platform.runLater(
            () -> {
              var pane =
                  (DialogPane)
                      Window.getWindows().stream()
                          .filter(
                              window ->
                                  window.getScene() != null
                                      && window.getScene().lookup("#game-root-migration-question")
                                          != null)
                          .findFirst()
                          .orElseThrow()
                          .getScene()
                          .getRoot();
              assertEquals(
                  java.util.List.of("迁移并重启", "不迁移，仅重启", "取消"),
                  pane.getButtonTypes().stream().map(ButtonType::getText).toList());
              var selected =
                  pane.getButtonTypes().stream()
                      .filter(type -> type.getText().equals(cancel ? "取消" : "不迁移，仅重启"))
                      .findFirst()
                      .orElseThrow();
              ((Button) pane.lookupButton(selected)).fire();
            });
        if (cancel) {
          save.fire();
          assertFalse(vm.busyProperty().get());
          assertEquals(before, restarts.get());
          assertFalse(Files.exists(target));
        } else {
          awaitAccountOperation(vm, save::fire);
          assertEquals(before + 1, restarts.get());
          assertTrue(Files.isDirectory(target));
        }
      }
    } finally {
      stage.getScene().setRoot((javafx.scene.Parent) original);
    }
  }

  private static void verifyInstanceEditingLock(
      LauncherView view, FuzzApplication app, JsonInstanceRepository repository) throws Exception {
    var vmField = FuzzApplication.class.getDeclaredField("viewModel");
    vmField.setAccessible(true);
    LauncherViewModel vm = (LauncherViewModel) vmField.get(app);
    var runningField = LauncherViewModel.class.getDeclaredField("gameRunning");
    runningField.setAccessible(true);
    var running = (javafx.beans.property.BooleanProperty) runningField.get(vm);
    view.showPage(0);
    view.applyCss();
    view.layout();
    Node form = view.lookup("#instance-settings-form");
    var preview = (javafx.scene.image.ImageView) view.lookup("#instance-preview-image");
    assertTrue(preview.isPreserveRatio());
    var frame = (javafx.scene.layout.Region) preview.getParent();
    double originalWidth = frame.getWidth();
    double originalHeight = frame.getHeight();
    for (double[] size : new double[][] {{580, 90}, {580, 210}, {300, 250}}) {
      frame.resize(size[0], size[1]);
      var crop = preview.getViewport();
      assertEquals(
          preview.getFitWidth() / preview.getFitHeight(),
          crop.getWidth() / crop.getHeight(),
          0.000001,
          "Preview must crop instead of stretching at every aspect ratio");
      assertTrue(crop.getMinX() >= 0 && crop.getMinY() >= 0);
      assertTrue(crop.getMaxX() <= preview.getImage().getWidth() + 0.000001);
      assertTrue(crop.getMaxY() <= preview.getImage().getHeight() + 0.000001);
    }
    frame.resize(originalWidth, originalHeight);
    assertFalse(form.isDisabled());
    var before = repository.findAll();
    try {
      running.set(true);
      assertTrue(form.isDisabled());
      for (Node input : form.lookupAll(".text-field, .combo-box, .spinner, .button"))
        assertTrue(input.isDisabled(), "Running instance input remained editable: " + input);
      assertTrue(view.lookup("#save-instance-settings").isDisabled());
      // Navigate away and back: newly built controls must also inherit the lock.
      view.showPage(3);
      view.showPage(0);
      view.applyCss();
      view.layout();
      assertTrue(view.lookup("#instance-settings-form").isDisabled());
      assertEquals(before, repository.findAll());
    } finally {
      running.set(false);
    }
    assertFalse(view.lookup("#instance-settings-form").isDisabled());
    assertFalse(view.lookup("#instance-min-memory").isDisabled());
    assertFalse(view.lookup("#instance-memory").isDisabled());
    assertFalse(view.lookup("#instance-java-path").isDisabled());
    assertFalse(view.lookup("#save-instance-settings").isDisabled());
  }

  private void verifyAccountExclusion(Stage stage) throws Exception {
    var original = stage.getScene().getRoot();
    for (int state = 0; state < 3; state++) {
      Account initial =
          state == 0
              ? null
              : state == 1
                  ? new Account("microsoft-fixture", "uuid-fixture", "MicrosoftFixture")
                  : Account.offline("OfflineFixture");
      var saved = new java.util.concurrent.atomic.AtomicReference<>(initial);
      var logins = new java.util.concurrent.atomic.AtomicInteger();
      AccountAuthenticator auth =
          new AccountAuthenticator() {
            public java.util.Optional<Account> savedAccount() {
              return java.util.Optional.ofNullable(saved.get());
            }

            public GameSession login(TaskContext task) {
              logins.incrementAndGet();
              saved.set(new Account("microsoft-fixture", "uuid-fixture", "MicrosoftFixture"));
              return new GameSession(saved.get(), "fixture", java.time.Instant.MAX);
            }

            public GameSession loginOffline(String name, TaskContext task) {
              logins.incrementAndGet();
              saved.set(Account.offline(name));
              return GameSession.offline(saved.get());
            }

            public GameSession refresh(Account account, TaskContext task) {
              throw new AssertionError("No refresh expected");
            }

            public void logout(TaskContext task) {
              saved.set(null);
            }
          };
      Path data = temp.resolve("account-state-" + state);
      new SettingsStore(data)
          .save(new LauncherSettings(data.resolve("game").toString(), IsolationMode.ALL));
      try (var vm =
          new LauncherViewModel(
              new SettingsStore(data), new JsonInstanceRepository(data), null, auth, null, null)) {
        if (state == 0) {
          verifyRootBrowser(stage, vm, data);
          var field = LauncherViewModel.class.getDeclaredField("gameRunning");
          field.setAccessible(true);
          var running = (javafx.beans.property.BooleanProperty) field.get(vm);
          running.set(true);
          try {
            vm.updateInstance(
                new GameInstance(
                    "guard",
                    "Guard",
                    "1.20.4",
                    LoaderType.VANILLA,
                    null,
                    IsolationOverride.INHERIT,
                    4096));
            assertFalse(vm.busyProperty().get(), "Running instance update must not start a task");
            assertEquals("游戏运行中，请先退出游戏再修改实例设置", vm.errorProperty().get());
            assertTrue(new JsonInstanceRepository(data).findAll().isEmpty());
          } finally {
            running.set(false);
          }
          verifyGameRootSwitch(stage, vm, data);
        }
        AccountsPage page = new AccountsPage(stage, vm, ignored -> {});
        stage.getScene().setRoot(page);
        page.applyCss();
        page.layout();
        Button microsoft = (Button) page.lookup("#microsoft-login");
        Button offline = (Button) page.lookup("#offline-login");
        TextField name = (TextField) page.lookup("#offline-username");
        Button microsoftLogout = (Button) page.lookup("#microsoft-logout");
        Button offlineLogout = (Button) page.lookup("#offline-logout");
        name.setText("NewOffline");
        assertEquals(state != 0, microsoft.isDisabled());
        assertEquals(state != 0, offline.isDisabled());
        assertEquals(state == 1, name.isDisabled());
        assertTrue(microsoftLogout.isVisible() && offlineLogout.isVisible());
        assertEquals(state != 1, microsoftLogout.isDisabled());
        assertEquals(state != 2, offlineLogout.isDisabled());
        if (initial != null) {
          microsoft.fire();
          offline.fire();
          vm.login();
          vm.loginOffline("NewOffline");
          if (initial.isOffline()) {
            microsoft.fire();
            vm.login();
          } else {
            offline.fire();
            vm.loginOffline("NewOffline");
          }
          assertEquals(
              0,
              logins.get(),
              "Logged-in accounts must not reach authentication or overwrite credentials");
          assertEquals(initial, saved.get());
          assertEquals(initial, vm.accountProperty().get());
          assertFalse(vm.errorProperty().get().isBlank());
          awaitAccountOperation(vm, vm::logout);
          assertNull(vm.accountProperty().get());
          assertFalse(name.isDisabled());
          assertFalse(microsoft.isDisabled());
          assertFalse(offline.isDisabled());
          assertTrue(microsoftLogout.isDisabled() && offlineLogout.isDisabled());
          awaitAccountOperation(
              vm, initial.isOffline() ? vm::login : () -> vm.loginOffline("NewOffline"));
          assertEquals(!initial.isOffline(), vm.accountProperty().get().isOffline());
          assertEquals(1, logins.get());
          assertTrue(microsoft.isDisabled());
          assertTrue(offline.isDisabled());
        }
      } finally {
        stage.getScene().setRoot(original);
      }
    }
  }

  private static void awaitAccountOperation(LauncherViewModel vm, Runnable action) {
    awaitAccountOperation(vm, action, true);
  }

  private static void awaitAccountOperation(
      LauncherViewModel vm, Runnable action, boolean success) {
    Object loop = new Object();
    javafx.animation.PauseTransition timeout =
        new javafx.animation.PauseTransition(javafx.util.Duration.seconds(5));
    timeout.setOnFinished(event -> Platform.exitNestedEventLoop(loop, false));
    javafx.beans.value.ChangeListener<Boolean> listener =
        (observable, old, busy) -> {
          if (!busy) Platform.runLater(() -> Platform.exitNestedEventLoop(loop, true));
        };
    vm.busyProperty().addListener(listener);
    try {
      action.run();
      assertTrue(vm.busyProperty().get());
      timeout.play();
      assertEquals(true, Platform.enterNestedEventLoop(loop));
      assertEquals(success, vm.errorProperty().get().isBlank(), vm.errorProperty().get());
    } finally {
      timeout.stop();
      vm.busyProperty().removeListener(listener);
    }
  }

  private static void verifyDropdownHighlight(LauncherView view) {
    ComboBox<?> combo = (ComboBox<?>) view.lookup(".combo-box");
    Object original = combo.getValue();
    combo.show();
    ListView<?> list = (ListView<?>) ((ComboBoxListViewSkin<?>) combo.getSkin()).getPopupContent();
    list.applyCss();
    list.layout();
    ListCell<?> second = cell(list, 1);
    ListCell<?> third = cell(list, 2);
    Event.fireEvent(second, mouse(second, MouseEvent.MOUSE_MOVED));
    list.applyCss();
    assertEquals(Color.NAVY, second.getBackground().getFills().getFirst().getFill());
    Event.fireEvent(third, mouse(third, MouseEvent.MOUSE_MOVED));
    Event.fireEvent(list, mouse(list, MouseEvent.MOUSE_EXITED));
    list.applyCss();
    assertEquals(Color.WHITE, second.getBackground().getFills().getFirst().getFill());
    assertEquals(Color.NAVY, third.getBackground().getFills().getFirst().getFill());
    assertEquals(original, combo.getValue(), "Hover must not commit a selection");
    Event.fireEvent(third, mouse(third, MouseEvent.MOUSE_PRESSED));
    Event.fireEvent(third, mouse(third, MouseEvent.MOUSE_RELEASED));
    assertEquals(third.getItem(), combo.getValue(), "Click must commit the hovered item");
    combo.hide();
  }

  private static ListCell<?> cell(ListView<?> list, int index) {
    return list.lookupAll(".list-cell").stream()
        .filter(node -> node instanceof ListCell<?> cell && cell.getIndex() == index)
        .map(node -> (ListCell<?>) node)
        .findFirst()
        .orElseThrow();
  }

  private static MouseEvent mouse(Node target, javafx.event.EventType<MouseEvent> type) {
    var point = target.localToScene(5, 5);
    return new MouseEvent(
        type,
        point.getX(),
        point.getY(),
        point.getX(),
        point.getY(),
        MouseButton.PRIMARY,
        1,
        false,
        false,
        false,
        false,
        type == MouseEvent.MOUSE_PRESSED,
        false,
        false,
        false,
        false,
        true,
        new javafx.scene.input.PickResult(target, point.getX(), point.getY()));
  }
}
