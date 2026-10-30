package com.fuzzlauncher.ui;

import com.fuzzlauncher.domain.*;

import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** DownloadsPage presentation; all application operations are delegated to the ViewModel. */
public final class DownloadsPage extends RetroPage {
    public DownloadsPage(Stage stage, LauncherViewModel vm, Consumer<Integer> navigate) {
        super(stage, vm, navigate);
        setCenter(build());
        boolean[] pendingInitialLoad = { true };
        Runnable loadWhenReady = () -> {
            if (getScene() != null && pendingInitialLoad[0] && !vm.busyProperty().get()) {
                pendingInitialLoad[0] = false;
                vm.loadVersions(this);
            }
        };
        ChangeListener<Boolean> busyListener = (observable, wasBusy, busy) -> javafx.application.Platform.runLater(loadWhenReady);
        sceneProperty().addListener((observable, oldScene, scene) -> {
            if (oldScene != null) {
                vm.busyProperty().removeListener(busyListener);
            }
            if (scene == null) {
                vm.cancelCatalogRequests(this);
            }
            if (scene != null) {
                vm.busyProperty().addListener(busyListener);
                loadWhenReady.run();
            }
        });
    }

    private Node build() {
        ComboBox<String> type = new RetroComboBox<>();
        type.getItems().addAll("正式版", "快照", "历史版本", "全部");
        type.setValue("正式版");
        TextField search = new TextField();
        search.setPromptText("搜索版本，例如 1.20.1");
        FilteredList<GameVersion> filtered = new FilteredList<>(vm.versions());
        Runnable filter = () -> filtered.setPredicate(v -> {
            boolean matches = switch (type.getValue()) {
                case "正式版" -> v.type().equals("release");
                case "快照" -> v.type().equals("snapshot");
                case "历史版本" -> v.type().startsWith("old_");
                default -> true;
            };
            return matches && v.id().toLowerCase(Locale.ROOT).contains(search.getText().strip().toLowerCase(Locale.ROOT));
        });
        type.setOnAction(e -> filter.run());
        search.textProperty().addListener((o, a, b) -> filter.run());
        filter.run();
        ListView<GameVersion> games = new ListView<>(filtered);
        games.setId("game-version-list");
        Label empty = new Label();
        empty.textProperty().bind(javafx.beans.binding.Bindings.when(vm.busyProperty()).then("正在加载版本列表…").otherwise("暂无匹配版本，可点击“刷新版本”重试"));
        games.setPlaceholder(empty);
        ComboBox<LoaderType> loader = new RetroComboBox<>();
        loader.setId("loader-type");
        loader.getItems().setAll(LoaderType.values());
        loader.setValue(LoaderType.VANILLA);
        loader.setMaxWidth(Double.MAX_VALUE);
        ComboBox<String> loaderVersion = new RetroComboBox<>(vm.loaderVersions());
        loaderVersion.setId("loader-version");
        loaderVersion.setMaxWidth(Double.MAX_VALUE);
        loaderVersion.setPromptText("选择兼容的加载器版本");
        TextField name = new TextField();
        boolean[] pendingQuery = { false };
        Runnable query = () -> {
            if (vm.busyProperty().get()) {
                pendingQuery[0] = true;
                return;
            }
            pendingQuery[0] = false;
            GameVersion v = games.getSelectionModel().getSelectedItem();
            loaderVersion.getSelectionModel().clearSelection();
            if (v != null) {
                name.setText(v.id() + (loader.getValue() == LoaderType.VANILLA ? "" : " " + loader.getValue()));
            }
            if (getScene() != null) {
                vm.loadLoaders(loader.getValue(), v, this);
            }
        };
        ChangeListener<Boolean> queryWhenReady = (observable, old, busy) -> {
            if (!busy && pendingQuery[0]) {
                javafx.application.Platform.runLater(() -> {
                    if (getScene() != null && pendingQuery[0] && !vm.busyProperty().get()) {
                        query.run();
                    }
                });
            }
        };
        sceneProperty().addListener((observable, old, scene) -> {
            if (old != null) {
                vm.busyProperty().removeListener(queryWhenReady);
            }
            if (scene != null) {
                vm.busyProperty().addListener(queryWhenReady);
            }
        });
        games.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> query.run());
        loader.valueProperty().addListener((o, a, b) -> query.run());
        filtered.addListener((ListChangeListener<GameVersion>) change -> {
            if (getScene() != null && games.getSelectionModel().getSelectedItem() == null && !filtered.isEmpty()) {
                games.getSelectionModel().selectFirst();
            }
        });
        if (!filtered.isEmpty()) {
            games.getSelectionModel().selectFirst();
        }
        loaderVersion.disableProperty().bind(vm.busyProperty().or(loader.valueProperty().isEqualTo(LoaderType.VANILLA)));
        Button refresh = button("刷新版本", () -> vm.loadVersions(this, true));
        refresh.getStyleClass().add("version-refresh");
        refresh.setMinWidth(Region.USE_PREF_SIZE);
        refresh.minHeightProperty().bind(type.heightProperty());
        refresh.prefHeightProperty().bind(type.heightProperty());
        refresh.maxHeightProperty().bind(type.heightProperty());
        refresh.disableProperty().bind(vm.busyProperty());
        Button refreshLoader = button("刷新加载器",
            () -> vm.loadLoaders(loader.getValue(), games.getSelectionModel().getSelectedItem(), this, true));
        refreshLoader.disableProperty().bind(vm.busyProperty().or(games.getSelectionModel().selectedItemProperty().isNull())
            .or(loader.valueProperty().isEqualTo(LoaderType.VANILLA)));
        Button install = button("下载并安装",
            () -> vm.install(games.getSelectionModel().getSelectedItem(), loader.getValue(), loaderVersion.getValue(), name.getText()));
        install.disableProperty()
            .bind(vm.busyProperty().or(games.getSelectionModel().selectedItemProperty().isNull()).or(name.textProperty().isEmpty()));
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
        Label sourceLabel = new Label();
        sourceLabel.textProperty().bind(javafx.beans.binding.Bindings
            .createStringBinding(() -> "下载源：" + vm.settingsProperty().get().downloadSource(), vm.settingsProperty()));
        Hyperlink sourceLink = new Hyperlink();
        sourceLink.textProperty().bind(javafx.beans.binding.Bindings
            .createStringBinding(() -> vm.settingsProperty().get().downloadSource().website(), vm.settingsProperty()));
        sourceLink.setOnAction(event -> {
            try {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(sourceLink.getText()));
            } catch (Exception ex) {
                message(Alert.AlertType.ERROR, "无法打开下载源网站", ex.getMessage());
            }
        });
        sourceLink.setWrapText(true);
        sourceLink.setMaxWidth(Double.MAX_VALUE);
        VBox sourceInfo = new VBox(3, sourceLabel, sourceLink);
        GridPane options = form(new String[] { "实例名称:", "加载器:", "加载器版本:" }, new Node[] { name, loader, loaderVersion });
        VBox config = new VBox(22, label("安装选项", "section-title"), options, refreshLoader, sourceInfo, install);
        config.setMinWidth(0);
        HBox.setHgrow(config, Priority.ALWAYS);
        config.getStyleClass().add("details-box");
        HBox content = new HBox(20, list, config);
        content.getStyleClass().add("content");
        return content;
    }
}
