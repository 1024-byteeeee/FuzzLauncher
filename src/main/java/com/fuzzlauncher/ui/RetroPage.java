package com.fuzzlauncher.ui;

import com.fuzzlauncher.domain.*;
import java.awt.Desktop;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

/** Shared retro controls and dialogs. Contains no application logic. */
public class RetroPage extends BorderPane {
  protected final LauncherViewModel vm;
  protected final Stage stage;
  private final Consumer<Integer> navigate;

  protected RetroPage(Stage stage, LauncherViewModel vm, Consumer<Integer> navigate) {
    this.stage = stage;
    this.vm = vm;
    this.navigate = navigate;
  }

  protected void showPage(int page) {
    navigate.accept(page);
  }

  protected GridPane form(String[] labels, Node[] values) {
    GridPane grid = new GridPane();
    grid.setHgap(14);
    grid.setVgap(12);
    ColumnConstraints a = new ColumnConstraints();
    a.setPercentWidth(28);
    ColumnConstraints b = new ColumnConstraints();
    b.setPercentWidth(72);
    grid.getColumnConstraints().addAll(a, b);
    for (int i = 0; i < labels.length; i++) {
      grid.add(new Label(labels[i]), 0, i);
      grid.add(values[i], 1, i);
      if (values[i] instanceof Region r) r.setMaxWidth(Double.MAX_VALUE);
    }
    return grid;
  }

  protected Button button(String text, Runnable action) {
    Button b = new Button(text);
    b.setOnAction(e -> action.run());
    b.setFocusTraversable(false);
    return b;
  }

  protected static Label label(String text, String style) {
    Label l = new Label(text);
    l.getStyleClass().add(style);
    return l;
  }

  protected static Image image(String name) {
    return new Image(
        Objects.requireNonNull(
            LauncherView.class.getResourceAsStream("/com/fuzzlauncher/" + name + ".png")));
  }

  protected static ImageView icon(String name, int size) {
    ImageView view = new ImageView(image(name));
    view.setFitWidth(size);
    view.setFitHeight(size);
    view.setPreserveRatio(true);
    view.setSmooth(false);
    return view;
  }

  protected void message(Alert.AlertType type, String title, String text) {
    Alert alert = new Alert(type);
    alert.initOwner(stage);
    alert.setTitle(title);
    alert.setHeaderText(null);
    alert.setContentText(text);
    alert.getDialogPane().setMinWidth(550);
    alert.showAndWait();
  }

  protected boolean confirm(String title, String text) {
    Alert alert = new Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.OK, ButtonType.CANCEL);
    alert.initOwner(stage);
    alert.setTitle(title);
    alert.setHeaderText(null);
    return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
  }

  protected void openFolder(Path directory) {
    try {
      Files.createDirectories(directory);
      Desktop.getDesktop().open(directory.toFile());
    } catch (Exception ex) {
      message(Alert.AlertType.ERROR, "无法打开目录", ex.getMessage());
    }
  }

  protected static final class Spacer extends Region {
    Spacer() {
      HBox.setHgrow(this, Priority.ALWAYS);
    }
  }
}
