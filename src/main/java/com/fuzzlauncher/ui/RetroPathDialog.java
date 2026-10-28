package com.fuzzlauncher.ui;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.stage.Window;

/** Read-only file browser using the launcher's shared dialog chrome. */
final class RetroPathDialog extends Dialog<Path> {
  private final boolean directoryOnly;
  private final TextField location = new TextField();
  private final ComboBox<Path> drives = new RetroComboBox<>();
  private final ListView<Path> files = new ListView<>();
  private final Label error = new Label();
  private Path current;

  private RetroPathDialog(Window owner, String title, Path initial, boolean directoryOnly) {
    this.directoryOnly = directoryOnly;
    RetroDialogs.decorate(this, owner, title);
    getDialogPane().setId("path-dialog");
    getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
    Button choose = (Button) getDialogPane().lookupButton(ButtonType.OK);
    choose.setText(directoryOnly ? "选择文件夹" : "选择 Java");
    ((Button) getDialogPane().lookupButton(ButtonType.CANCEL)).setText("取消");
    drives.setMinWidth(95);
    FileSystems.getDefault().getRootDirectories().forEach(drives.getItems()::add);
    drives.setOnAction(
        event -> {
          if (drives.getValue() != null
              && (current == null || !drives.getValue().equals(current.getRoot())))
            navigate(drives.getValue());
        });
    Button up = new Button("上一级");
    up.setOnAction(
        event -> {
          if (current != null && current.getParent() != null) navigate(current.getParent());
        });
    location.setId("path-location");
    location.setOnAction(event -> openTypedPath());
    HBox.setHgrow(location, Priority.ALWAYS);
    HBox address = new HBox(8, drives, location, up);
    files.setId("path-entries");
    files.setPrefHeight(300);
    files.setCellFactory(
        ignored ->
            new ListCell<>() {
              @Override
              protected void updateItem(Path path, boolean empty) {
                super.updateItem(path, empty);
                setText(
                    empty || path == null
                        ? null
                        : (Files.isDirectory(path) ? "▸ " : "") + path.getFileName());
              }
            });
    files.setPlaceholder(new Label(directoryOnly ? "没有子文件夹" : "没有子文件夹或 Java 程序"));
    files.setOnMouseClicked(
        event -> {
          if (event.getClickCount() == 2) openSelection();
        });
    files.setOnKeyPressed(
        event -> {
          if (event.getCode() == KeyCode.ENTER) {
            openSelection();
            event.consume();
          }
        });
    error.setWrapText(true);
    error.setStyle("-fx-text-fill: #a00000; -fx-font-size: 15px;");
    VBox content = new VBox(12, address, files, error);
    content.setPadding(new Insets(12));
    content.setPrefWidth(700);
    getDialogPane().setContent(content);
    choose.addEventFilter(
        javafx.event.ActionEvent.ACTION,
        event -> {
          Path selected = files.getSelectionModel().getSelectedItem();
          if (directoryOnly) {
            // An edited address must be resolved before returning a folder.
            if (!location.getText().equals(current == null ? "" : current.toString())) {
              if (!openTypedPath()) {
                event.consume();
                return;
              }
              selected = null;
            }
            setResult(selected != null && Files.isDirectory(selected) ? selected : current);
          } else {
            if (!location.getText().equals(current == null ? "" : current.toString())) {
              try {
                Path typed = Path.of(location.getText()).toAbsolutePath().normalize();
                if (isJava(typed)) selected = typed;
                else {
                  openTypedPath();
                  event.consume();
                  return;
                }
              } catch (InvalidPathException ex) {
                error.setText("路径无效");
                event.consume();
                return;
              }
            }
            if (selected == null || !isJava(selected)) {
              error.setText("请选择 java.exe 或 javaw.exe");
              event.consume();
              return;
            }
            setResult(selected);
          }
          if (getResult() == null) event.consume();
        });
    setResultConverter(button -> button == ButtonType.OK ? getResult() : null);
    Path start =
        initial == null
            ? Path.of(System.getProperty("user.home"))
            : initial.toAbsolutePath().normalize();
    if (Files.isRegularFile(start)) start = start.getParent();
    while (start != null && !Files.isDirectory(start)) start = start.getParent();
    navigate(start == null ? Path.of(System.getProperty("user.home")) : start);
  }

  static Optional<Path> directory(Window owner, String title, Path initial) {
    return new RetroPathDialog(owner, title, initial, true).showAndWait();
  }

  static Optional<Path> javaExecutable(Window owner, Path initial) {
    return new RetroPathDialog(owner, "选择此实例的 Java", initial, false).showAndWait();
  }

  private boolean navigate(Path path) {
    try (var entries = Files.list(path)) {
      List<Path> items =
          entries
              .filter(entry -> Files.isDirectory(entry) || (!directoryOnly && isJava(entry)))
              .sorted(
                  Comparator.<Path, Boolean>comparing(entry -> !Files.isDirectory(entry))
                      .thenComparing(
                          entry -> entry.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
              .toList();
      current = path.toAbsolutePath().normalize();
      if (!Objects.equals(drives.getValue(), current.getRoot())) drives.setValue(current.getRoot());
      location.setText(current.toString());
      files.getItems().setAll(items);
      files.getSelectionModel().clearSelection();
      error.setText("");
      return true;
    } catch (IOException | SecurityException ex) {
      error.setText("无法打开目录：" + Objects.toString(ex.getMessage(), path.toString()));
      return false;
    }
  }

  private boolean openTypedPath() {
    try {
      Path path = Path.of(location.getText()).toAbsolutePath().normalize();
      if (!directoryOnly && isJava(path)) {
        setResult(path);
        close();
        return true;
      }
      return navigate(path);
    } catch (InvalidPathException ex) {
      error.setText("路径无效");
      return false;
    }
  }

  private void openSelection() {
    Path selected = files.getSelectionModel().getSelectedItem();
    if (selected == null) return;
    if (Files.isDirectory(selected)) navigate(selected);
    else if (!directoryOnly && isJava(selected)) {
      setResult(selected);
      close();
    }
  }

  private static boolean isJava(Path path) {
    String name = path.getFileName() == null ? "" : path.getFileName().toString();
    return Files.isRegularFile(path)
        && (name.equalsIgnoreCase("java.exe") || name.equalsIgnoreCase("javaw.exe"));
  }
}
