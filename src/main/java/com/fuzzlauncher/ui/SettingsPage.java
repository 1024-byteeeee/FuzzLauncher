package com.fuzzlauncher.ui;

import com.fuzzlauncher.domain.*;
import com.fuzzlauncher.infrastructure.GameRootMigration;

import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/** SettingsPage presentation; all application operations are delegated to the ViewModel. */
public final class SettingsPage extends RetroPage {
    public SettingsPage(Stage stage, LauncherViewModel vm, Consumer<Integer> navigate) {
        super(stage, vm, navigate);
        setCenter(build());
    }

    private Node build() {
        LauncherSettings config = vm.settingsProperty().get();
        TextField root = new TextField(config.gameRoot());
        root.setId("game-root-path");
        root.setMinWidth(0);
        HBox.setHgrow(root, Priority.ALWAYS);
        Button browseRoot = button("浏览", () -> {
            Path initial;
            try {
                initial = Path.of(root.getText());
            } catch (InvalidPathException ex) {
                initial = Path.of(config.gameRoot());
            }
            RetroPathDialog.directory(stage, "选择游戏根目录", initial).ifPresent(chosen -> root.setText(chosen.toString()));
        });
        browseRoot.setId("browse-game-root");
        browseRoot.setMinWidth(Region.USE_PREF_SIZE);
        browseRoot.disableProperty().bind(vm.busyProperty().or(vm.gameRunningProperty()));
        root.disableProperty().bind(vm.busyProperty().or(vm.gameRunningProperty()));
        HBox rootControl = new HBox(6, root, browseRoot);
        rootControl.setMinWidth(0);
        TextField dataPath = new TextField(config.dataDirectory().isBlank() ? vm.dataDirectory().toString() : config.dataDirectory());
        dataPath.setId("launcher-data-path");
        dataPath.setMinWidth(0);
        HBox.setHgrow(dataPath, Priority.ALWAYS);
        Button browse = button("浏览", () -> {
            Path initial;
            try {
                initial = Path.of(dataPath.getText());
            } catch (InvalidPathException ex) {
                initial = vm.dataDirectory();
            }
            RetroPathDialog.directory(stage, "选择启动器数据目录", initial).ifPresent(chosen -> dataPath.setText(chosen.toString()));
        });
        browse.setMinWidth(Region.USE_PREF_SIZE);
        HBox dataControl = new HBox(6, dataPath, browse);
        dataControl.setMinWidth(0);
        ComboBox<IsolationMode> mode = new RetroComboBox<>();
        mode.getItems().setAll(IsolationMode.values());
        mode.setValue(config.isolation());
        mode.setMaxWidth(Double.MAX_VALUE);
        ComboBox<DownloadSource> source = new RetroComboBox<>();
        source.setId("download-source");
        source.getItems().setAll(DownloadSource.values());
        source.setValue(config.downloadSource());
        source.setMaxWidth(Double.MAX_VALUE);
        ComboBox<LauncherVisibility> visibility = new RetroComboBox<>();
        visibility.setId("launcher-visibility");
        visibility.getItems().setAll(LauncherVisibility.values());
        visibility.setValue(config.launcherVisibility());
        visibility.setMaxWidth(Double.MAX_VALUE);
        visibility.disableProperty().bind(vm.busyProperty().or(vm.gameRunningProperty()));
        Button save = button("保存设置", () -> {
            try {
                if (root.getText().isBlank()) {
                    throw new IllegalArgumentException("游戏根目录不能为空");
                }
                Path normalized = Path.of(root.getText()).toAbsolutePath().normalize();
                boolean rootChanged = !GameRootMigration.sameDirectory(normalized, Path.of(vm.settingsProperty().get().gameRoot()));
                Path requested = dataPath.getText().isBlank() ? vm.dataDirectory()
                    : Path.of(dataPath.getText()).toAbsolutePath().normalize();
                if (mode.getValue() != config.isolation() && !confirm("更改全局隔离模式", "跟随全局设置的实例将切换游戏目录。\n旧存档和模组保留在原目录，不会自动迁移。是否继续？")) {
                    return;
                }
                boolean migrate = false;
                if (rootChanged) {
                    ButtonType move = new ButtonType("迁移并重启", ButtonBar.ButtonData.YES);
                    ButtonType keep = new ButtonType("不迁移，仅重启", ButtonBar.ButtonData.NO);
                    Alert question = RetroDialogs.alert(stage, Alert.AlertType.CONFIRMATION, "切换游戏根目录",
                        "是否将原目录中的实例、存档、模组及共享资源迁移到新目录？\n" + "迁移目标必须为空；选择不迁移会保留原文件。保存成功后将自动重启。");
                    question.getDialogPane().setId("game-root-migration-question");
                    question.getButtonTypes().setAll(move, keep, new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE));
                    var answer = question.showAndWait();
                    if (answer.isEmpty() || (answer.get() != move && answer.get() != keep)) {
                        return;
                    }
                    migrate = answer.get() == move;
                }
                vm.saveSettings(new LauncherSettings(normalized.toString(), mode.getValue(), source.getValue(), requested.toString(),
                    visibility.getValue()), migrate);
            } catch (IllegalArgumentException | java.io.IOException ex) {
                message(Alert.AlertType.ERROR, "设置无效", ex.getMessage());
            }
        });
        save.disableProperty().bind(vm.busyProperty().or(vm.gameRunningProperty()));
        Button data = button("打开启动器数据目录", () -> openFolder(vm.dataDirectory()));
        Button logs = button("打开启动器日志目录", () -> openFolder(vm.dataDirectory().resolve("logs")));
        VBox content = new VBox(25, label("启动器设置", "section-title"), form(new String[] { "游戏根目录:", "数据文件目录:", "默认隔离:", "下载源:", "启动器可见性:" },
            new Node[] { rootControl, dataControl, mode, source, visibility }), new HBox(10, save, data, logs));
        content.setPadding(new Insets(30));
        return content;
    }
}
