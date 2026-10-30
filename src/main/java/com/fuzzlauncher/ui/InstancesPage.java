package com.fuzzlauncher.ui;

import com.fuzzlauncher.domain.*;

import javafx.beans.binding.Bindings;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import javafx.scene.text.TextAlignment;
import javafx.stage.Stage;

import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** InstancesPage presentation; all application operations are delegated to the ViewModel. */
public final class InstancesPage extends RetroPage {
    public InstancesPage(Stage stage, LauncherViewModel vm, Consumer<Integer> navigate) {
        super(stage, vm, navigate);
        setCenter(build());
        setOnDragOver(event -> {
            if (!vm.busyProperty().get() && !vm.gameRunningProperty().get() && event.getDragboard().hasFiles()
                && event.getDragboard().getFiles().size() == 1 && isModpack(event.getDragboard().getFiles().getFirst().toPath())) {
                event.acceptTransferModes(javafx.scene.input.TransferMode.COPY);
            }
            event.consume();
        });
        setOnDragDropped(event -> {
            boolean accepted = !vm.busyProperty().get() && !vm.gameRunningProperty().get() && event.getDragboard().hasFiles()
                && event.getDragboard().getFiles().size() == 1 && isModpack(event.getDragboard().getFiles().getFirst().toPath());
            if (accepted) {
                Path archive = event.getDragboard().getFiles().getFirst().toPath();
                javafx.application.Platform.runLater(() -> ModpackDialogs.importArchive(stage, vm, archive));
            }
            event.setDropCompleted(accepted);
            event.consume();
        });
    }

    private static boolean isModpack(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().toLowerCase(Locale.ROOT).matches(".*\\.(zip|mrpack)");
    }

