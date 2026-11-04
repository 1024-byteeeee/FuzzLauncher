package com.fuzzlauncher.ui;

import com.fuzzlauncher.application.TaskContext;
import com.fuzzlauncher.domain.*;
import com.fuzzlauncher.infrastructure.LauncherLog;
import com.fuzzlauncher.infrastructure.mod.ModFiles;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.*;
import javafx.beans.value.ChangeListener;
import javafx.collections.*;
import javafx.collections.transformation.*;
import javafx.concurrent.Task;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Local-only mod management. Scans belong to the page; mutations belong to the ViewModel. */
public final class ModsPage extends RetroPage {
    private final ComboBox<GameInstance> instances;
    private final ObservableList<ModFile> mods = FXCollections.observableArrayList();
    private final ListView<ModFile> list = new ListView<>();
    private final BooleanProperty scanning = new SimpleBooleanProperty();
    private final Label summary = new Label(), directory = new Label();
    private Task<List<ModFile>> scan;
    private long generation;
    private final javafx.event.EventHandler<javafx.stage.WindowEvent> windowHidden = event -> cancelScan();

    public ModsPage(Stage stage, LauncherViewModel vm, Consumer<Integer> navigate) {
        super(stage, vm, navigate);
        instances = new RetroComboBox<>(vm.instances());
        instances.setId("mod-instance");
        instances.setMaxWidth(Double.MAX_VALUE);
        instances.setMinWidth(0);
        instances.setValue(vm.selectedProperty().get() != null ? vm.selectedProperty().get()
            : vm.instances().isEmpty() ? null : vm.instances().getFirst());
        instances.setConverter(new javafx.util.StringConverter<>() {
            public String toString(GameInstance value) {
                return value == null ? "" : value.name();
            }

            public GameInstance fromString(String value) {
                return null;
            }
        });
        instances.disableProperty().bind(vm.busyProperty());
        instances.valueProperty().addListener((observable, old, selected) -> {
            if (selected != null) {
                vm.selectInstance(selected);
            }
            refresh();
        });
        setCenter(build());
        ChangeListener<GameInstance> selected = (observable, old, value) -> instances.setValue(value);
        ChangeListener<LauncherSettings> settings = (observable, old, value) -> refresh();
        sceneProperty().addListener((observable, old, scene) -> {
            if (old != null) {
                vm.selectedProperty().removeListener(selected);
                vm.settingsProperty().removeListener(settings);
                if (old.getWindow() != null) {
                    old.getWindow().removeEventHandler(javafx.stage.WindowEvent.WINDOW_HIDDEN, windowHidden);
                }
            }
            if (scene == null) {
                cancelScan();
            } else {
                vm.selectedProperty().addListener(selected);
                vm.settingsProperty().addListener(settings);
                if (scene.getWindow() != null) {
                    scene.getWindow().addEventHandler(javafx.stage.WindowEvent.WINDOW_HIDDEN, windowHidden);
                }
                refresh();
            }
        });
    }

