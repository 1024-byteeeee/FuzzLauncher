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
            verifyInstanceEditingLock(view, app, repository);
            verifyAccountExclusion(stage);
            verifyAvatarLifecycle(stage);
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
    assertEquals(java.util.List.of(target), vm.instances());
    assertEquals(target, vm.selectedProperty().get());
    assertEquals(changed, new SettingsStore(data).load());
    assertEquals(
        "world old-root",
        Files.readString(
            InstanceDirectories.directory(oldRoot, old).resolve("saves/world/level.dat")));
    awaitAccountOperation(vm, () -> vm.saveSettings(original));
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
        assertEquals(state == 2, microsoft.isDisabled());
        assertEquals(state == 1, offline.isDisabled());
        assertEquals(state == 1, name.isDisabled());
        assertTrue(microsoftLogout.isVisible() && offlineLogout.isVisible());
        assertEquals(state != 1, microsoftLogout.isDisabled());
        assertEquals(state != 2, offlineLogout.isDisabled());
        if (initial != null) {
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
              "Opposite login must not reach authentication or overwrite credentials");
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
          assertEquals(!initial.isOffline(), microsoft.isDisabled());
          assertEquals(initial.isOffline(), offline.isDisabled());
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