    private Node build() {
        ListView<GameInstance> list = new ListView<>(vm.instances());
        Label empty = new Label("尚无实例\n点击“新建”下载游戏");
        empty.setAlignment(Pos.CENTER);
        empty.setTextAlignment(TextAlignment.CENTER);
        list.setPlaceholder(empty);
        list.setCellFactory(ignored -> new ListCell<>() {
            @Override
            protected void updateItem(GameInstance item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name());
                if (empty || item == null) {
                    setGraphic(null);
                    return;
                }
                ImageView image = icon(switch (item.loader()) {
                    case VANILLA -> "grass";
                    case FABRIC -> "fabric-retro";
                    case FORGE -> "forge-retro";
                    case NEOFORGE -> "neoforge-retro";
                }, 32);
                StackPane iconSlot = new StackPane(image);
                iconSlot.setMinSize(32, 32);
                iconSlot.setPrefSize(32, 32);
                iconSlot.setMaxSize(32, 32);
                iconSlot.setAlignment(Pos.CENTER);
                setAlignment(Pos.CENTER_LEFT);
                setGraphic(iconSlot);
            }
        });
        if (vm.selectedProperty().get() != null) {
            list.getSelectionModel().select(vm.selectedProperty().get());
        }
        list.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b != null) {
                vm.selectInstance(b);
            }
        });
        Label heading = label("游戏实例", "section-title");
        Button add = button("新建", () -> showPage(1));
        Button refresh = button("刷新", vm::refreshInstances);
        refresh.setId("refresh-instances");
        refresh.disableProperty().bind(vm.busyProperty().or(vm.gameRunningProperty()));
        Button remove = button("删除", () -> {
            GameInstance item = vm.selectedProperty().get();
            if (item != null) {
                Path root = Path.of(vm.settingsProperty().get().gameRoot()).toAbsolutePath().normalize();
                boolean shared = new IsolationPolicy().gameDirectory(root, item, vm.settingsProperty().get().isolation()).equals(root);
                String scope = shared ? "此实例使用共享游戏目录，共享存档和模组会保留。" : "包括其中的存档、模组和配置。";
                if (confirm("永久删除实例",
                    "将永久删除“" + item.name() + "”的实例目录：\n" + com.fuzzlauncher.infrastructure.InstanceDirectories.directory(root, item) + "\n"
                        + scope + "\n删除后无法恢复，请先备份重要数据。\n共享基础文件和依赖库会保留。是否继续？")) {
                    vm.unregister(item);
                }
            }
        });
        remove.setId("delete-instance");
        remove.disableProperty().bind(vm.busyProperty().or(vm.gameRunningProperty()).or(vm.selectedProperty().isNull()));
        add.disableProperty().bind(vm.busyProperty());
        Button importPack = button("导入", () -> ModpackDialogs.importPack(stage, vm));
        importPack.setId("import-modpack");
        importPack.disableProperty().bind(vm.busyProperty().or(vm.gameRunningProperty()));
        Button exportPack = button("导出", () -> {
            GameInstance instance = vm.selectedProperty().get();
            if (instance != null) {
                ModpackDialogs.exportPack(stage, vm, instance);
            }
        });
        exportPack.setId("export-modpack");
        exportPack.disableProperty().bind(vm.busyProperty().or(vm.gameRunningProperty()).or(vm.selectedProperty().isNull()));
        for (Button action : java.util.List.of(add, remove, refresh, importPack, exportPack)) {
            action.setStyle("-fx-padding: 5 3;");
            action.setMinWidth(54);
            action.setPrefWidth(54);
            action.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(action, Priority.ALWAYS);
        }
        VBox toolbar = new VBox(8, heading, new HBox(3, add, remove, refresh, importPack, exportPack));
        toolbar.setAlignment(Pos.CENTER_LEFT);
        VBox left = new VBox(12, toolbar, list);
        left.setPrefWidth(330);
        left.setMinWidth(300);
        VBox.setVgrow(list, Priority.ALWAYS);
        left.getStyleClass().add("instances");
        BorderPane details = new BorderPane();
        details.setMinWidth(0);
        HBox.setHgrow(details, Priority.ALWAYS);
        InstanceDetails editor = new InstanceDetails();
        VBox emptyDetails = new VBox(20, label("实例详情", "section-title"), new Label("安装一个游戏版本后，在这里配置并启动。"),
            button("下载游戏", () -> showPage(1)));
        emptyDetails.setPadding(new Insets(20));
        Consumer<GameInstance> render = item -> {
            if (item != null) {
                editor.update.accept(item);
            }
            details.setCenter(item == null ? emptyDetails : editor);
        };
        render.accept(vm.selectedProperty().get());
        // A page-scoped binding avoids retaining obsolete detail views when navigating.
        javafx.beans.value.ChangeListener<GameInstance> listener = (o, a, b) -> render.accept(b);
        vm.selectedProperty().addListener(listener);
        details.sceneProperty().addListener((o, old, scene) -> {
            if (scene == null && old != null) {
                vm.selectedProperty().removeListener(listener);
            }
        });
        HBox content = new HBox(14, left, details);
        content.getStyleClass().add("content");
        return content;
    }

    private final class InstanceDetails extends VBox {
        private GameInstance instance;
        private Consumer<GameInstance> update;
        private javafx.concurrent.Task<Boolean> packPreview;

        private InstanceDetails() {
            ImageView landscape = new ImageView(image("landscape"));
            landscape.setId("instance-preview-image");
            landscape.setSmooth(false);
            landscape.setPreserveRatio(true);
            landscape.setManaged(false);
            landscape.setLayoutX(3);
            landscape.setLayoutY(3);
            StackPane preview = new StackPane(landscape);
            preview.getStyleClass().add("preview");
            preview.setMinSize(0, 30);
            preview.setPrefHeight(210);
            VBox.setVgrow(preview, Priority.ALWAYS);
            landscape.fitWidthProperty().bind(preview.widthProperty().subtract(6));
            landscape.fitHeightProperty().bind(preview.heightProperty().subtract(6));
            landscape.viewportProperty().bind(Bindings.createObjectBinding(() -> {
                double width = Math.max(1, landscape.getFitWidth());
                double height = Math.max(1, landscape.getFitHeight());
                double sourceWidth = landscape.getImage().getWidth();
                double sourceHeight = landscape.getImage().getHeight();
                double scale = Math.max(width / sourceWidth, height / sourceHeight);
                double cropWidth = width / scale;
                double cropHeight = height / scale;
                return new Rectangle2D((sourceWidth - cropWidth) / 2, (sourceHeight - cropHeight) / 2, cropWidth, cropHeight);
            }, landscape.fitWidthProperty(), landscape.fitHeightProperty()));
            TextField name = new TextField();
            TextField version = new TextField();
            version.setEditable(false);
            TextField loader = new TextField();
            loader.setEditable(false);
            loader.setId("instance-loader");
            loader.setMinWidth(0);
            HBox.setHgrow(loader, Priority.ALWAYS);
            Button changeLoader = button("修改", () -> new LoaderVersionDialog(stage, vm, instance).show());
            changeLoader.setId("change-instance-loader");
            changeLoader.setMinWidth(Region.USE_PREF_SIZE);
            HBox loaderControl = new HBox(6, loader, changeLoader);
            loaderControl.setAlignment(Pos.CENTER_LEFT);
            loaderControl.setMinWidth(0);
            ComboBox<IsolationOverride> isolation = new RetroComboBox<>();
            isolation.getItems().setAll(IsolationOverride.values());
            isolation.setMaxWidth(Double.MAX_VALUE);
            Spinner<Integer> minimumMemory = memorySpinner(128, 4096, 128);
            minimumMemory.setId("instance-min-memory");
            Spinner<Integer> memory = memorySpinner(512, 4096, 512);
            memory.setId("instance-memory");
            TextField javaPath = new TextField();
            javaPath.setId("instance-java-path");
            javaPath.setPromptText("留空自动选择，或输入 java.exe 路径");
            javaPath.setMinWidth(0);
            HBox.setHgrow(javaPath, Priority.ALWAYS);
            Button browseJava = button("浏览", () -> {
                Path initial;
                try {
                    initial = javaPath.getText().isBlank() ? Path.of(System.getProperty("java.home")) : Path.of(javaPath.getText());
                } catch (InvalidPathException ex) {
                    initial = Path.of(System.getProperty("java.home"));
                }
                RetroPathDialog.javaExecutable(stage, initial).ifPresent(chosen -> javaPath.setText(chosen.toString()));
            });
            browseJava.setMinWidth(Region.USE_PREF_SIZE);
            HBox javaControl = new HBox(6, javaPath, browseJava);
            javaControl.setMinWidth(0);
            GridPane form = form(new String[] { "名称:", "游戏版本:", "加载器:", "版本隔离:", "最小内存 MB:", "最大内存 MB:", "Java 路径:" },
                new Node[] { name, version, loaderControl, isolation, minimumMemory, memory, javaControl });
            form.setId("instance-settings-form");
            form.disableProperty().bind(vm.busyProperty().or(vm.gameRunningProperty()));
            Label directory = new Label();
            directory.setWrapText(true);
            directory.setMinHeight(40);
            directory.setMaxWidth(Double.MAX_VALUE);
            directory.setStyle("-fx-font-size: 13px;");
            Button save = button("保存设置", () -> {
                if (isolation.getValue() != instance.isolation() && !confirm("切换版本隔离", "切换后将使用另一游戏目录；现有存档和模组不会自动迁移。\n请先备份重要数据。是否继续？")) {
                    return;
                }
                try {
                    vm.updateInstance(
                        instance.withSettings(name.getText(), isolation.getValue(), Integer.parseInt(minimumMemory.getEditor().getText()),
                            Integer.parseInt(memory.getEditor().getText()), javaPath.getText()));
                } catch (IllegalArgumentException ex) {
                    message(Alert.AlertType.ERROR, "设置无效", ex.getMessage());
                }
            });
            save.setId("save-instance-settings");
            save.disableProperty().bind(vm.busyProperty().or(vm.gameRunningProperty()));
            Button folder = button("打开游戏目录", () -> {
                try {
                    Path root = Path.of(vm.settingsProperty().get().gameRoot());
                    com.fuzzlauncher.infrastructure.InstanceDirectories.migrate(root, instance);
                    com.fuzzlauncher.infrastructure.InstanceDirectories.checkAvailable(root, instance, vm.instances());
                    openFolder(new IsolationPolicy().gameDirectory(root, instance, vm.settingsProperty().get().isolation()));
                } catch (java.io.IOException ex) {
                    message(Alert.AlertType.ERROR, "无法打开实例目录", ex.getMessage());
                }
            });
            Button arguments = button("启动参数", () -> {
                GameInstance item = vm.selectedProperty().get();
                if (item != null) {
                    new LaunchArgumentsDialog(stage, vm, item).showAndWait();
                }
            });
            arguments.setId("launch-arguments");
            arguments.disableProperty().bind(vm.busyProperty().or(vm.gameRunningProperty()));
            HBox actions = new HBox(8, save, arguments, folder);
            Button updatePack = button("检查整合包更新", () -> vm.updateModpack(instance));
            updatePack.setId("update-modpack");
            updatePack.disableProperty().bind(vm.busyProperty().or(vm.gameRunningProperty()));
            updatePack.setVisible(false);
            updatePack.setManaged(false);
            update = selected -> {
                instance = selected;
                name.setText(instance.name());
                version.setText(instance.gameVersion());
                loader.setText(instance.loader()
                    + (instance.loader() == LoaderType.VANILLA || instance.loaderVersion() == null ? "" : " " + instance.loaderVersion()));
                changeLoader.setDisable(instance.loader() == LoaderType.VANILLA);
                isolation.setValue(instance.isolation());
                minimumMemory.getValueFactory().setValue(instance.minMemoryMb());
                minimumMemory.getEditor().setText(Integer.toString(instance.minMemoryMb()));
                memory.getValueFactory().setValue(instance.memoryMb());
                memory.getEditor().setText(Integer.toString(instance.memoryMb()));
                javaPath.setText(instance.javaPath());
                directory.setText("游戏目录: " + new IsolationPolicy().gameDirectory(Path.of(vm.settingsProperty().get().gameRoot()), instance,
                    vm.settingsProperty().get().isolation()));
                if (packPreview != null) {
                    packPreview.cancel(true);
                }
                updatePack.setVisible(false);
                updatePack.setManaged(false);
                Path packState = com.fuzzlauncher.infrastructure.InstanceDirectories
                    .directory(Path.of(vm.settingsProperty().get().gameRoot()), instance)
                    .resolve(com.fuzzlauncher.infrastructure.ModpackArchive.STATE);
                javafx.concurrent.Task<Boolean> task = new javafx.concurrent.Task<>() {
                    @Override
                    protected Boolean call() throws Exception {
                        return Files.isRegularFile(packState) && "SERVER".equals(com.fuzzlauncher.infrastructure.JsonFiles
                            .string(com.fuzzlauncher.infrastructure.JsonFiles.read(packState), "format", ""));
                    }
                };
                task.setOnSucceeded(event -> {
                    if (instance == selected && !task.isCancelled()) {
                        updatePack.setVisible(task.getValue());
                        updatePack.setManaged(task.getValue());
                    }
                });
                // Broken pack state is reported by launch/update; a preview must not block selection.
                task.setOnFailed(event -> com.fuzzlauncher.infrastructure.LauncherLog.LOGGER.fine("无法预览整合包状态：" + task.getException()));
                packPreview = task;
                Thread.ofVirtual().name("instance-pack-preview").start(task);
            };
            sceneProperty().addListener((o, old, scene) -> {
                if (scene == null && packPreview != null) {
                    packPreview.cancel(true);
                }
            });
            HBox packActions = new HBox(8, updatePack);
            packActions.managedProperty().bind(updatePack.managedProperty());
            packActions.visibleProperty().bind(updatePack.visibleProperty());
            VBox panel = new VBox(12, label("实例详情", "section-title"), preview, form, directory, actions, packActions);
            panel.setMinWidth(0);
            panel.getStyleClass().add("details-box");
            VBox.setVgrow(panel, Priority.ALWAYS);
            Button start = button("启动游戏", vm::launch);
            start.setGraphic(icon("grass", 43));
            start.setGraphicTextGap(18);
            start.getStyleClass().add("launch-button");
            start.setMaxWidth(Double.MAX_VALUE);
            start.disableProperty().bind(vm.busyProperty().or(vm.gameRunningProperty()).or(vm.accountProperty().isNull()));
            Label loginHint = new Label();
            loginHint.textProperty()
                .bind(Bindings.createStringBinding(
                    () -> vm.accountProperty().get() == null ? "请先在“账号”页选择微软登录或离线登录"
                        : (vm.accountProperty().get().isOffline() ? "离线账号: " : "微软账号: ") + vm.accountProperty().get().name(),
                    vm.accountProperty()));
            loginHint.setStyle("-fx-font-size: 14px;");
            VBox right = new VBox(12, panel, start, loginHint);
            getChildren().setAll(right);
            VBox.setVgrow(right, Priority.ALWAYS);
        }
    }

    private Spinner<Integer> memorySpinner(int minimum, int initial, int step) {
        Spinner<Integer> spinner = new Spinner<>(minimum, 65536, initial, step);
        spinner.setEditable(true);
        spinner.getEditor()
            .setTextFormatter(new TextFormatter<String>(change -> change.getControlNewText().matches("[0-9]{0,5}") ? change : null));
        spinner.setMaxWidth(Double.MAX_VALUE);
        return spinner;
    }
}
