package com.fuzzlauncher.ui.shell;

import com.fuzzlauncher.application.model.RunningGame;
import com.fuzzlauncher.ui.common.RetroDialogs;
import com.fuzzlauncher.ui.common.WindowResize;
import com.fuzzlauncher.ui.viewmodel.GamesViewModel;

import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.EnumMap;
import java.util.List;
import java.util.stream.Collectors;

final class GameLogDialog extends Dialog<ButtonType> {
    private static final int MAX_LINES = 1000;

    GameLogDialog(Stage owner, GamesViewModel games, RunningGame game) {
        initModality(Modality.NONE);
        setDialogPane(new DialogPane() {
            @Override
            protected Node createButtonBar() {
                ButtonBar bar = (ButtonBar) super.createButtonBar();
                bar.setButtonOrder("+UC");
                bar.getStyleClass().add("game-log-buttons");
                return bar;
            }
        });
        RetroDialogs.decorate(this, owner, "测试游戏日志");
        setResizable(true);
        getDialogPane().setId("test-game-log-dialog");
        getDialogPane().setPrefSize(900, 640);
        getDialogPane().setMinSize(560, 360);
        WindowResize.install(getDialogPane());
        ButtonType stopGame = new ButtonType("终止游戏", ButtonBar.ButtonData.OTHER);
        getDialogPane().getButtonTypes().addAll(stopGame, ButtonType.CLOSE);
        ((Button) getDialogPane().lookupButton(ButtonType.CLOSE)).setText("关闭");
        var entries = FXCollections.<GameLogEntry>observableArrayList();
        var visible = new FilteredList<>(entries);
        ListView<GameLogEntry> output = new ListView<>(visible);
        output.setId("test-game-log-output");
        output.getStyleClass().add("game-log-output");
        output.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        output.setCellFactory(list -> new ListCell<>() {
            {
                setWrapText(true);
                setPrefWidth(0);
            }

            @Override
            protected void updateItem(GameLogEntry entry, boolean empty) {
                super.updateItem(entry, empty);
                getStyleClass().removeIf(style -> style.startsWith("game-log-"));
                setText(empty ? null : entry.text());
                if (!empty) {
                    getStyleClass().add(entry.level().cssClass());
                }
            }
        });
        output.setOnKeyPressed(event -> {
            if (event.isShortcutDown() && event.getCode() == KeyCode.C && !output.getSelectionModel().isEmpty()) {
                ClipboardContent content = new ClipboardContent();
                content.putString(output.getSelectionModel().getSelectedItems().stream().map(GameLogEntry::text)
                    .collect(Collectors.joining(System.lineSeparator())));
                Clipboard.getSystemClipboard().setContent(content);
                event.consume();
            }
        });
        VBox.setVgrow(output, Priority.ALWAYS);
        Label status = new Label("游戏运行中");
        status.setId("test-game-log-status");
        status.setMinWidth(0);
        status.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(status, Priority.ALWAYS);
        CheckBox autoScroll = new CheckBox("自动滚动");
        autoScroll.setSelected(true);
        var filters = new EnumMap<GameLogEntry.Level, ToggleButton>(GameLogEntry.Level.class);
        HBox levels = new HBox(5);
        Runnable scrollToEnd = () -> {
            if (autoScroll.isSelected() && !visible.isEmpty()) {
                output.scrollTo(visible.size() - 1);
            }
        };
        for (var level : GameLogEntry.Level.values()) {
            if (level == GameLogEntry.Level.OTHER) {
                continue;
            }
            ToggleButton filter = new ToggleButton(level.name() + " 0");
            filter.setId(level.cssClass() + "-filter");
            filter.getStyleClass().addAll("button", "game-log-filter", level.cssClass());
            filter.setSelected(true);
            filter.setOnAction(event -> {
                visible.setPredicate(entry -> !filters.containsKey(entry.level()) || filters.get(entry.level()).isSelected());
                scrollToEnd.run();
            });
            filters.put(level, filter);
            levels.getChildren().add(filter);
        }
        Button stop = (Button) getDialogPane().lookupButton(stopGame);
        stop.setId("stop-test-game");
        stop.setDisable(!game.process().isAlive());
        stop.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            stop.setDisable(true);
            status.setText("正在终止游戏…");
            Task<Void> termination = new Task<>() {
                @Override
                protected Void call() throws IOException {
                    games.stopTestGame(game);
                    return null;
                }
            };
            termination.setOnFailed(failure -> {
                status.setText("无法终止游戏：" + termination.getException().getMessage());
                stop.setDisable(!game.process().isAlive());
            });
            Thread.ofVirtual().name("stop-test-game").start(termination);
        });
        HBox toolbar = new HBox(10, status, levels);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        getDialogPane().setContent(new VBox(10, toolbar, output, autoScroll));

        Task<List<GameLogEntry>> reader = new Task<>() {
            private List<GameLogEntry> latest = List.of();

            @Override
            protected List<GameLogEntry> call() throws IOException, InterruptedException {
                games.followTestGameLog(game, this::isCancelled, snapshot -> {
                    latest = GameLogEntry.parse(snapshot.text(), MAX_LINES);
                    updateValue(latest);
                });
                return latest;
            }
        };
        reader.valueProperty().addListener((observable, old, value) -> {
            int prefix = 0;
            while (prefix < entries.size() && prefix < value.size() && entries.get(prefix).equals(value.get(prefix))) {
                prefix++;
            }
            entries.remove(prefix, entries.size());
            entries.addAll(value.subList(prefix, value.size()));
            var counts = new EnumMap<GameLogEntry.Level, Integer>(GameLogEntry.Level.class);
            value.forEach(entry -> counts.merge(entry.level(), 1, Integer::sum));
            filters.forEach((level, filter) -> filter.setText(level.name() + " " + counts.getOrDefault(level, 0)));
            scrollToEnd.run();
        });
        autoScroll.setOnAction(event -> scrollToEnd.run());
        reader.setOnSucceeded(event -> {
            status.setText("游戏已退出，退出码 " + game.process().exitValue());
            stop.setDisable(true);
        });
        reader.setOnFailed(event -> status.setText("无法读取游戏日志：" + reader.getException().getMessage()));
        setOnHidden(event -> reader.cancel(true));
        setOnShowing(event -> Thread.ofVirtual().name("test-game-log").start(reader));
    }
}
