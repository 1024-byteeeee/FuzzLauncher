package com.fuzzlauncher.ui;

import com.fuzzlauncher.domain.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

/** DownloadsPage presentation; all application operations are delegated to the ViewModel. */
public final class DownloadsPage extends RetroPage {
  public DownloadsPage(Stage stage, LauncherViewModel vm, Consumer<Integer> navigate) {
    super(stage, vm, navigate);
    setCenter(build());
  }

  private Node build() {
    ComboBox<String> type = new ComboBox<>();
    type.getItems().addAll("正式版", "快照", "历史版本", "全部");
    type.setValue("正式版");
    TextField search = new TextField();
    search.setPromptText("搜索版本，例如 1.20.1");
    FilteredList<GameVersion> filtered = new FilteredList<>(vm.versions());
    Runnable filter =
        () ->
            filtered.setPredicate(
                v -> {
                  boolean matches =
                      switch (type.getValue()) {
                        case "正式版" -> v.type().equals("release");
                        case "快照" -> v.type().equals("snapshot");
                        case "历史版本" -> v.type().startsWith("old_");
                        default -> true;
                      };
                  return matches
                      && v.id()
                          .toLowerCase(Locale.ROOT)
                          .contains(search.getText().strip().toLowerCase(Locale.ROOT));
                });
    type.setOnAction(e -> filter.run());
    search.textProperty().addListener((o, a, b) -> filter.run());
    filter.run();
    ListView<GameVersion> games = new ListView<>(filtered);
    games.setPlaceholder(new Label("点击“刷新版本”读取官方清单"));
    ComboBox<LoaderType> loader = new ComboBox<>();
    loader.getItems().setAll(LoaderType.values());
    loader.setValue(LoaderType.VANILLA);
    loader.setMaxWidth(Double.MAX_VALUE);
    ComboBox<String> loaderVersion = new ComboBox<>(vm.loaderVersions());
    loaderVersion.setMaxWidth(Double.MAX_VALUE);
    loaderVersion.setPromptText("选择兼容的加载器版本");
    TextField name = new TextField();
    Runnable query =
        () -> {
          GameVersion v = games.getSelectionModel().getSelectedItem();
          loaderVersion.getSelectionModel().clearSelection();
          if (v != null) {
            name.setText(
                v.id() + (loader.getValue() == LoaderType.VANILLA ? "" : " " + loader.getValue()));
            vm.loadLoaders(loader.getValue(), v);
          }
        };
    games.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> query.run());
    loader.setOnAction(e -> query.run());
    loaderVersion
        .disableProperty()
        .bind(vm.busyProperty().or(loader.valueProperty().isEqualTo(LoaderType.VANILLA)));
    Button refresh = button("刷新版本", vm::loadVersions);
    refresh.getStyleClass().add("version-refresh");
    refresh.setMinWidth(Region.USE_PREF_SIZE);
    refresh.minHeightProperty().bind(type.heightProperty());
    refresh.prefHeightProperty().bind(type.heightProperty());
    refresh.maxHeightProperty().bind(type.heightProperty());
    refresh.disableProperty().bind(vm.busyProperty());
    Button refreshLoader =
        button(
            "查询加载器",
            () -> vm.loadLoaders(loader.getValue(), games.getSelectionModel().getSelectedItem()));
    refreshLoader.disableProperty().bind(vm.busyProperty());
    Button install =
        button(
            "下载并安装",
            () ->
                vm.install(
                    games.getSelectionModel().getSelectedItem(),
                    loader.getValue(),
                    loaderVersion.getValue(),
                    name.getText()));
    install
        .disableProperty()
        .bind(
            vm.busyProperty()
                .or(games.getSelectionModel().selectedItemProperty().isNull())
                .or(name.textProperty().isEmpty()));
    games.disableProperty().bind(vm.busyProperty());
    loader.disableProperty().bind(vm.busyProperty());
    type.disableProperty().bind(vm.busyProperty());
    search.disableProperty().bind(vm.busyProperty());
    HBox filters = new HBox(8, type, refresh);
    filters.setAlignment(Pos.CENTER_LEFT);
    filters.setFillHeight(false);
    VBox list = new VBox(10, filters, search, games);
    list.setPrefWidth(360);
    VBox.setVgrow(games, Priority.ALWAYS);
    Label note = new Label("资源和依赖来自官方服务。\n安装完成后实例会出现在“游戏实例”页。\n游戏 Java 会在需要时自动检测或下载。");
    note.setWrapText(true);
    GridPane options =
        form(new String[] {"实例名称:", "加载器:", "加载器版本:"}, new Node[] {name, loader, loaderVersion});
    VBox config =
        new VBox(22, label("安装选项", "section-title"), options, refreshLoader, note, install);
    config.setMinWidth(0);
    HBox.setHgrow(config, Priority.ALWAYS);
    config.getStyleClass().add("details-box");
    HBox content = new HBox(20, list, config);
    content.getStyleClass().add("content");
    return content;
  }
}
