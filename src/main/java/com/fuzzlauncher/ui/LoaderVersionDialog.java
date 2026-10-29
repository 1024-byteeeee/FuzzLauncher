package com.fuzzlauncher.ui;

import com.fuzzlauncher.domain.*;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.*;

/** A page-owned compatible-version query; closing the chooser cancels that query. */
final class LoaderVersionDialog extends Dialog<ButtonType> {
  LoaderVersionDialog(Stage owner, LauncherViewModel vm, GameInstance instance) {
    initOwner(owner);
    initModality(Modality.WINDOW_MODAL);
    RetroDialogs.decorate(this, owner, "修改加载器");
    getDialogPane().setId("loader-version-dialog");
    ComboBox<String> versions = new RetroComboBox<>();
    versions.setId("replacement-loader-version");
    versions.setMaxWidth(Double.MAX_VALUE);
    versions.setPromptText("正在查询兼容版本…");
    versions
        .promptTextProperty()
        .bind(Bindings.when(vm.busyProperty()).then("正在查询兼容版本…").otherwise("请选择加载器版本"));
    Label status = new Label();
    status.setWrapText(true);
    status.textProperty().bind(vm.statusProperty());
    VBox content =
        new VBox(
            14,
            new Label("Minecraft " + instance.gameVersion() + " · " + instance.loader()),
            new Label("当前版本：" + instance.loaderVersion()),
            versions,
            status);
    content.setPadding(new Insets(12));
    content.setPrefWidth(520);
    getDialogPane().setContent(content);
    ButtonType install = new ButtonType("更改", ButtonBar.ButtonData.OK_DONE);
    ButtonType cancel = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
    getDialogPane().getButtonTypes().setAll(install, cancel);
    versions.disableProperty().bind(vm.busyProperty());
    var button = (Button) getDialogPane().lookupButton(install);
    button
        .disableProperty()
        .bind(vm.busyProperty().or(versions.valueProperty().isNull()).or(vm.gameRunningProperty()));
    button.addEventFilter(
        javafx.event.ActionEvent.ACTION,
        event -> {
          String selected = versions.getValue();
          close();
          vm.reinstallLoader(instance, selected);
          event.consume();
        });
    ListChangeListener<String> listener =
        change -> {
          versions.getItems().setAll(vm.loaderVersions());
          versions.setValue(
              versions.getItems().contains(instance.loaderVersion())
                  ? instance.loaderVersion()
                  : versions.getItems().isEmpty() ? null : versions.getItems().getFirst());
        };
    vm.loaderVersions().addListener(listener);
    setOnHidden(
        event -> {
          vm.cancelCatalogRequests(this);
          vm.loaderVersions().removeListener(listener);
          status.textProperty().unbind();
          versions.promptTextProperty().unbind();
        });
    // Do not override the shared chrome's onShown icon handler.
    addEventHandler(
        DialogEvent.DIALOG_SHOWN,
        event ->
            Platform.runLater(
                () -> {
                  if (isShowing())
                    vm.loadLoaders(
                        instance.loader(),
                        new GameVersion(instance.gameVersion(), "release", "", ""),
                        this);
                }));
  }
}
