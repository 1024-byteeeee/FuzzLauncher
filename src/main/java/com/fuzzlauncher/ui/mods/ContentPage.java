package com.fuzzlauncher.ui.mods;

import com.fuzzlauncher.application.task.TaskContext;
import com.fuzzlauncher.domain.instance.GameInstance;
import com.fuzzlauncher.domain.content.ContentFile;
import com.fuzzlauncher.domain.content.ContentKind;
import com.fuzzlauncher.domain.mod.ModFileNames;
import com.fuzzlauncher.domain.settings.LauncherSettings;
import com.fuzzlauncher.ui.common.RetroComboBox;
import com.fuzzlauncher.ui.common.RetroDialogs;
import com.fuzzlauncher.ui.common.RetroPage;
import com.fuzzlauncher.ui.common.RetroPathDialog;
import com.fuzzlauncher.ui.viewmodel.LauncherViewModel;
import com.fuzzlauncher.ui.viewmodel.ContentViewModel;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanExpression;
import javafx.beans.property.*;
import javafx.beans.value.ChangeListener;
import javafx.collections.*;
import javafx.collections.transformation.*;
import javafx.concurrent.Task;
import javafx.event.EventHandler;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import javafx.util.StringConverter;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/** Shared content layout. Scans belong to the page; mutations belong to the ViewModel. */
public final class ContentPage extends RetroPage {
    private final LauncherViewModel vm;
    private final ContentViewModel model;
    private final ContentKind kind;
    private final boolean schematics;
    private final String contentLabel;
    private final ComboBox<GameInstance> instances;
    private final ObservableList<ContentFile> mods = FXCollections.observableArrayList();
    private final ListView<ContentFile> list = new ListView<>();
    private final BooleanProperty scanning = new SimpleBooleanProperty();
    private final Label summary = new Label(), directory = new Label();
    private final ObjectProperty<Path> subdirectory = new SimpleObjectProperty<>(Path.of(""));
    private final ObjectProperty<Path> displayedDirectory = new SimpleObjectProperty<>();
    private Task<List<ContentFile>> scan;
    private long generation;
    private final EventHandler<WindowEvent> windowHidden = event -> cancelScan();

