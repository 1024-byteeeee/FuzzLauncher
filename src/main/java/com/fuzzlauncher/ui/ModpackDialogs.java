package com.fuzzlauncher.ui;

import com.fuzzlauncher.domain.*;
import com.fuzzlauncher.infrastructure.ModpackArchive;

import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.nio.file.*;
import java.util.*;

final class ModpackDialogs {
    private ModpackDialogs() {
    }

    static void importPack(Stage owner, LauncherViewModel vm) {
        RetroPathDialog.modpack(owner, Path.of(System.getProperty("user.home"))).ifPresent(archive -> importArchive(owner, vm, archive));
    }

    static void importArchive(Stage owner, LauncherViewModel vm, Path archive) {
        vm.inspectModpack(archive, info -> importOptions(owner, vm, archive, info));
    }

    static Dialog<ButtonType> importOptions(Stage owner, LauncherViewModel vm, Path archive, ModpackArchive.Info info) {
        Dialog<ButtonType> dialog = new Dialog<>();
        RetroDialogs.decorate(dialog, owner, "导入整合包");
        dialog.getDialogPane().setId("import-modpack-dialog");
        TextField name = new TextField(info.name());
        name.setId("modpack-instance-name");
        Label details = new Label(info.format() + " · Minecraft " + info.game() + "\n加载器：" + info.loader()
            + (info.loaderVersion() == null ? "" : " " + info.loaderVersion()) + "\n整合包版本：" + info.version());
        details.setWrapText(true);
        Label description = new Label(info.description());
        description.setWrapText(true);
        ModpackFileTree optional = new ModpackFileTree("可选客户端文件", path -> true);
        optional.setId("modpack-optional-files");
        java.util.List<ModpackArchive.ContentEntry> optionalEntries = java.util.List.of();
        String optionalError = "";
        try {
            optionalEntries = ModpackArchive.optionalFiles(info);
            optional.setEntries(optionalEntries);
        } catch (java.io.IOException | RuntimeException ex) {
            optionalError = "无法读取可选文件：" + ex.getMessage();
            optionalEntries = java.util.List.of();
            optional.setEntries(optionalEntries);
        }
        ((TreeView<?>) optional.lookup("#modpack-export-files")).setPrefHeight(180);
        optional.setVisible(!optionalEntries.isEmpty());
        optional.setManaged(optional.isVisible());
        java.util.Set<String> optionalPaths = new java.util.HashSet<>();
        for (var entry : optionalEntries) {
            optionalPaths.add(entry.path());
        }
        Label note = new Label(
            info.format() == ModpackFormat.SERVER ? "导入后使用独立目录，并在启动前检查服务器更新；你修改过的文件会保留。" : "导入后创建独立实例，自动安装游戏和加载器。整合包中的启动器命令与自定义启动参数不会执行。");
        note.setWrapText(true);
        Label error = new Label();
        error.setStyle("-fx-text-fill: #a00000;");
        error.setText(optionalError);
        VBox content = new VBox(12, details, description, new Label("实例名称"), name, optional, note, error);
        content.setPadding(new Insets(12));
        content.setPrefWidth(optionalEntries.isEmpty() ? 620 : 850);
        dialog.getDialogPane().setContent(content);
        ButtonType start = new ButtonType("开始导入", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(start, ButtonType.CANCEL);
        ((Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL)).setText("取消");
        dialog.getDialogPane().lookupButton(start).setDisable(!optionalError.isEmpty());
        dialog.getDialogPane().lookupButton(start).addEventFilter(ActionEvent.ACTION, event -> {
            try {
                new GameInstance("validation", name.getText(), info.game(), info.loader(), info.loaderVersion(), IsolationOverride.ISOLATED,
                    4096);
                java.util.Set<String> excluded = new java.util.HashSet<>(optionalPaths);
                excluded.removeAll(optional.selectedPaths());
                vm.importModpack(archive, name.getText().strip(), true, excluded);
            } catch (IllegalArgumentException ex) {
                error.setText(ex.getMessage());
                event.consume();
            }
        });
        dialog.show();
        return dialog;
    }

    static Dialog<ButtonType> exportPack(Stage owner, LauncherViewModel vm, GameInstance instance) {
        Dialog<ButtonType> dialog = new ModpackExportDialog(owner, vm, instance);
        dialog.show();
        return dialog;
    }
}
