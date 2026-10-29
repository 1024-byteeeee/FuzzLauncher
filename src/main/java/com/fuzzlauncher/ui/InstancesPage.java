package com.fuzzlauncher.ui;

import com.fuzzlauncher.domain.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import javafx.beans.binding.Bindings;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import javafx.scene.text.TextAlignment;
import javafx.stage.Stage;

/** InstancesPage presentation; all application operations are delegated to the ViewModel. */
public final class InstancesPage extends RetroPage {
  public InstancesPage(Stage stage, LauncherViewModel vm, Consumer<Integer> navigate) {
    super(stage, vm, navigate);
    setCenter(build());
  }

  private Node build() {
    ListView<GameInstance> list = new ListView<>(vm.instances());
    Label empty = new Label("尚无实例\n点击“新建”下载游戏");
    empty.setAlignment(Pos.CENTER);
    empty.setTextAlignment(TextAlignment.CENTER);
    list.setPlaceholder(empty);
    list.setCellFactory(
        ignored ->
            new ListCell<>() {
              @Override
              protected void updateItem(GameInstance item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name());
                setGraphic(empty || item == null ? null : icon("grass", 32));
              }
            });
    if (vm.selectedProperty().get() != null)
      list.getSelectionModel().select(vm.selectedProperty().get());
    list.getSelectionModel()
        .selectedItemProperty()
        .addListener(
            (o, a, b) -> {
              if (b != null) vm.selectInstance(b);
            });
    Label heading = label("游戏实例", "section-title");
    Button add = button("新建", () -> showPage(1));
    Button refresh = button("刷新", vm::refreshInstances);
    refresh.setId("refresh-instances");
    refresh.disableProperty().bind(vm.busyProperty().or(vm.gameRunningProperty()));
    Button remove =
        button(
            "删除",
            () -> {
              GameInstance item = vm.selectedProperty().get();
              if (item != null) {
                Path root =
                    Path.of(vm.settingsProperty().get().gameRoot()).toAbsolutePath().normalize();
                boolean shared =
                    new IsolationPolicy()
                        .gameDirectory(root, item, vm.settingsProperty().get().isolation())
                        .equals(root);
                String scope = shared ? "此实例使用共享游戏目录，共享存档和模组会保留。" : "包括其中的存档、模组和配置。";
                if (confirm(
                    "永久删除实例",
                    "将永久删除“"
                        + item.name()
                        + "”的实例目录：\n"
                        + com.fuzzlauncher.infrastructure.InstanceDirectories.directory(root, item)
                        + "\n"
                        + scope
                        + "\n删除后无法恢复，请先备份重要数据。\n共享基础文件和依赖库会保留。是否继续？")) vm.unregister(item);
              }
            });
    remove.setId("delete-instance");
    remove
        .disableProperty()
        .bind(vm.busyProperty().or(vm.gameRunningProperty()).or(vm.selectedProperty().isNull()));
    add.disableProperty().bind(vm.busyProperty());
    VBox toolbar = new VBox(8, heading, new HBox(6, add, remove, refresh));
    toolbar.setAlignment(Pos.CENTER_LEFT);
    VBox left = new VBox(12, toolbar, list);
    left.setPrefWidth(330);
    left.setMinWidth(300);
    VBox.setVgrow(list, Priority.ALWAYS);
    left.getStyleClass().add("instances");
    BorderPane details = new BorderPane();
    details.setMinWidth(0);
    HBox.setHgrow(details, Priority.ALWAYS);
    Consumer<GameInstance> render = item -> details.setCenter(instanceDetails(item));
    render.accept(vm.selectedProperty().get());
    // A page-scoped binding avoids retaining obsolete detail views when navigating.
    javafx.beans.value.ChangeListener<GameInstance> listener = (o, a, b) -> render.accept(b);
    vm.selectedProperty().addListener(listener);
    details
        .sceneProperty()
        .addListener(
            (o, old, scene) -> {
              if (scene == null && old != null) vm.selectedProperty().removeListener(listener);
            });
    HBox content = new HBox(14, left, details);
    content.getStyleClass().add("content");
    return content;
  }

  private Node instanceDetails(GameInstance instance) {
    if (instance == null) {
      VBox empty =
          new VBox(
              20,
              label("实例详情", "section-title"),
              new Label("安装一个游戏版本后，在这里配置并启动。"),
              button("下载游戏", () -> showPage(1)));
      empty.setPadding(new Insets(20));
      return empty;
    }
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
    landscape
        .viewportProperty()
        .bind(
            Bindings.createObjectBinding(
                () -> {
                  double width = Math.max(1, landscape.getFitWidth());
                  double height = Math.max(1, landscape.getFitHeight());
                  double sourceWidth = landscape.getImage().getWidth();
                  double sourceHeight = landscape.getImage().getHeight();
                  double scale = Math.max(width / sourceWidth, height / sourceHeight);
                  double cropWidth = width / scale;
                  double cropHeight = height / scale;
                  return new Rectangle2D(
                      (sourceWidth - cropWidth) / 2,
                      (sourceHeight - cropHeight) / 2,
                      cropWidth,
                      cropHeight);
                },
                landscape.fitWidthProperty(),
                landscape.fitHeightProperty()));
    TextField name = new TextField(instance.name());
    TextField version = new TextField(instance.gameVersion());
    version.setEditable(false);
    TextField loader =
        new TextField(
            instance.loader()
                + (instance.loaderVersion() == null || instance.loader() == LoaderType.VANILLA
                    ? ""
                    : " " + instance.loaderVersion()));
    loader.setEditable(false);
    loader.setId("instance-loader");
    loader.setMinWidth(0);
    HBox.setHgrow(loader, Priority.ALWAYS);
    Button changeLoader = button("修改", () -> new LoaderVersionDialog(stage, vm, instance).show());
    changeLoader.setId("change-instance-loader");
    changeLoader.setDisable(instance.loader() == LoaderType.VANILLA);
    changeLoader.setMinWidth(Region.USE_PREF_SIZE);
    HBox loaderControl = new HBox(6, loader, changeLoader);
    loaderControl.setAlignment(Pos.CENTER_LEFT);
    loaderControl.setMinWidth(0);
    ComboBox<IsolationOverride> isolation = new RetroComboBox<>();
    isolation.getItems().setAll(IsolationOverride.values());
    isolation.setValue(instance.isolation());
    isolation.setMaxWidth(Double.MAX_VALUE);
    Spinner<Integer> minimumMemory = memorySpinner(128, instance.minMemoryMb(), 128);
    minimumMemory.setId("instance-min-memory");
    Spinner<Integer> memory = memorySpinner(512, instance.memoryMb(), 512);
    memory.setId("instance-memory");
    TextField javaPath = new TextField(instance.javaPath());
    javaPath.setId("instance-java-path");
    javaPath.setPromptText("留空自动选择，或输入 java.exe 路径");
    javaPath.setMinWidth(0);
    HBox.setHgrow(javaPath, Priority.ALWAYS);
    Button browseJava =
        button(
            "浏览",
            () -> {
              Path initial;
              try {
                initial =
                    javaPath.getText().isBlank()
                        ? Path.of(System.getProperty("java.home"))
                        : Path.of(javaPath.getText());
              } catch (InvalidPathException ex) {
                initial = Path.of(System.getProperty("java.home"));
              }
              RetroPathDialog.javaExecutable(stage, initial)
                  .ifPresent(chosen -> javaPath.setText(chosen.toString()));
            });
    browseJava.setMinWidth(Region.USE_PREF_SIZE);
    HBox javaControl = new HBox(6, javaPath, browseJava);
    javaControl.setMinWidth(0);
    GridPane form =
        form(
            new String[] {"名称:", "游戏版本:", "加载器:", "版本隔离:", "最小内存 MB:", "最大内存 MB:", "Java 路径:"},
            new Node[] {name, version, loaderControl, isolation, minimumMemory, memory, javaControl});
    form.setId("instance-settings-form");
    form.disableProperty().bind(vm.busyProperty().or(vm.gameRunningProperty()));
    Label directory = new Label();
    directory.setWrapText(true);
    directory.setMinHeight(40);
    directory.setMaxWidth(Double.MAX_VALUE);
    directory.setStyle("-fx-font-size: 13px;");
    directory.setText(
        "游戏目录: "
            + new IsolationPolicy()
                .gameDirectory(
                    Path.of(vm.settingsProperty().get().gameRoot()),
                    instance,
                    vm.settingsProperty().get().isolation()));
    Button save =
        button(
            "保存设置",
            () -> {
              if (isolation.getValue() != instance.isolation()
                  && !confirm("切换版本隔离", "切换后将使用另一游戏目录；现有存档和模组不会自动迁移。\n请先备份重要数据。是否继续？")) return;
              try {
                vm.updateInstance(
                    instance.withSettings(
                        name.getText(),
                        isolation.getValue(),
                        Integer.parseInt(minimumMemory.getEditor().getText()),
                        Integer.parseInt(memory.getEditor().getText()),
                        javaPath.getText()));
              } catch (IllegalArgumentException ex) {
                message(Alert.AlertType.ERROR, "设置无效", ex.getMessage());
              }
            });
    save.setId("save-instance-settings");
    save.disableProperty().bind(vm.busyProperty().or(vm.gameRunningProperty()));
    Button folder =
        button(
            "打开游戏目录",
            () -> {
              try {
                Path root = Path.of(vm.settingsProperty().get().gameRoot());
                com.fuzzlauncher.infrastructure.InstanceDirectories.migrate(root, instance);
                com.fuzzlauncher.infrastructure.InstanceDirectories.checkAvailable(
                    root, instance, vm.instances());
                openFolder(
                    new IsolationPolicy()
                        .gameDirectory(root, instance, vm.settingsProperty().get().isolation()));
              } catch (java.io.IOException ex) {
                message(Alert.AlertType.ERROR, "无法打开实例目录", ex.getMessage());
              }
            });
    Button arguments =
        button(
            "启动参数",
            () -> {
              GameInstance item = vm.selectedProperty().get();
              if (item != null)
                new LaunchArgumentsDialog(stage, vm, item).showAndWait();
            });
    arguments.setId("launch-arguments");
    arguments.disableProperty().bind(vm.busyProperty().or(vm.gameRunningProperty()));
    HBox actions = new HBox(8, save, arguments, folder);
    VBox panel = new VBox(12, label("实例详情", "section-title"), preview, form, directory, actions);
    panel.setMinWidth(0);
    panel.getStyleClass().add("details-box");
    VBox.setVgrow(panel, Priority.ALWAYS);
    Button start = button("启动游戏", vm::launch);
    start.setGraphic(icon("grass", 43));
    start.setGraphicTextGap(18);
    start.getStyleClass().add("launch-button");
    start.setMaxWidth(Double.MAX_VALUE);
    start
        .disableProperty()
        .bind(vm.busyProperty().or(vm.gameRunningProperty()).or(vm.accountProperty().isNull()));
    Label loginHint = new Label();
    loginHint
        .textProperty()
        .bind(
            Bindings.createStringBinding(
                () ->
                    vm.accountProperty().get() == null
                        ? "请先在“账号”页选择微软登录或离线登录"
                        : (vm.accountProperty().get().isOffline() ? "离线账号: " : "微软账号: ")
                            + vm.accountProperty().get().name(),
                vm.accountProperty()));
    loginHint.setStyle("-fx-font-size: 14px;");
    VBox right = new VBox(12, panel, start, loginHint);
    return right;
  }

  private Spinner<Integer> memorySpinner(int minimum, int initial, int step) {
    Spinner<Integer> spinner = new Spinner<>(minimum, 65536, initial, step);
    spinner.setEditable(true);
    spinner
        .getEditor()
        .setTextFormatter(
            new TextFormatter<String>(
                change -> change.getControlNewText().matches("[0-9]{0,5}") ? change : null));
    spinner.setMaxWidth(Double.MAX_VALUE);
    return spinner;
  }
}
