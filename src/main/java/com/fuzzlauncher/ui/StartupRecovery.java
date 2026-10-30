package com.fuzzlauncher.ui;

import com.fuzzlauncher.infrastructure.*;

import javafx.scene.control.*;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.*;

/** Explicit startup recovery choices; closing a recovery dialog never authorizes a write. */
public final class StartupRecovery {
    @FunctionalInterface
    public interface ReadConfiguration {
        void run() throws IOException;
    }

    private StartupRecovery() {
    }

    public static boolean configurations(Window owner, Path directory, ReadConfiguration read) {
        while (true) {
            try {
                read.run();
                return true;
            } catch (IOException | RuntimeException ex) {
                RecoverableJson.Damaged damaged = ex instanceof RecoverableJson.Damaged value ? value : null;
                ButtonType restore = new ButtonType("恢复备份", ButtonBar.ButtonData.YES);
                ButtonType retry = new ButtonType("重新读取", ButtonBar.ButtonData.OK_DONE);
                ButtonType folder = new ButtonType("打开配置目录", ButtonBar.ButtonData.LEFT);
                ButtonType exit = new ButtonType("退出", ButtonBar.ButtonData.CANCEL_CLOSE);
                Alert dialog = RetroDialogs.alert(owner, Alert.AlertType.ERROR, "配置需要修复", ex.getMessage());
                dialog.getButtonTypes().setAll(retry, folder, exit);
                if (damaged != null && damaged.hasValidBackup()) {
                    dialog.getButtonTypes().addFirst(restore);
                }
                ButtonType answer = dialog.showAndWait().orElse(exit);
                if (answer == exit) {
                    return false;
                }
                if (answer == folder) {
                    open(owner, damaged == null ? directory.resolve("config") : damaged.file().getParent());
                } else if (answer == restore) {
                    try {
                        damaged.restore();
                    } catch (IOException failure) {
                        RetroDialogs.alert(owner, Alert.AlertType.ERROR, "恢复失败", failure.getMessage()).showAndWait();
                    }
                }
            }
        }
    }

    public static boolean operations(Window owner, Path directory) {
        while (true) {
            try {
                var records = OperationJournal.pending(directory);
                if (records.isEmpty()) {
                    return true;
                }
                ButtonType restore = new ButtonType("恢复或清理并继续", ButtonBar.ButtonData.OK_DONE);
                ButtonType folder = new ButtonType("打开恢复目录", ButtonBar.ButtonData.LEFT);
                ButtonType exit = new ButtonType("保留记录并退出", ButtonBar.ButtonData.CANCEL_CLOSE);
                String descriptions = String.join("\n", records.stream().map(OperationJournal::description).toList());
                Alert dialog = RetroDialogs.alert(owner, Alert.AlertType.CONFIRMATION, "发现未完成操作",
                    descriptions + "\n\n未提交的操作将回退；已提交的操作只清理临时文件。\n恢复时发现文件被改动会停止并保留备份。迁移后的旧目录不会在此自动删除。");
                dialog.getButtonTypes().setAll(restore, folder, exit);
                ButtonType answer = dialog.showAndWait().orElse(exit);
                if (answer == exit) {
                    return false;
                }
                if (answer == folder) {
                    open(owner, directory);
                    continue;
                }
                recoverInBackground(owner, records);
            } catch (IOException | RuntimeException ex) {
                ButtonType retry = new ButtonType("重试", ButtonBar.ButtonData.OK_DONE);
                ButtonType folder = new ButtonType("打开恢复目录", ButtonBar.ButtonData.LEFT);
                ButtonType exit = new ButtonType("退出", ButtonBar.ButtonData.CANCEL_CLOSE);
                Alert dialog = RetroDialogs.alert(owner, Alert.AlertType.ERROR, "未完成恢复", ex.getMessage());
                dialog.getButtonTypes().setAll(retry, folder, exit);
                ButtonType answer = dialog.showAndWait().orElse(exit);
                if (answer == exit) {
                    return false;
                }
                if (answer == folder) {
                    open(owner, directory);
                }
            }
        }
    }

    private static void recoverInBackground(Window owner, java.util.List<OperationJournal> records) throws IOException {
        Dialog<ButtonType> progress = new Dialog<>();
        RetroDialogs.decorate(progress, owner, "正在恢复操作");
        progress.getDialogPane().setContent(new Label("正在恢复文件，请稍候…"));
        progress.getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);
        progress.getDialogPane().lookupButton(ButtonType.CLOSE).setVisible(false);
        progress.getDialogPane().lookupButton(ButtonType.CLOSE).setManaged(false);
        progress.getDialogPane().getHeader().lookup(".window-control").setDisable(true);
        var finished = new java.util.concurrent.atomic.AtomicBoolean();
        var failure = new java.util.concurrent.atomic.AtomicReference<Exception>();
        progress.setOnCloseRequest(event -> {
            if (!finished.get()) {
                event.consume();
            }
        });
        progress.addEventHandler(DialogEvent.DIALOG_SHOWN, event -> Thread.ofVirtual().name("startup-recovery").start(() -> {
            try {
                for (OperationJournal record : records) {
                    record.recover();
                }
            } catch (Exception ex) {
                failure.set(ex);
            } finally {
                javafx.application.Platform.runLater(() -> {
                    finished.set(true);
                    progress.setResult(ButtonType.CLOSE);
                    progress.close();
                });
            }
        }));
        progress.showAndWait();
        if (failure.get() != null) {
            throw new IOException("恢复未完成：" + failure.get().getMessage(), failure.get());
        }
    }

    private static void open(Window owner, Path directory) {
        try {
            Files.createDirectories(directory);
            java.awt.Desktop.getDesktop().open(directory.toFile());
        } catch (IOException | RuntimeException ex) {
            RetroDialogs.alert(owner, Alert.AlertType.ERROR, "无法打开目录", ex.getMessage()).showAndWait();
        }
    }
}
