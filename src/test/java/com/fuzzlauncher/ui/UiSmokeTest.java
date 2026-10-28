package com.fuzzlauncher.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.fuzzlauncher.FuzzApplication;
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