    private Node build() {
        var blocked = vm.busyProperty().or(vm.gameRunningProperty()).or(scanning).or(instances.valueProperty().isNull());
        var noSelection = Bindings.isEmpty(list.getSelectionModel().getSelectedItems());
        Button add = button("导入", () -> importFiles(RetroPathDialog.mods(stage, Path.of(System.getProperty("user.home")))));
        add.setId("import-mods");
        add.disableProperty().bind(blocked);
        Button refresh = button("刷新", this::refresh);
        refresh.setId("refresh-mods");
        refresh.disableProperty().bind(vm.busyProperty().or(instances.valueProperty().isNull()));
        Button enable = button("启用", () -> setEnabled(true));
        enable.setId("enable-mods");
        enable.disableProperty()
            .bind(blocked.or(noSelection).or(Bindings.createBooleanBinding(() -> selected().stream().noneMatch(mod -> !mod.enabled()),
                list.getSelectionModel().getSelectedItems())));
        Button disable = button("禁用", () -> setEnabled(false));
        disable.setId("disable-mods");
        disable.disableProperty().bind(blocked.or(noSelection).or(Bindings
            .createBooleanBinding(() -> selected().stream().noneMatch(ModFile::enabled), list.getSelectionModel().getSelectedItems())));
        Button delete = button("删除", this::deleteSelected);
        delete.setId("delete-mods");
        delete.disableProperty().bind(blocked.or(noSelection));
        Button folder = button("打开模组目录", () -> openFolder(vm.modDirectory(instances.getValue())));
        folder.setId("open-mods-directory");
        folder.disableProperty().bind(vm.busyProperty().or(instances.valueProperty().isNull()));
        HBox toolbar = new HBox(6, add, refresh, enable, disable, delete, folder);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        TextField search = new TextField();
        search.setId("mod-search");
        search.setPromptText("搜索模组名称、ID 或文件名");
        FilteredList<ModFile> filtered = new FilteredList<>(mods);
        ComboBox<String> state = new RetroComboBox<>();
        state.setId("mod-state-filter");
        state.getItems().setAll("全部", "已启用", "已禁用");
        state.setValue("全部");
        Runnable filter = () -> {
            String text = search.getText().strip().toLowerCase(Locale.ROOT);
            filtered.setPredicate(mod -> {
                boolean matches = switch (state.getValue()) {
                    case "已启用" -> mod.enabled();
                    case "已禁用" -> !mod.enabled();
                    default -> true;
                };
                return matches && (mod.name() + " " + mod.path().getFileName() + " "
                    + mod.metadata().stream().map(ModFile.Metadata::id).collect(Collectors.joining(" "))).toLowerCase(Locale.ROOT)
                    .contains(text);
            });
        };
        search.textProperty().addListener((o, old, value) -> filter.run());
        state.valueProperty().addListener((o, old, value) -> filter.run());
        HBox.setHgrow(search, Priority.ALWAYS);
        HBox filters = new HBox(8, search, state);
        list.setId("mod-list");
        list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        list.setCellFactory(ignored -> new ModCell(blocked));
        list.setFixedCellSize(64);
        ComboBox<String> order = new RetroComboBox<>();
        order.setId("mod-sort");
        order.getItems().setAll("按名称", "按文件名", "按启用状态");
        order.setValue("按名称");
        SortedList<ModFile> sorted = new SortedList<>(filtered);
        Runnable sort = () -> {
            Comparator<ModFile> byName = Comparator.comparing(ModFile::name, String.CASE_INSENSITIVE_ORDER);
            sorted.setComparator(switch (order.getValue()) {
                case "按文件名" -> Comparator.comparing(mod -> mod.path().getFileName().toString(), String.CASE_INSENSITIVE_ORDER);
                case "按启用状态" -> Comparator.comparing((ModFile mod) -> !mod.enabled()).thenComparing(byName);
                default -> byName;
            });
        };
        order.valueProperty().addListener((o, old, value) -> sort.run());
        sort.run();
        filters.getChildren().add(order);
        list.setItems(sorted);
        Label empty = new Label();
        empty.textProperty().bind(Bindings.when(scanning).then("正在读取模组…")
            .otherwise(Bindings.when(instances.valueProperty().isNull()).then("请先创建游戏实例").otherwise("暂无匹配模组")));
        list.setPlaceholder(empty);
        list.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.DELETE && !delete.isDisabled()) {
                delete.fire();
                event.consume();
            } else if (event.getCode() == KeyCode.F5 && !refresh.isDisabled()) {
                refresh.fire();
                event.consume();
            }
        });
        ContextMenu menu = new ContextMenu();
        for (Button action : List.of(enable, disable, delete, refresh, folder)) {
            MenuItem item = new MenuItem(action.getText());
            item.disableProperty().bind(action.disableProperty());
            item.setOnAction(event -> action.fire());
            menu.getItems().add(item);
        }
        list.setContextMenu(menu);
        list.setOnDragOver(event -> {
            if (!blocked.get() && event.getDragboard().hasFiles()
                && event.getDragboard().getFiles().stream().anyMatch(file -> ModFiles.supported(file.toPath()))) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });
        list.setOnDragDropped(event -> {
            List<Path> sources = event.getDragboard().getFiles().stream().map(java.io.File::toPath).toList();
            boolean accepted = !blocked.get() && !sources.isEmpty();
            event.setDropCompleted(accepted);
            if (accepted) {
                Platform.runLater(() -> importFiles(sources));
            }
            event.consume();
        });
        list.setMinWidth(0);
        VBox.setVgrow(list, Priority.ALWAYS);
        directory.setWrapText(true);
        directory.setStyle("-fx-font-size: 14px;");
        summary.setId("mod-summary");
        summary.setWrapText(true);
        Label running = new Label("游戏运行中，模组修改已锁定");
        running.visibleProperty().bind(vm.gameRunningProperty());
        running.managedProperty().bind(running.visibleProperty());
        Label instanceLabel = new Label("选择实例：");
        instanceLabel.setLabelFor(instances);
        instanceLabel.setMinWidth(Region.USE_PREF_SIZE);
        instances.setPromptText("请选择游戏实例");
        HBox.setHgrow(instances, Priority.ALWAYS);
        HBox instanceSelector = new HBox(10, instanceLabel, instances);
        instanceSelector.setAlignment(Pos.CENTER_LEFT);
        VBox content = new VBox(12, label("模组管理", "section-title"), instanceSelector, directory, toolbar, filters, list, summary, running);
        content.getStyleClass().add("content");
        return content;
    }

    private final class ModCell extends ListCell<ModFile> {
        private final CheckBox enabled = new CheckBox();
        private final ImageView icon = new ImageView();
        private final Label title = new Label(), version = new Label(), subtitle = new Label();
        private final HBox row;
        private ModFile displayed;

        private ModCell(javafx.beans.binding.BooleanExpression blocked) {
            enabled.getStyleClass().add("mod-enabled");
            enabled.setFocusTraversable(false);
            enabled.setAccessibleText("启用模组");
            enabled.disableProperty().bind(blocked);
            enabled.setOnAction(event -> {
                ModFile mod = getItem();
                if (mod != null) {
                    boolean requested = enabled.isSelected();
                    enabled.setSelected(mod.enabled());
                    GameInstance instance = instances.getValue();
                    vm.setModsEnabled(instance, List.of(mod), requested, changed(instance));
                }
            });
            icon.setFitWidth(32);
            icon.getStyleClass().add("mod-logo");
            icon.setFitHeight(32);
            icon.setPreserveRatio(true);
            icon.setSmooth(false);
            StackPane iconSlot = new StackPane(icon);
            iconSlot.setMinSize(36, 36);
            iconSlot.setPrefSize(36, 36);
            iconSlot.setMaxSize(36, 36);
            title.getStyleClass().add("mod-entry-name");
            title.setMinWidth(0);
            version.getStyleClass().add("mod-entry-version");
            version.minWidthProperty().bind(Bindings.createDoubleBinding(() -> Math.min(160, version.prefWidth(-1)), version.textProperty(),
                version.fontProperty(), version.paddingProperty()));
            version.setMaxWidth(160);
            HBox heading = new HBox(8, title, version);
            heading.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(title, Priority.SOMETIMES);
            subtitle.getStyleClass().add("mod-entry-subtitle");
            subtitle.setMinWidth(0);
            VBox text = new VBox(2, heading, subtitle);
            text.setMinWidth(0);
            text.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(text, Priority.ALWAYS);
            Button locate = entryButton("定位模组文件", "M1 4 H8 L10 6 H20 V18 H1 Z", () -> locate(getItem()));
            locate.getStyleClass().add("mod-locate");
            locate.disableProperty().bind(vm.busyProperty());
            Button info = entryButton("模组详情", "M10 1 A9 9 0 1 0 10 19 A9 9 0 1 0 10 1 M10 8 V15 M10 5 V6", () -> details(getItem()));
            info.getStyleClass().add("mod-info");
            row = new HBox(10, enabled, iconSlot, text, locate, info);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setMinWidth(0);
            row.prefWidthProperty().bind(Bindings.max(0, widthProperty().subtract(20)));
            getStyleClass().add("mod-entry");
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            setOnMouseClicked(event -> {
                for (Node target = event.getTarget() instanceof Node node ? node : null; target != null
                    && target != this; target = target.getParent()) {
                    if (target instanceof ButtonBase) {
                        return;
                    }
                }
                if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2 && !isEmpty()) {
                    details(getItem());
                    event.consume();
                }
            });
        }

        @Override
        protected void updateItem(ModFile mod, boolean empty) {
            super.updateItem(mod, empty);
            setText(null);
            if (empty || mod == null) {
                displayed = null;
                icon.setImage(null);
                setGraphic(null);
                setTooltip(null);
                return;
            }
            enabled.setSelected(mod.enabled());
            if (displayed != mod) {
                displayed = mod;
                title.setText(mod.name());
                version.setText(mod.version());
                version.setManaged(!mod.version().isBlank());
                version.setVisible(version.isManaged());
                String ids = mod.metadata().stream().map(ModFile.Metadata::id).collect(Collectors.joining(", "));
                subtitle.setText((ids.isBlank() ? "" : ids + " | ") + mod.path().getFileName());
                byte[] bytes = mod.icon();
                icon.setImage(bytes == null ? image("mods") : new Image(new ByteArrayInputStream(bytes)));
                setTooltip(
                    new Tooltip(mod.name() + "\n" + mod.path().getFileName() + (mod.problem().isBlank() ? "" : "\n" + mod.problem())));
            }
            setGraphic(row);
        }
    }

    private Button entryButton(String title, String path, Runnable action) {
        Button button = button("", action);
        javafx.scene.shape.SVGPath graphic = new javafx.scene.shape.SVGPath();
        graphic.setContent(path);
        graphic.setFill(javafx.scene.paint.Color.TRANSPARENT);
        graphic.setStroke(javafx.scene.paint.Color.BLACK);
        graphic.setStrokeWidth(1.5);
        graphic.setMouseTransparent(true);
        StackPane slot = new StackPane(graphic);
        slot.setMinSize(22, 22);
        slot.setPrefSize(22, 22);
        slot.setMaxSize(22, 22);
        button.setGraphic(slot);
        button.setMinSize(32, 30);
        button.setPrefSize(32, 30);
        button.setMaxSize(32, 30);
        button.setAccessibleText(title);
        button.setTooltip(new Tooltip(title));
        button.getStyleClass().add("mod-entry-action");
        return button;
    }

    private void locate(ModFile mod) {
        if (mod == null) {
            return;
        }
        try {
            java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
            if (desktop.isSupported(java.awt.Desktop.Action.BROWSE_FILE_DIR)) {
                desktop.browseFileDirectory(mod.path().toFile());
            } else {
                desktop.open(mod.path().getParent().toFile());
            }
        } catch (Exception ex) {
            message(Alert.AlertType.ERROR, "无法定位模组文件", ex.getMessage());
        }
    }

    private void cancelScan() {
        generation++;
        if (scan != null) {
            scan.cancel(true);
            scan = null;
        }
        scanning.set(false);
    }

    private void refresh() {
        Set<Path> selection = selected().stream().map(ModFile::path).collect(Collectors.toSet());
        cancelScan();
        mods.clear();
        GameInstance instance = instances.getValue();
        if (getScene() == null || instance == null) {
            directory.setText("");
            summary.setText(instance == null ? "尚无游戏实例" : "");
            return;
        }
        Path path = vm.modDirectory(instance);
        Path gameRoot = Path.of(vm.settingsProperty().get().gameRoot()).toAbsolutePath().normalize();
        directory.setText(path + (path.getParent().equals(gameRoot) ? "\n共享目录：修改会影响使用此目录的实例" : ""));
        scanning.set(true);
        summary.setText("正在读取模组…");
        long ticket = generation;
        Task<List<ModFile>> task = new Task<>() {
            @Override
            protected List<ModFile> call() throws Exception {
                return vm.scanMods(path, new TaskContext() {
                    public void report(String message, double progress) {
                    }

                    public boolean cancelled() {
                        return isCancelled();
                    }
                });
            }
        };
        task.setOnSucceeded(event -> publish(task.getValue(), 0, ticket, selection));
        task.setOnFailed(event -> {
            if (ticket == generation) {
                scanning.set(false);
                summary.setText("无法读取模组：" + task.getException().getMessage());
                LauncherLog.LOGGER.warning(summary.getText());
            }
        });
        scan = task;
        Thread.ofVirtual().name("mod-scan").start(task);
    }

    private void publish(List<ModFile> values, int offset, long ticket, Set<Path> selection) {
        if (ticket != generation || getScene() == null) {
            return;
        }
        int end = Math.min(values.size(), offset + 128);
        mods.addAll(values.subList(offset, end));
        if (end < values.size()) {
            Platform.runLater(() -> publish(values, end, ticket, selection));
        } else {
            scanning.set(false);
            scan = null;
            for (ModFile mod : list.getItems()) {
                if (selection.contains(mod.path())) {
                    list.getSelectionModel().select(mod);
                }
            }
            if (list.getSelectionModel().isEmpty() && !list.getItems().isEmpty()) {
                list.getSelectionModel().selectFirst();
            }
            long enabled = values.stream().filter(ModFile::enabled).count();
            long problems = values.stream().filter(mod -> !mod.problem().isBlank()).count();
            summary.setText(values.size() + " 个模组文件 · 启用 " + enabled + " · 禁用 " + (values.size() - enabled)
                + (problems == 0 ? "" : " · 异常 " + problems));
        }
    }

    private List<ModFile> selected() {
        return List.copyOf(list.getSelectionModel().getSelectedItems());
    }

    private Runnable changed(GameInstance instance) {
        return () -> {
            if (getScene() != null && Objects.equals(instance, instances.getValue())) {
                refresh();
            }
        };
    }

    private void importFiles(List<Path> sources) {
        GameInstance instance = instances.getValue();
        if (instance == null || sources.isEmpty() || vm.busyProperty().get() || vm.gameRunningProperty().get() || scanning.get()) {
            return;
        }
        Set<String> existing = mods.stream().map(mod -> ModFiles.logicalName(mod.path().getFileName().toString()))
            .collect(Collectors.toSet());
        Set<String> incoming = new HashSet<>();
        boolean collision = false;
        for (Path path : sources) {
            if (ModFiles.supported(path)) {
                String name = ModFiles.logicalName(path.getFileName().toString());
                collision |= existing.contains(name) || !incoming.add(name);
            }
        }
        boolean rename = false;
        if (collision) {
            ChoiceDialog<String> choice = new ChoiceDialog<>("跳过同名文件", "跳过同名文件", "保留两份");
            RetroDialogs.decorate(choice, stage, "同名模组");
            choice.setContentText("已存在同名启用或禁用文件，处理方式：");
            ((Button) choice.getDialogPane().lookupButton(ButtonType.OK)).setText("确定");
            ((Button) choice.getDialogPane().lookupButton(ButtonType.CANCEL)).setText("取消");
            Optional<String> result = choice.showAndWait();
            if (result.isEmpty()) {
                return;
            }
            rename = result.get().equals("保留两份");
        }
        vm.importMods(instance, sources, rename, changed(instance));
    }

    private void setEnabled(boolean enabled) {
        GameInstance instance = instances.getValue();
        vm.setModsEnabled(instance, selected(), enabled, changed(instance));
    }

    private void deleteSelected() {
        GameInstance instance = instances.getValue();
        List<ModFile> files = selected();
        if (!files.isEmpty() && confirm("删除模组", "将选中的 " + files.size() + " 个模组文件移入回收站？")) {
            vm.deleteMods(instance, files, changed(instance));
        }
    }

    private void details(ModFile mod) {
        if (mod == null) {
            return;
        }
        Dialog<Void> dialog = new Dialog<>();
        RetroDialogs.decorate(dialog, stage, "模组详情");
        dialog.getDialogPane().setId("mod-details-dialog");
        Label name = label(mod.name(), "section-title");
        name.setWrapText(true);
        byte[] bytes = mod.icon();
        ImageView artwork = new ImageView(bytes == null ? image("mods") : new Image(new ByteArrayInputStream(bytes)));
        artwork.setFitWidth(48);
        artwork.setFitHeight(48);
        artwork.setPreserveRatio(true);
        artwork.setSmooth(false);
        TextArea description = new TextArea();
        description.setId("mod-details");
        description.setEditable(false);
        description.setWrapText(true);
        description.setPrefRowCount(12);
        HBox heading = new HBox(12, artwork, name);
        heading.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(name, Priority.ALWAYS);
        VBox content = new VBox(12, heading, description);
        content.setPadding(new Insets(12));
        content.setPrefWidth(540);
        VBox.setVgrow(description, Priority.ALWAYS);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        ((Button) dialog.getDialogPane().lookupButton(ButtonType.CLOSE)).setText("关闭");
        List<String> parts = new ArrayList<>();
        for (var entry : mod.metadata()) {
            parts.add(entry.name() + "\nID：" + entry.id() + "\n版本：" + entry.version() + "\n作者：" + entry.authors() + "\n加载器："
                + String.join(" / ", new TreeSet<>(entry.loaders())) + "\n\n" + entry.description());
        }
        GameInstance instance = instances.getValue();
        if (instance != null) {
            String expected = switch (instance.loader()) {
                case FABRIC -> "Fabric";
                case FORGE -> "Forge";
                case NEOFORGE -> "NeoForge";
                default -> "";
            };
            if (expected.isEmpty()) {
                parts.add("当前实例为原版，不会加载 mods 目录中的模组。");
            } else if (!mod.metadata().isEmpty() && mod.metadata().stream().noneMatch(entry -> entry.loaders().contains(expected))) {
                parts.add("模组声明的加载器与当前实例不同，请检查兼容性。");
            }
        }
        if (!mod.problem().isBlank()) {
            parts.add(mod.problem());
        }
        parts.add("文件：" + mod.path().getFileName());
        description.setText(String.join("\n\n", parts));
        description.positionCaret(0);
        dialog.showAndWait();
    }
}
