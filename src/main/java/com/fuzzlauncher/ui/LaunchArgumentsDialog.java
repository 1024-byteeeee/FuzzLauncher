package com.fuzzlauncher.ui;

import com.fuzzlauncher.domain.GameInstance;
import com.fuzzlauncher.infrastructure.game.LaunchArguments;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.IOException;

/** Per-instance launch argument editor; -Xms/-Xmx edits stay synchronized with the detail page. */
final class LaunchArgumentsDialog extends Dialog<ButtonType> {
    LaunchArgumentsDialog(Stage owner, LauncherViewModel vm, GameInstance instance) {
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);
        RetroDialogs.decorate(this, owner, "启动参数");
        TextArea jvm = textArea(
            defaultJvm(instance) + (instance.jvmArguments().isEmpty() ? "" : " " + LaunchArguments.display(instance.jvmArguments())));
        jvm.setId("launch-jvm-arguments");
        TextArea game = textArea(LaunchArguments.display(instance.gameArguments()));
        game.setId("launch-game-arguments");
        TextArea automatic = textArea("正在检测 Java…");
        automatic.setId("automatic-jvm-arguments");
        automatic.setEditable(false);
        automatic.setPrefRowCount(4);
        LaunchArguments.Preview[] preview = new LaunchArguments.Preview[1];
        Runnable updatePreview = () -> {
            if (preview[0] == null) {
                return;
            }
            if (preview[0].environment() == null) {
                automatic.setText("未检测到所需 Java，请先安装或在实例详情中指定 Java 路径");
                return;
            }
            try {
                automatic.setText(LaunchArguments.display(preview[0].generated(jvm.getText())));
            } catch (IOException ex) {
                automatic.setText("请完成 JVM 参数输入后查看");
            }
        };
        jvm.textProperty().addListener((observable, before, after) -> updatePreview.run());
        var work = vm.jvmPreview(instance);
        java.util.concurrent.FutureTask<LaunchArguments.Preview> load = new java.util.concurrent.FutureTask<>(work) {
            @Override
            protected void done() {
                javafx.application.Platform.runLater(() -> {
                    if (isCancelled() || !isShowing()) {
                        return;
                    }
                    try {
                        preview[0] = get();
                        updatePreview.run();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } catch (java.util.concurrent.ExecutionException ex) {
                        automatic.setText("无法预览自动参数：" + ex.getCause().getMessage());
                    }
                });
            }
        };
        addEventHandler(DialogEvent.DIALOG_SHOWN, event -> Thread.ofVirtual().name("jvm-preview").start(load));
        addEventHandler(DialogEvent.DIALOG_HIDDEN, event -> load.cancel(true));
        VBox content = new VBox(10, section("JVM 参数", jvm), label("自动 JVM 参数"), automatic, section("游戏附加参数", game));
        content.setPadding(new Insets(10));
        content.setPrefWidth(640);
        getDialogPane().setContent(content);
        getDialogPane().setId("launch-arguments-dialog");
        ButtonType ok = new ButtonType("确定", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType reset = new ButtonType("恢复默认", ButtonBar.ButtonData.OTHER);
        getDialogPane().getButtonTypes().addAll(reset, ok, cancel);
        ((ButtonBar) getDialogPane().lookup(".button-bar")).setButtonOrder("+UOC");
        Button resetButton = (Button) getDialogPane().lookupButton(reset);
        resetButton.setId("restore-default-arguments");
        resetButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            jvm.setText(defaultJvm(instance));
            game.clear();
            event.consume();
        });
        ((Button) getDialogPane().lookupButton(ok)).addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            try {
                LaunchArguments.MemoryArguments parsed = LaunchArguments.parseMemory(jvm.getText());
                int minimum = parsed.minimum() != null ? parsed.minimum() : instance.minMemoryMb();
                int maximum = parsed.maximum() != null ? parsed.maximum() : instance.memoryMb();
                vm.updateInstance(
                    instance.withLaunchArguments(minimum, maximum, parsed.jvmArguments(), LaunchArguments.splitLegacy(game.getText())));
                close();
            } catch (IllegalArgumentException | IOException ex) {
                RetroDialogs.alert(owner, Alert.AlertType.ERROR, "启动参数无效", ex.getMessage()).showAndWait();
            } finally {
                event.consume();
            }
        });
    }

    private static String defaultJvm(GameInstance instance) {
        return LaunchArguments.display(LaunchArguments.defaultJvm(instance));
    }

    private static TextArea textArea(String text) {
        TextArea area = new TextArea(text);
        area.setWrapText(true);
        area.setPrefRowCount(5);
        area.setFont(new javafx.scene.text.Font("Courier New", 16));
        return area;
    }

    private static Node section(String title, TextArea area) {
        VBox box = new VBox(6, label(title), area);
        VBox.setVgrow(area, Priority.ALWAYS);
        return box;
    }

    private static Label label(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("section-title");
        return l;
    }

}
