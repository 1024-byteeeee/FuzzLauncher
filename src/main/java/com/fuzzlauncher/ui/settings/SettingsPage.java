package com.fuzzlauncher.ui.settings;

import com.fuzzlauncher.domain.instance.IsolationMode;
import com.fuzzlauncher.domain.settings.DownloadSource;
import com.fuzzlauncher.domain.settings.LauncherSettings;
import com.fuzzlauncher.domain.settings.LauncherVisibility;
import com.fuzzlauncher.ui.common.RetroComboBox;
import com.fuzzlauncher.ui.common.RetroDialogs;
import com.fuzzlauncher.ui.common.RetroPage;
import com.fuzzlauncher.ui.common.RetroPathDialog;
import com.fuzzlauncher.ui.viewmodel.LauncherViewModel;

import javafx.geometry.*;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.util.StringConverter;

import java.io.IOException;
import java.nio.file.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** SettingsPage presentation; all application operations are delegated to the ViewModel. */
public final class SettingsPage extends RetroPage {
    private final LauncherViewModel vm;
    private BooleanSupplier unsavedChanges;

    public SettingsPage(Stage stage, LauncherViewModel vm, Consumer<Integer> navigate) {
        super(stage, navigate);
        this.vm = vm;
        setCenter(build());
    }