    public ContentPage(Stage stage, LauncherViewModel vm, ContentViewModel model, Consumer<Integer> navigate) {
        super(stage, navigate);
        this.vm = vm;
        this.model = model;
        kind = model.kind();
        schematics = kind == ContentKind.SCHEMATICS;
        contentLabel = model.label();
        instances = new RetroComboBox<>(vm.instances());
        instances.setId(id("mod-instance"));
        instances.setMaxWidth(Double.MAX_VALUE);
        instances.setMinWidth(0);
        instances.setValue(vm.selectedProperty().get() != null ? vm.selectedProperty().get()
            : vm.instances().isEmpty() ? null : vm.instances().getFirst());
        instances.setConverter(new StringConverter<>() {
            public String toString(GameInstance value) {
                return value == null ? "" : value.name();
            }

            public GameInstance fromString(String value) {
                return null;
            }
        });
        var noInstances = Bindings.isEmpty(instances.getItems());
        instances.disableProperty().bind(vm.busyProperty().or(noInstances));
        instances.promptTextProperty().bind(Bindings.when(noInstances).then("当前无可用游戏实例").otherwise("请选择游戏实例"));
        instances.valueProperty().addListener((observable, old, selected) -> {
            subdirectory.set(Path.of(""));
            if (selected != null) {
                vm.instancesModel().selectInstance(selected);
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
                    old.getWindow().removeEventHandler(WindowEvent.WINDOW_HIDDEN, windowHidden);
                }
            }
            if (scene == null) {
                cancelScan();
            } else {
                vm.selectedProperty().addListener(selected);
                vm.settingsProperty().addListener(settings);
                if (scene.getWindow() != null) {
                    scene.getWindow().addEventHandler(WindowEvent.WINDOW_HIDDEN, windowHidden);
                }
                refresh();
            }
        });
    }

    private String id(String modId) {
        return switch (kind) {
            case MODS -> modId;
            case RESOURCE_PACKS -> modId.replace("mod", "resource-pack");
            case SCHEMATICS -> modId.replace("mod", "schematic");
        };
    }

    private Image defaultIcon(ContentFile item) {
        return image(switch (kind) {
            case MODS -> "mods";
            case RESOURCE_PACKS -> "resource-packs";
            case SCHEMATICS -> item.directory() ? "folder" : "schematics";
        });
    }
    private boolean importable(Path path) {
        return Files.isDirectory(path) ? kind.supportsFolders() : kind.supportsArchive(path);
    }

    private Node build() {
        var blocked = vm.busyProperty().or(vm.gameRunningProperty()).or(scanning).or(displayedDirectory.isNull());
        var noSelection = Bindings.isEmpty(list.getSelectionModel().getSelectedItems());
        Button add = button("导入", () -> importFiles(RetroPathDialog.content(stage, Path.of(System.getProperty("user.home")), vm.browser(), kind)));
        add.setId(id("import-mods"));
        add.disableProperty().bind(blocked);
        Button refresh = button("刷新", this::refresh);
        refresh.setId(id("refresh-mods"));
        refresh.disableProperty().bind(vm.busyProperty().or(instances.valueProperty().isNull()));
        Button enable = button("启用", () -> setEnabled(true));
        enable.setId(id("enable-mods"));
        enable.disableProperty()
            .bind(blocked.or(noSelection).or(Bindings.createBooleanBinding(() -> selected().stream().noneMatch(mod -> !mod.enabled()),
                list.getSelectionModel().getSelectedItems())));
        Button disable = button("禁用", () -> setEnabled(false));
        disable.setId(id("disable-mods"));
        disable.disableProperty().bind(blocked.or(noSelection).or(Bindings
            .createBooleanBinding(() -> selected().stream().noneMatch(ContentFile::enabled), list.getSelectionModel().getSelectedItems())));
        Button delete = button("删除", this::deleteSelected);
        delete.setId(id("delete-mods"));
        delete.disableProperty().bind(blocked.or(noSelection));
        Button folder = button("打开" + contentLabel + "目录", () -> {
            if (displayedDirectory.get() != null) {
                openFolder(displayedDirectory.get());
            }
        });
        folder.setId(id("open-mods-directory"));
        folder.disableProperty().bind(vm.busyProperty().or(displayedDirectory.isNull()));
        HBox toolbar;
        if (schematics) {
            Button back = button("上一级", () -> {
                Path parent = subdirectory.get().getParent();
                subdirectory.set(parent == null ? Path.of("") : parent);
                refresh();
            });
            back.setId("schematics-back");
            back.disableProperty().bind(vm.busyProperty().or(vm.gameRunningProperty()).or(scanning)
                .or(subdirectory.isEqualTo(Path.of(""))));
            Button create = button("新建文件夹", this::createFolder);
            create.setId("schematics-create-folder");
            create.disableProperty().bind(blocked);
            toolbar = new HBox(6, back, refresh, add, create, folder);
        } else {
            toolbar = new HBox(6, add, refresh, enable, disable, delete, folder);
        }
        toolbar.setAlignment(Pos.CENTER_LEFT);
        TextField search = new TextField();
        search.setId(id("mod-search"));
        search.setPromptText(kind == ContentKind.MODS ? "搜索模组名称、ID 或文件名" : "搜索" + contentLabel + "名称或文件名");
        FilteredList<ContentFile> filtered = new FilteredList<>(mods);
        ComboBox<String> state = new RetroComboBox<>();
        state.setId(id("mod-state-filter"));
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
                    + mod.metadata().stream().map(ContentFile.Metadata::id).collect(Collectors.joining(" "))).toLowerCase(Locale.ROOT)
                    .contains(text);
            });
        };
        search.textProperty().addListener((o, old, value) -> filter.run());
        state.valueProperty().addListener((o, old, value) -> filter.run());
        HBox.setHgrow(search, Priority.ALWAYS);
        HBox filters = schematics ? new HBox(8, search) : new HBox(8, search, state);
        list.setId(id("mod-list"));
        list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        list.setCellFactory(ignored -> new ModCell(blocked));
        list.setFixedCellSize(64);
        ComboBox<String> order = new RetroComboBox<>();
        order.setId(id("mod-sort"));
        order.getItems().setAll(schematics ? List.of("按名称", "按文件名") : List.of("按名称", "按文件名", "按启用状态"));
        order.setValue("按名称");
        SortedList<ContentFile> sorted = new SortedList<>(filtered);
        Runnable sort = () -> {
            Comparator<ContentFile> byName = Comparator.comparing(ContentFile::name, String.CASE_INSENSITIVE_ORDER);
            Comparator<ContentFile> ordering = switch (order.getValue()) {
                case "按文件名" -> Comparator.comparing(mod -> mod.path().getFileName().toString(), String.CASE_INSENSITIVE_ORDER);
                case "按启用状态" -> Comparator.comparing((ContentFile mod) -> !mod.enabled()).thenComparing(byName);
                default -> byName;
            };
            sorted.setComparator(schematics ? Comparator.comparing((ContentFile item) -> !item.directory()).thenComparing(ordering) : ordering);
        };
        order.valueProperty().addListener((o, old, value) -> sort.run());
        sort.run();
        filters.getChildren().add(order);
        list.setItems(sorted);
        Label empty = new Label();
        empty.textProperty().bind(Bindings.when(scanning).then("正在读取" + contentLabel + "…")
            .otherwise(Bindings.when(instances.valueProperty().isNull()).then("请先创建游戏实例").otherwise("暂无匹配" + contentLabel)));
        list.setPlaceholder(empty);
        list.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.DELETE && !delete.isDisabled()) {
                delete.fire();
                event.consume();
            } else if (event.getCode() == KeyCode.F5 && !refresh.isDisabled()) {
                refresh.fire();
                event.consume();
            } else if (schematics && event.getCode() == KeyCode.ENTER && !blocked.get()) {
                ContentFile item = list.getSelectionModel().getSelectedItem();
                if (item != null && item.directory()) {
                    enterFolder(item);
                    event.consume();
                }
            }
        });
        ContextMenu menu = new ContextMenu();
        for (Button action : schematics ? List.of(delete, refresh, folder) : List.of(enable, disable, delete, refresh, folder)) {
            MenuItem item = new MenuItem(action.getText());
            item.disableProperty().bind(action.disableProperty());
            item.setOnAction(event -> action.fire());
            menu.getItems().add(item);
        }
        list.setContextMenu(menu);
        list.setOnDragOver(event -> {
            if (!blocked.get() && event.getDragboard().hasFiles()
                && event.getDragboard().getFiles().stream().anyMatch(file -> importable(file.toPath()))) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });
        list.setOnDragDropped(event -> {
            List<Path> sources = event.getDragboard().getFiles().stream().map(File::toPath).filter(this::importable).toList();
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
        summary.setId(id("mod-summary"));
        summary.setWrapText(true);
        Label running = new Label("游戏运行中，" + contentLabel + "修改已锁定");
        running.visibleProperty().bind(vm.gameRunningProperty());
        running.managedProperty().bind(running.visibleProperty());
        Label instanceLabel = new Label("选择实例：");
        instanceLabel.setLabelFor(instances);
        instanceLabel.setMinWidth(Region.USE_PREF_SIZE);
        HBox.setHgrow(instances, Priority.ALWAYS);
        HBox instanceSelector = new HBox(10, instanceLabel, instances);
        instanceSelector.setAlignment(Pos.CENTER_LEFT);
        VBox content = new VBox(12, label(contentLabel + "管理", "section-title"), instanceSelector, directory, toolbar, filters, list, summary, running);
        content.getStyleClass().add("content");
        return content;
    }

    private final class ModCell extends ListCell<ContentFile> {
        private final CheckBox enabled = new CheckBox();
        private final ImageView icon = new ImageView();
        private final Label title = new Label(), version = new Label(), subtitle = new Label();
        private final HBox row;
        private ContentFile displayed;

        private ModCell(BooleanExpression blocked) {
            enabled.getStyleClass().add("mod-enabled");
            enabled.setFocusTraversable(false);
            enabled.setAccessibleText("启用" + contentLabel);
            enabled.disableProperty().bind(blocked);
            enabled.setOnAction(event -> {
                ContentFile mod = getItem();
                if (mod != null) {
                    boolean requested = enabled.isSelected();
                    enabled.setSelected(mod.enabled());
                    GameInstance instance = instances.getValue();
                    model.setEnabled(displayedDirectory.get(), List.of(mod), requested, changed(instance));
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
            Button locate = entryButton("定位" + contentLabel, "M1 4 H8 L10 6 H20 V18 H1 Z", () -> locate(getItem()));
            locate.getStyleClass().add("mod-locate");
            locate.disableProperty().bind(vm.busyProperty());
            Button info = entryButton(contentLabel + "详情", "M10 1 A9 9 0 1 0 10 19 A9 9 0 1 0 10 1 M10 8 V15 M10 5 V6", () -> details(getItem()));
            info.getStyleClass().add("mod-info");
            if (schematics) {
                Button remove = entryButton("删除", "M4 5 H18 M7 5 V3 H15 V5 M6 5 L7 18 H15 L16 5 M10 8 V15 M13 8 V15",
                    () -> deleteEntries(getItem() == null ? List.of() : List.of(getItem())));
                remove.disableProperty().bind(blocked);
                row = new HBox(10, iconSlot, text, locate, remove);
            } else {
                row = new HBox(10, enabled, iconSlot, text, locate, info);
            }
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
                    if (schematics && getItem().directory() && !blocked.get()) {
                        enterFolder(getItem());
                    } else if (!schematics) {
                        details(getItem());
                    }
                    event.consume();
                }
            });
        }

        @Override
        protected void updateItem(ContentFile mod, boolean empty) {
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
                String ids = mod.metadata().stream().map(ContentFile.Metadata::id).collect(Collectors.joining(", "));
                subtitle.setText(schematics ? (mod.directory() ? "文件夹" : "原理图文件")
                    : (ids.isBlank() ? "" : ids + " | ") + mod.path().getFileName());
                byte[] bytes = mod.icon();
                icon.setImage(bytes == null ? defaultIcon(mod) : new Image(new ByteArrayInputStream(bytes)));
                setTooltip(
                    new Tooltip(mod.name() + "\n" + mod.path().getFileName() + (mod.problem().isBlank() ? "" : "\n" + mod.problem())));
            }
            setGraphic(row);
        }
    }

    private Button entryButton(String title, String path, Runnable action) {
        Button button = button("", action);
        SVGPath graphic = new SVGPath();
        graphic.setContent(path);
        graphic.setFill(Color.TRANSPARENT);
        graphic.setStroke(Color.BLACK);
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

    private void locate(ContentFile mod) {
        if (mod == null) {
            return;
        }
        try {
            vm.browser().reveal(mod.path());
        } catch (IOException ex) {
            message(Alert.AlertType.ERROR, "无法定位" + contentLabel, ex.getMessage());
        }
    }

    private void enterFolder(ContentFile folder) {
        subdirectory.set(subdirectory.get().resolve(folder.path().getFileName()));
        refresh();
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
        Set<Path> selection = selected().stream().map(ContentFile::path).collect(Collectors.toSet());
        cancelScan();
        displayedDirectory.set(null);
        directory.setText("");
        mods.clear();
        GameInstance instance = instances.getValue();
        if (getScene() == null || instance == null) {
            summary.setText(instance == null ? "尚无游戏实例" : "");
            return;
        }
        LauncherSettings settings = vm.settingsProperty().get();
        Path relative = subdirectory.get();
        scanning.set(true);
        summary.setText("正在读取" + contentLabel + "…");
        long ticket = generation;
        class ScanTask extends Task<List<ContentFile>> {
            private Path path;

            @Override
            protected List<ContentFile> call() throws Exception {
                path = model.directory(settings, instance).resolve(relative);
                return model.scan(path, new TaskContext() {
                    public void report(String message, double progress) {
                    }

                    public boolean cancelled() {
                        return isCancelled();
                    }
                });
            }
        }
        var task = new ScanTask();
        task.setOnSucceeded(event -> {
            if (ticket != generation || getScene() == null) {
                return;
            }
            showDirectory(task.path, settings);
            publish(task.getValue(), 0, ticket, selection);
        });
        task.setOnFailed(event -> {
            if (ticket == generation && getScene() != null) {
                showDirectory(task.path, settings);
                scanning.set(false);
                scan = null;
                summary.setText("无法读取" + contentLabel + "：" + task.getException().getMessage());
                Logger.getLogger("com.fuzzlauncher.launcher").warning(summary.getText());
            }
        });
        scan = task;
        Thread.ofVirtual().name("content-scan").start(task);
    }

    private void showDirectory(Path path, LauncherSettings settings) {
        if (path != null) {
            Path gameRoot = Path.of(settings.gameRoot()).toAbsolutePath().normalize();
            displayedDirectory.set(path);
            directory.setText(path + (gameRoot.equals(path.getParent()) ? "\n共享目录：修改会影响使用此目录的实例" : ""));
        }
    }

    private void publish(List<ContentFile> values, int offset, long ticket, Set<Path> selection) {
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
            for (ContentFile mod : list.getItems()) {
                if (selection.contains(mod.path())) {
                    list.getSelectionModel().select(mod);
                }
            }
            if (list.getSelectionModel().isEmpty() && !list.getItems().isEmpty()) {
                list.getSelectionModel().selectFirst();
            }
            long enabled = values.stream().filter(ContentFile::enabled).count();
            summary.setText(schematics ? (values.size() - values.stream().filter(ContentFile::directory).count()) + " 个原理图"
                : values.size() + (kind == ContentKind.MODS ? " 个模组文件" : " 个资源包")
                    + " · 启用 " + enabled + " · 禁用 " + (values.size() - enabled));
        }
    }

    private List<ContentFile> selected() {
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
        Set<String> existing = mods.stream().map(mod -> ModFileNames.logicalName(mod.path().getFileName().toString()))
            .collect(Collectors.toSet());
        Set<String> incoming = new HashSet<>();
        boolean collision = false;
        for (Path path : sources) {
            if (importable(path)) {
                String name = ModFileNames.logicalName(path.getFileName().toString());
                collision |= existing.contains(name) || !incoming.add(name);
            }
        }
        boolean rename = false;
        if (collision) {
            ChoiceDialog<String> choice = new ChoiceDialog<>("跳过同名文件", "跳过同名文件", "保留两份");
            RetroDialogs.decorate(choice, stage, "同名" + contentLabel);
            choice.setContentText("已存在同名启用或禁用文件，处理方式：");
            ((Button) choice.getDialogPane().lookupButton(ButtonType.OK)).setText("确定");
            ((Button) choice.getDialogPane().lookupButton(ButtonType.CANCEL)).setText("取消");
            Optional<String> result = choice.showAndWait();
            if (result.isEmpty()) {
                return;
            }
            rename = result.get().equals("保留两份");
        }
        model.importFiles(displayedDirectory.get(), sources, rename, changed(instance));
    }

    private void setEnabled(boolean enabled) {
        GameInstance instance = instances.getValue();
        model.setEnabled(displayedDirectory.get(), selected(), enabled, changed(instance));
    }

    private void deleteSelected() {
        deleteEntries(selected());
    }

    private void deleteEntries(List<ContentFile> files) {
        GameInstance instance = instances.getValue();
        if (!files.isEmpty() && confirm("删除" + contentLabel, "将选中的 " + files.size() + " 个条目移入回收站？")) {
            model.delete(displayedDirectory.get(), files, changed(instance));
        }
    }

    private void createFolder() {
        TextInputDialog dialog = new TextInputDialog();
        RetroDialogs.decorate(dialog, stage, "新建文件夹");
        dialog.setContentText("文件夹名称：");
        ((Button) dialog.getDialogPane().lookupButton(ButtonType.OK)).setText("确定");
        ((Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL)).setText("取消");
        dialog.showAndWait().map(String::strip).filter(name -> !name.isEmpty())
            .ifPresent(name -> model.createFolder(displayedDirectory.get(), name, changed(instances.getValue())));
    }

    private void details(ContentFile mod) {
        if (mod == null) {
            return;
        }
        Dialog<Void> dialog = new Dialog<>();
        RetroDialogs.decorate(dialog, stage, contentLabel + "详情");
        dialog.getDialogPane().setId(id("mod-details-dialog"));
        Label name = label(mod.name(), "section-title");
        name.setWrapText(true);
        byte[] bytes = mod.icon();
        ImageView artwork = new ImageView(bytes == null ? defaultIcon(mod) : new Image(new ByteArrayInputStream(bytes)));
        artwork.setFitWidth(48);
        artwork.setFitHeight(48);
        artwork.setPreserveRatio(true);
        artwork.setSmooth(false);
        TextArea description = new TextArea();
        description.setId(id("mod-details"));
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
            if (kind == ContentKind.RESOURCE_PACKS) {
                parts.add(String.join("\n", entry.name(), entry.version(), entry.description()).strip());
                continue;
            }
            parts.add(entry.name() + "\nID：" + entry.id() + "\n版本：" + entry.version() + "\n作者：" + entry.authors() + "\n加载器："
                + String.join(" / ", new TreeSet<>(entry.loaders())) + "\n\n" + entry.description());
        }
        GameInstance instance = instances.getValue();
        if (kind == ContentKind.MODS && instance != null) {
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
        if (kind == ContentKind.RESOURCE_PACKS) {
            parts.add("启用后可在游戏的资源包菜单中选择。");
        }
        parts.add("文件：" + mod.path().getFileName());
        description.setText(String.join("\n\n", parts));
        description.positionCaret(0);
        dialog.showAndWait();
    }
}
