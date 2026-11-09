package com.fuzzlauncher.ui.common;

import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

public final class FileDrop {
    private FileDrop() {
    }

    public static void install(Node page, BooleanSupplier ready, UnaryOperator<List<Path>> select, Consumer<List<Path>> dropped) {
        Map<Dragboard, List<Path>> candidates = new WeakHashMap<>();
        EventHandler<DragEvent> over = event -> {
            if (!inside(page, event) || !ready.getAsBoolean()) {
                return;
            }
            Dragboard board = event.getDragboard();
            if (!candidates.containsKey(board)) {
                List<Path> paths = select.apply(readPaths(board));
                if (!paths.isEmpty()) {
                    candidates.put(board, paths);
                }
            }
            if (candidates.containsKey(board)) {
                if (board.getTransferModes().contains(TransferMode.COPY)) {
                    event.acceptTransferModes(TransferMode.COPY);
                } else {
                    event.acceptTransferModes(TransferMode.MOVE);
                }
                event.consume();
            }
        };
        EventHandler<DragEvent> drop = event -> {
            TransferMode mode = event.getTransferMode();
            if (!inside(page, event) || !ready.getAsBoolean() || (mode != TransferMode.COPY && mode != TransferMode.MOVE)) {
                return;
            }
            Dragboard board = event.getDragboard();
            List<Path> paths = readPaths(board);
            List<Path> previous = candidates.remove(board);
            List<Path> selected = paths.isEmpty() && previous != null ? previous : select.apply(paths);
            // A successful MOVE tells the drag source it may remove the original files.
            event.setDropCompleted(!selected.isEmpty() && mode == TransferMode.COPY);
            event.consume();
            if (!selected.isEmpty()) {
                dropped.accept(selected);
            }
        };
        page.sceneProperty().addListener((observable, oldScene, scene) -> {
            candidates.clear();
            if (oldScene != null) {
                oldScene.removeEventFilter(DragEvent.DRAG_OVER, over);
                oldScene.removeEventFilter(DragEvent.DRAG_DROPPED, drop);
            }
            if (scene != null) {
                scene.addEventFilter(DragEvent.DRAG_OVER, over);
                scene.addEventFilter(DragEvent.DRAG_DROPPED, drop);
            }
        });
    }

    private static boolean inside(Node page, DragEvent event) {
        return page.contains(page.sceneToLocal(event.getSceneX(), event.getSceneY()));
    }

    private static List<Path> readPaths(Dragboard board) {
        List<File> files = board.getFiles();
        if (files != null && !files.isEmpty()) {
            return files.stream().map(File::toPath).toList();
        }
        String url = board.getUrl();
        if (url != null && url.regionMatches(true, 0, "file:", 0, 5)) {
            try {
                return List.of(Path.of(URI.create(url.strip())));
            } catch (IllegalArgumentException ex) {
                return List.of();
            }
        }
        return List.of();
    }
}
