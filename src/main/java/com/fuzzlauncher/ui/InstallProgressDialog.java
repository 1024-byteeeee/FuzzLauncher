package com.fuzzlauncher.ui;

import javafx.beans.binding.Bindings;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;

/** Progress stays visible until the installation task has actually finished or cancelled. */
final class InstallProgressDialog extends Dialog<ButtonType> {
    InstallProgressDialog(Stage owner, LauncherViewModel vm) {
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);
        RetroDialogs.decorate(this, owner, "任务进度");
        Label message = new Label();
        message.setId("task-progress-message");
        message.setWrapText(true);
        message.setMinSize(496, 72);
        message.setPrefSize(496, 72);
        message.setMaxSize(496, 72);
        message.setAlignment(Pos.TOP_LEFT);
        Tooltip detail = new Tooltip();
        detail.textProperty().bind(vm.statusProperty());
        message.setTooltip(detail);
        message.textProperty().bind(vm.statusProperty());
        ProgressBar bar = new ProgressBar();
        bar.setId("task-progress-bar");
        // Keep both determinate and indeterminate skins in the same fixed layout slot.
        bar.setMinSize(496, 24);
        bar.setPrefSize(496, 24);
        bar.setMaxSize(496, 24);
        bar.progressProperty().bind(vm.progressProperty());
        Label percent = new Label();
        percent.setMinHeight(24);
        percent.setPrefHeight(24);
        percent.setMaxHeight(24);
        percent.textProperty().bind(Bindings.createStringBinding(() -> vm.progressProperty().get() < 0 ? "正在准备…"
            : String.format(java.util.Locale.ROOT, "%.0f%%", vm.progressProperty().get() * 100), vm.progressProperty()));
        VBox content = new VBox(16, new Label("正在处理，请稍候"), message, bar, percent);
        content.setPadding(new Insets(12));
        content.setMinSize(520, 222);
        content.setPrefSize(520, 222);
        content.setMaxSize(520, 222);
        getDialogPane().setContent(content);
        getDialogPane().setId("install-progress-dialog");
        ButtonType cancel = new ButtonType("取消任务", ButtonBar.ButtonData.CANCEL_CLOSE);
        getDialogPane().getButtonTypes().add(cancel);
        Button cancelButton = (Button) getDialogPane().lookupButton(cancel);
        cancelButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            vm.cancel();
            cancelButton.setDisable(true);
            event.consume();
        });
        setOnCloseRequest(event -> {
            if (vm.installingProperty().get()) {
                vm.cancel();
                event.consume();
            }
        });
        setOnHidden(event -> {
            message.textProperty().unbind();
            detail.textProperty().unbind();
            bar.progressProperty().unbind();
            percent.textProperty().unbind();
        });
    }
}
