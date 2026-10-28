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
    Label heading = label("游戏实例(I)", "section-title");
    Button add = button("新建", () -> showPage(1));
    Button remove =
        button(
            "移除",
            () -> {
              GameInstance item = vm.selectedProperty().get();
              if (item != null && confirm("移除实例", "从列表移除“" + item.name() + "”？\n游戏文件、模组和存档将保留。"))
                vm.unregister(item);
            });
    remove
        .disableProperty()
        .bind(vm.busyProperty().or(vm.gameRunningProperty()).or(vm.selectedProperty().isNull()));
    add.disableProperty().bind(vm.busyProperty());
    HBox toolbar = new HBox(6, heading, new Spacer(), add, remove);
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
    landscape.setSmooth(false);
    landscape.setPreserveRatio(false);
    landscape.setManaged(false);
    landscape.setLayoutX(3);
    landscape.setLayoutY(3);
    StackPane preview = new StackPane(landscape);
    preview.getStyleClass().add("preview");
    preview.setMinSize(0, 140);
    preview.setPrefHeight(210);
    VBox.setVgrow(preview, Priority.ALWAYS);
    landscape.fitWidthProperty().bind(preview.widthProperty().subtract(6));
    landscape.fitHeightProperty().bind(preview.heightProperty().subtract(6));
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
    ComboBox<IsolationOverride> isolation = new RetroComboBox<>();
    isolation.getItems().setAll(IsolationOverride.values());
    isolation.setValue(instance.isolation());
    isolation.setMaxWidth(Double.MAX_VALUE);
    Spinner<Integer> memory = new Spinner<>(512, 65536, instance.memoryMb(), 512);
    memory.setId("instance-memory");
    memory.setEditable(true);
    memory
        .getEditor()
        .setTextFormatter(
            new TextFormatter<String>(
                change -> change.getControlNewText().matches("[0-9]{0,5}") ? change : null));
    memory.setMaxWidth(Double.MAX_VALUE);
    TextField javaPath = new TextField(instance.javaPath());
    javaPath.setId("instance-java-path");
    javaPath.setPromptText("留空自动选择，或输入 java.exe 路径");
    javaPath.setMinWidth(0);
    HBox.setHgrow(javaPath, Priority.ALWAYS);
    Button browseJava =
        button(
            "浏览",
            () -> {
              javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
              chooser.setTitle("选择此实例的 Java");
              chooser
                  .getExtensionFilters()
                  .add(
                      new javafx.stage.FileChooser.ExtensionFilter(
                          "Java 程序", "java.exe", "javaw.exe"));
              java.io.File chosen = chooser.showOpenDialog(stage);
              if (chosen != null) javaPath.setText(chosen.getAbsolutePath());
            });
    browseJava.setMinWidth(Region.USE_PREF_SIZE);
    HBox javaControl = new HBox(6, javaPath, browseJava);
    javaControl.setMinWidth(0);
    GridPane form =
        form(
            new String[] {"名称(N):", "游戏版本:", "加载器:", "版本隔离:", "内存 MB:", "Java 路径:"},
            new Node[] {name, version, loader, isolation, memory, javaControl});
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
    HBox actions = new HBox(8, save, folder);
    VBox panel = new VBox(12, label("实例详情", "section-title"), preview, form, directory, actions);
    panel.setMinWidth(0);
    panel.getStyleClass().add("details-box");
    VBox.setVgrow(panel, Priority.ALWAYS);
    Button start = button("启动游戏(L)", vm::launch);
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
}
