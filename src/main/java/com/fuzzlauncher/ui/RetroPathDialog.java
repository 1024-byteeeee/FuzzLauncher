package com.fuzzlauncher.ui;

import com.fuzzlauncher.infrastructure.BrowserFiles;
import com.fuzzlauncher.infrastructure.WindowsFileClipboard;

import javafx.beans.property.*;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;

/** File picker and local file management using the launcher's shared dialog chrome. */
final class RetroPathDialog extends Dialog<Path> {
    private final boolean directoryOnly;
    private final java.util.function.Predicate<Path> fileFilter;
    private final TextField location = new TextField();
    private final ComboBox<Path> drives = new RetroComboBox<>();
    private final ListView<Path> files = new ListView<>();
    private final Label error = new Label();
    private Path current;
    private final BooleanProperty fileBusy = new SimpleBooleanProperty();

    private RetroPathDialog(Window owner, String title, Path initial, boolean directoryOnly) {
        this(owner, title, initial, directoryOnly, RetroPathDialog::isJava);
    }

    private RetroPathDialog(Window owner, String title, Path initial, boolean directoryOnly,
        java.util.function.Predicate<Path> fileFilter) {
        this.directoryOnly = directoryOnly;
        this.fileFilter = fileFilter;
        RetroDialogs.decorate(this, owner, title);
        getDialogPane().setId("path-dialog");
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Button choose = (Button) getDialogPane().lookupButton(ButtonType.OK);
        choose.setText(directoryOnly ? "选择文件夹" : "选择文件");
        ((Button) getDialogPane().lookupButton(ButtonType.CANCEL)).setText("取消");
        drives.setMinWidth(95);
        FileSystems.getDefault().getRootDirectories().forEach(drives.getItems()::add);
        drives.setOnAction(event -> {
            if (drives.getValue() != null && (current == null || !drives.getValue().equals(current.getRoot()))) {
                navigate(drives.getValue());
            }
        });
        Button up = new Button("上一级");
        up.setOnAction(event -> {
            if (current != null && current.getParent() != null) {
                navigate(current.getParent());
            }
        });
        location.setId("path-location");
        location.setOnAction(event -> openTypedPath());
        HBox.setHgrow(location, Priority.ALWAYS);
        HBox address = new HBox(8, drives, location, up);
        files.setId("path-entries");
        files.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        files.setPrefHeight(300);
        files.setCellFactory(ignored -> new ListCell<>() {
            @Override
            protected void updateItem(Path path, boolean empty) {
                super.updateItem(path, empty);
                setText(empty || path == null ? null : (Files.isDirectory(path) ? "▸ " : "") + path.getFileName());
            }
        });
        files.setPlaceholder(new Label(directoryOnly ? "没有子文件夹" : "没有可选择的文件"));
        files.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                openSelection();
            }
        });
        files.setOnKeyPressed(event -> {
            if (fileBusy.get()) {
                return;
            }
            if (event.isControlDown() && event.getCode() == KeyCode.C) {
                clipboard(false);
                event.consume();
            } else if (event.isControlDown() && event.getCode() == KeyCode.X) {
                clipboard(true);
                event.consume();
            } else if (event.isControlDown() && event.getCode() == KeyCode.V) {
                paste();
                event.consume();
            } else if (event.isControlDown() && event.isShiftDown() && event.getCode() == KeyCode.N) {
                newFolder();
                event.consume();
            } else if (event.getCode() == KeyCode.F2) {
                rename();
                event.consume();
            } else if (event.getCode() == KeyCode.DELETE) {
                recycle();
                event.consume();
            } else if (event.getCode() == KeyCode.F5) {
                navigate(current);
                event.consume();
            } else if (event.getCode() == KeyCode.ENTER) {
                openSelection();
                event.consume();
            }
        });
        installContextMenu();
        error.setWrapText(true);
        error.setStyle("-fx-text-fill: #a00000; -fx-font-size: 15px;");
        VBox content = new VBox(12, address, files, error);
        content.setPadding(new Insets(12));
        content.setPrefWidth(700);
        getDialogPane().setContent(content);
        content.disableProperty().bind(fileBusy);
        for (ButtonType type : getDialogPane().getButtonTypes()) {
            getDialogPane().lookupButton(type).disableProperty().bind(fileBusy);
        }
        setOnCloseRequest(event -> {
            if (fileBusy.get()) {
                event.consume();
            }
        });
        choose.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
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
                        if (fileFilter.test(typed)) {
                            selected = typed;
                        } else {
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
                if (selected == null || !fileFilter.test(selected)) {
                    error.setText("请选择受支持的文件");
                    event.consume();
                    return;
                }
                setResult(selected);
            }
            if (getResult() == null) {
                event.consume();
            }
        });
        setResultConverter(button -> button == ButtonType.OK ? getResult() : null);
        Path start = initial == null ? Path.of(System.getProperty("user.home")) : initial.toAbsolutePath().normalize();
        if (Files.isRegularFile(start)) {
            start = start.getParent();
        }
        while (start != null && !Files.isDirectory(start)) {
            start = start.getParent();
        }
        navigate(start == null ? Path.of(System.getProperty("user.home")) : start);
    }

    static Optional<Path> directory(Window owner, String title, Path initial) {
        return new RetroPathDialog(owner, title, initial, true).showAndWait();
    }

    static Optional<Path> javaExecutable(Window owner, Path initial) {
        return new RetroPathDialog(owner, "选择此实例的 Java", initial, false).showAndWait();
    }

    static Optional<Path> modpack(Window owner, Path initial) {
        return new RetroPathDialog(owner, "选择整合包", initial, false,
            path -> Files.isRegularFile(path) && path.getFileName().toString().toLowerCase(Locale.ROOT).matches(".*\\.(zip|mrpack)"))
            .showAndWait();
    }

    private boolean navigate(Path path) {
        try (var entries = Files.list(path)) {
            List<Path> items = entries.filter(entry -> Files.isDirectory(entry) || (!directoryOnly && fileFilter.test(entry)))
                .sorted(Comparator.<Path, Boolean>comparing(entry -> !Files.isDirectory(entry))
                    .thenComparing(entry -> entry.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                .toList();
            current = path.toRealPath();
            if (!Objects.equals(drives.getValue(), current.getRoot())) {
                drives.setValue(current.getRoot());
            }
            location.setText(current.toString());
            files.getItems().setAll(items.stream().map(entry -> current.resolve(entry.getFileName())).toList());
            files.getSelectionModel().clearSelection();
            error.setText("");
            return true;
        } catch (IOException | SecurityException ex) {
            error.setText("无法打开目录：" + Objects.toString(ex.getMessage(), path.toString()));
            return false;
        }
    }

    private void installContextMenu() {
        MenuItem open = menuItem("打开", "path-open", this::openSelection);
        MenuItem cut = menuItem("剪切", "path-cut", () -> clipboard(true));
        MenuItem copy = menuItem("复制", "path-copy", () -> clipboard(false));
        MenuItem paste = menuItem("粘贴", "path-paste", this::paste);
        MenuItem rename = menuItem("重命名", "path-rename", this::rename);
        MenuItem delete = menuItem("删除", "path-delete", this::recycle);
        MenuItem create = menuItem("新建文件夹", "path-new-folder", this::newFolder);
        MenuItem refresh = menuItem("刷新", "path-refresh", () -> navigate(current));
        ContextMenu menu = new ContextMenu(open, new SeparatorMenuItem(), cut, copy, paste, new SeparatorMenuItem(), rename, delete,
            new SeparatorMenuItem(), create, refresh);
        files.setContextMenu(menu);
        files.setOnContextMenuRequested(event -> {
            if (!event.isKeyboardTrigger()) {
                Node node = event.getTarget() instanceof Node target ? target : null;
                while (node != null && !(node instanceof ListCell<?>)) {
                    node = node.getParent();
                }
                if (node instanceof ListCell<?> cell && !cell.isEmpty()) {
                    if (!files.getSelectionModel().isSelected(cell.getIndex())) {
                        files.getSelectionModel().clearAndSelect(cell.getIndex());
                    }
                } else {
                    files.getSelectionModel().clearSelection();
                }
            }
            int count = files.getSelectionModel().getSelectedItems().size();
            open.setDisable(fileBusy.get() || count != 1);
            cut.setDisable(fileBusy.get() || count == 0);
            copy.setDisable(fileBusy.get() || count == 0);
            rename.setDisable(fileBusy.get() || count != 1);
            delete.setDisable(fileBusy.get() || count == 0);
            paste.setDisable(fileBusy.get() || !WindowsFileClipboard.hasFiles());
            create.setDisable(fileBusy.get());
            refresh.setDisable(fileBusy.get());
            menu.show(files, event.getScreenX(), event.getScreenY());
            event.consume();
        });
        addEventHandler(DialogEvent.DIALOG_HIDDEN, event -> menu.hide());
    }

    private MenuItem menuItem(String title, String id, Runnable action) {
        MenuItem item = new MenuItem(title);
        item.setId(id);
        item.setOnAction(event -> {
            if (!fileBusy.get()) {
                action.run();
            }
        });
        return item;
    }

    private List<Path> selection() {
        return List.copyOf(files.getSelectionModel().getSelectedItems());
    }

    private void clipboard(boolean cut) {
        List<Path> selected = selection();
        if (selected.isEmpty()) {
            return;
        }
        try {
            WindowsFileClipboard.write(selected, cut);
            error.setText("");
        } catch (IOException ex) {
            error.setText(ex.getMessage());
        }
    }

    private void paste() {
        if (fileBusy.get()) {
            return;
        }
        try {
            WindowsFileClipboard.Contents contents = WindowsFileClipboard.read();
            Path destination = current;
            boolean foundCollision = false;
            for (Path source : contents.paths()) {
                Path target = destination.resolve(source.getFileName());
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !(contents.move() && Files.isSameFile(source, target))) {
                    foundCollision = true;
                }
            }
            boolean collisions = foundCollision;
            if (collisions && RetroDialogs
                .alert(getDialogPane().getScene().getWindow(), Alert.AlertType.CONFIRMATION, "存在同名项目", "目标目录已有同名项目。是否为冲突项目自动生成副本名称？")
                .showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                return;
            }
            runFileOperation("正在粘贴", () -> {
                List<Path> completed = new ArrayList<>();
                Path last = null;
                Exception failure = null;
                try {
                    for (Path source : contents.paths()) {
                        last = BrowserFiles.paste(source, destination, contents.move(), collisions);
                        completed.add(source);
                    }
                    return last;
                } catch (Exception ex) {
                    failure = ex;
                    throw ex;
                } finally {
                    if (contents.move() && !completed.isEmpty()) {
                        try {
                            WindowsFileClipboard.completeCut(contents, completed);
                        } catch (IOException ex) {
                            if (failure != null) {
                                failure.addSuppressed(ex);
                            } else {
                                throw ex;
                            }
                        }
                    }
                }
            });
        } catch (IOException | InvalidPathException ex) {
            error.setText(ex.getMessage());
        }
    }

    private Optional<String> promptName(String title, String initial) {
        TextInputDialog input = new TextInputDialog(initial);
        input.setContentText("名称:");
        RetroDialogs.decorate(input, getDialogPane().getScene().getWindow(), title);
        ((Button) input.getDialogPane().lookupButton(ButtonType.OK)).setText("确定");
        ((Button) input.getDialogPane().lookupButton(ButtonType.CANCEL)).setText("取消");
        return input.showAndWait();
    }

    private void newFolder() {
        if (fileBusy.get()) {
            return;
        }
        Path parent = current;
        promptName("新建文件夹", "新建文件夹").ifPresent(name -> runFileOperation("正在新建文件夹", () -> BrowserFiles.createFolder(parent, name)));
    }

    private void rename() {
        if (fileBusy.get() || selection().size() != 1) {
            return;
        }
        Path source = selection().getFirst();
        promptName("重命名", source.getFileName().toString())
            .ifPresent(name -> runFileOperation("正在重命名", () -> BrowserFiles.rename(source, name)));
    }

    private void recycle() {
        if (fileBusy.get()) {
            return;
        }
        List<Path> selected = selection();
        if (selected.isEmpty()) {
            return;
        }
        String names = selected.size() == 1 ? selected.getFirst().getFileName().toString() : selected.size() + " 个项目";
        if (RetroDialogs.alert(getDialogPane().getScene().getWindow(), Alert.AlertType.CONFIRMATION, "删除", "将“" + names + "”移入回收站？")
            .showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        runFileOperation("正在移入回收站", () -> {
            for (Path source : selected) {
                BrowserFiles.recycle(source);
            }
            return null;
        });
    }

    private void runFileOperation(String title, Callable<Path> work) {
        if (fileBusy.get()) {
            return;
        }
        Path directory = current;
        fileBusy.set(true);
        error.setText(title + "…");
        Task<Path> task = new Task<>() {
            @Override
            protected Path call() throws Exception {
                return work.call();
            }
        };
        task.setOnSucceeded(event -> {
            fileBusy.set(false);
            navigate(directory);
            if (task.getValue() != null) {
                files.getSelectionModel().select(task.getValue());
                files.scrollTo(task.getValue());
            }
        });
        task.setOnFailed(event -> {
            fileBusy.set(false);
            navigate(directory);
            error.setText(Objects.toString(task.getException().getMessage(), "文件操作失败"));
        });
        Thread.ofVirtual().name("file-browser-operation").start(task);
    }

    private boolean openTypedPath() {
        try {
            Path path = Path.of(location.getText()).toAbsolutePath().normalize();
            if (!directoryOnly && fileFilter.test(path)) {
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
        if (selected == null) {
            return;
        }
        if (Files.isDirectory(selected)) {
            navigate(selected);
        } else if (!directoryOnly && fileFilter.test(selected)) {
            setResult(selected);
            close();
        }
    }

    private static boolean isJava(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString();
        return Files.isRegularFile(path) && (name.equalsIgnoreCase("java.exe") || name.equalsIgnoreCase("javaw.exe"));
    }
}
