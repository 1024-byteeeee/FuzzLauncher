package com.fuzzlauncher.ui;

import com.fuzzlauncher.domain.GameInstance;
import com.fuzzlauncher.infrastructure.game.LaunchArguments;
import java.io.IOException;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;

/** Per-instance launch argument editor; -Xms/-Xmx edits stay synchronized with the detail page. */
final class LaunchArgumentsDialog extends Dialog<ButtonType> {
  LaunchArgumentsDialog(Stage owner, LauncherViewModel vm, GameInstance instance) {
    initOwner(owner);
    initModality(Modality.WINDOW_MODAL);
    RetroDialogs.decorate(this, owner, "启动参数");
    TextArea jvm =
        textArea(
            defaultJvm(instance)
                + (instance.jvmArguments().isEmpty()
                    ? ""
                    : " " + LaunchArguments.display(instance.jvmArguments())));
    jvm.setId("launch-jvm-arguments");
    TextArea game = textArea(LaunchArguments.display(instance.gameArguments()));
    game.setId("launch-game-arguments");
    Button jvmReset = button("恢复默认", () -> jvm.setText(defaultJvm(instance)));
    jvmReset.setId("restore-default-jvm");
    Button gameReset = button("恢复默认", () -> game.setText(""));
    gameReset.setId("restore-default-game");
    VBox content = new VBox(10, section("JVM 参数", jvmReset, jvm), section("游戏附加参数", gameReset, game));
    content.setPadding(new Insets(10));
    content.setPrefWidth(640);
    getDialogPane().setContent(content);
    getDialogPane().setId("launch-arguments-dialog");
    ButtonType ok = new ButtonType("确定", ButtonBar.ButtonData.OK_DONE);
    ButtonType cancel = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
    getDialogPane().getButtonTypes().addAll(ok, cancel);
    ((Button) getDialogPane().lookupButton(ok))
        .addEventFilter(
            javafx.event.ActionEvent.ACTION,
            event -> {
              try {
                LaunchArguments.MemoryArguments parsed = LaunchArguments.parseMemory(jvm.getText());
                int minimum = parsed.minimum() != null ? parsed.minimum() : instance.minMemoryMb();
                int maximum = parsed.maximum() != null ? parsed.maximum() : instance.memoryMb();
                vm.updateInstance(
                    instance.withLaunchArguments(
                        minimum,
                        maximum,
                        parsed.jvmArguments(),
                        LaunchArguments.splitLegacy(game.getText())));
                close();
              } catch (IllegalArgumentException | IOException ex) {
                RetroDialogs.alert(owner, Alert.AlertType.ERROR, "启动参数无效", ex.getMessage())
                    .showAndWait();
              } finally {
                event.consume();
              }
            });
  }

  private static String defaultJvm(GameInstance instance) {
    return "-Xms"
        + instance.minMemoryMb()
        + "M -Xmx"
        + instance.memoryMb()
        + "M -Dfile.encoding=UTF-8";
  }

  private static TextArea textArea(String text) {
    TextArea area = new TextArea(text);
    area.setWrapText(false);
    area.setPrefRowCount(5);
    area.setFont(new javafx.scene.text.Font("Courier New", 16));
    return area;
  }

  private static Node section(String title, Button reset, TextArea area) {
    Region spacer = new Region();
    HBox.setHgrow(spacer, Priority.ALWAYS);
    HBox header = new HBox(8, label(title), spacer, reset);
    header.setAlignment(Pos.CENTER_LEFT);
    VBox box = new VBox(6, header, area);
    VBox.setVgrow(area, Priority.ALWAYS);
    return box;
  }

  private static Label label(String text) {
    Label l = new Label(text);
    l.getStyleClass().add("section-title");
    return l;
  }

  private static Button button(String text, Runnable action) {
    Button b = new Button(text);
    b.setOnAction(e -> action.run());
    b.setFocusTraversable(false);
    return b;
  }
}