    public boolean confirmLeave() {
        if (vm.savingSettingsProperty().get()) {
            return false;
        }
        if (!unsavedChanges.getAsBoolean()) {
            return true;
        }
        ButtonType discard = new ButtonType("放弃更改", ButtonBar.ButtonData.NO);
        ButtonType edit = new ButtonType("继续编辑", ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert question = RetroDialogs.alert(stage, Alert.AlertType.CONFIRMATION, "未保存的设置", "设置有未保存的更改。是否放弃更改并离开设置页？");
        question.getDialogPane().setId("unsaved-settings-question");
        question.getButtonTypes().setAll(discard, edit);
        ((Button) question.getDialogPane().lookupButton(edit)).setDefaultButton(true);
        return question.showAndWait().filter(discard::equals).isPresent();
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
            RetroPathDialog.directory(stage, "选择游戏根目录", initial, vm.browser()).ifPresent(chosen -> root.setText(chosen.toString()));
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
            RetroPathDialog.directory(stage, "选择启动器数据目录", initial, vm.browser()).ifPresent(chosen -> dataPath.setText(chosen.toString()));
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
        source.setConverter(new StringConverter<>() {
            public String toString(DownloadSource value) {
                return value == null ? "" : vm.options().sources().get(value).label();
            }

            public DownloadSource fromString(String value) {
                return null;
            }
        });
        source.setValue(config.downloadSource());
        source.setMaxWidth(Double.MAX_VALUE);
        ComboBox<LauncherVisibility> visibility = new RetroComboBox<>();
        visibility.setId("launcher-visibility");
        visibility.getItems().setAll(LauncherVisibility.values());
        visibility.setValue(config.launcherVisibility());
        visibility.setMaxWidth(Double.MAX_VALUE);
        visibility.disableProperty().bind(vm.busyProperty().or(vm.gameRunningProperty()));
        CheckBox automaticDownloads = new CheckBox("自动");
        automaticDownloads.setId("auto-download-threads");
        automaticDownloads.setSelected(config.autoDownloadThreads());
        Spinner<Integer> downloadThreads = new Spinner<>(1, 128, config.downloadThreads());
        downloadThreads.setId("download-threads");
        downloadThreads.setEditable(true);
        downloadThreads.setPrefWidth(130);
        downloadThreads.disableProperty().bind(automaticDownloads.selectedProperty().or(vm.busyProperty()));
        automaticDownloads.disableProperty().bind(vm.busyProperty());
        Label automaticCount = new Label("自动：" + vm.options().automaticDownloads());
        HBox parallelism = new HBox(12, automaticDownloads, downloadThreads, automaticCount);
        parallelism.setAlignment(Pos.CENTER_LEFT);
        unsavedChanges = () -> {
            LauncherSettings saved = vm.settingsProperty().get();
            Path defaultData = vm.dataDirectory();
            String savedData = saved.dataDirectory().isBlank() ? defaultData.toString() : saved.dataDirectory();
            String enteredData = dataPath.getText().isBlank() ? defaultData.toString() : dataPath.getText();
            return !samePath(root.getText(), saved.gameRoot()) || !samePath(enteredData, savedData)
                || mode.getValue() != saved.isolation() || source.getValue() != saved.downloadSource()
                || visibility.getValue() != saved.launcherVisibility()
                || automaticDownloads.isSelected() != saved.autoDownloadThreads()
                || !sameDownloadCount(downloadThreads, automaticDownloads.isSelected(), saved.downloadThreads());
        };
        Button save = button("保存设置", () -> {
            try {
                if (!automaticDownloads.isSelected()) {
                    String input = downloadThreads.getEditor().getText().strip();
                    if (!input.matches("[0-9]{1,3}")) {
                        throw new IllegalArgumentException("下载并行数应为 1–128 之间的整数");
                    }
                    int count = Integer.parseInt(input);
                    if (count < 1 || count > 128) {
                        throw new IllegalArgumentException("下载并行数应在 1–128 之间");
                    }
                    downloadThreads.getValueFactory().setValue(count);
                }
                if (root.getText().isBlank()) {
                    throw new IllegalArgumentException("游戏根目录不能为空");
                }
                Path normalized = Path.of(root.getText()).toAbsolutePath().normalize();
                boolean rootChanged = !vm.settingsModel().sameDirectory(normalized, Path.of(vm.settingsProperty().get().gameRoot()));
                Path requested = dataPath.getText().isBlank() ? vm.dataDirectory()
                    : Path.of(dataPath.getText()).toAbsolutePath().normalize();
                if (mode.getValue() != vm.settingsProperty().get().isolation()
                    && !confirm("更改全局隔离模式", "跟随全局设置的实例将切换游戏目录。\n旧存档和模组保留在原目录，不会自动迁移。是否继续？")) {
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
                vm.settingsModel().saveSettings(new LauncherSettings(normalized.toString(), mode.getValue(), source.getValue(),
                    requested.toString(), visibility.getValue(), automaticDownloads.isSelected(), downloadThreads.getValue()), migrate);
            } catch (IllegalArgumentException | IOException ex) {
                message(Alert.AlertType.ERROR, "设置无效", ex.getMessage());
            }
        });
        save.disableProperty().bind(vm.busyProperty().or(vm.gameRunningProperty()));
        Button data = button("打开启动器数据目录", () -> openFolder(vm.dataDirectory()));
        Button logs = button("打开启动器日志目录", () -> openFolder(vm.dataDirectory().resolve("logs")));
        Button clearLogs = button("清理日志", () -> {
            if (confirm("清理日志", "将永久删除启动器日志目录中的启动器日志、轮转日志和游戏输出日志。\n"
                + "清理后会重新生成当前日志。是否继续？")) {
                vm.settingsModel().clearLogs();
            }
        });
        clearLogs.setId("clear-launcher-logs");
        clearLogs.disableProperty().bind(vm.busyProperty().or(vm.gameRunningProperty()));
        VBox content = new VBox(25, label("启动器设置", "section-title"),
            form(new String[] { "游戏根目录:", "数据文件目录:", "默认隔离:", "下载源:", "下载并行数:", "启动器可见性:" },
                new Node[] { rootControl, dataControl, mode, source, parallelism, visibility }),
            new HBox(10, save, data, logs, clearLogs));
        content.setPadding(new Insets(30));
        return content;
    }

    private static boolean samePath(String entered, String saved) {
        if (entered.equals(saved)) {
            return true;
        }
        if (entered.isBlank()) {
            return false;
        }
        try {
            return Path.of(entered).toAbsolutePath().normalize().equals(Path.of(saved).toAbsolutePath().normalize());
        } catch (InvalidPathException ex) {
            return false;
        }
    }

    private static boolean sameDownloadCount(Spinner<Integer> spinner, boolean automatic, int saved) {
        if (automatic) {
            return spinner.getValue() == saved;
        }
        String entered = spinner.getEditor().getText().strip();
        return entered.matches("[0-9]{1,3}") && Integer.parseInt(entered) == saved;
    }
}
