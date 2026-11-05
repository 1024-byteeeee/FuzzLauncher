package com.fuzzlauncher.ui.common;

import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.control.ButtonBase;
import javafx.scene.input.MouseButton;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.function.BooleanSupplier;

/** Shares custom title-bar drag handling across the main window and its dialogs. */
public final class WindowDrag {
    private static final Object NATIVE_MOVE = new Object();

    private WindowDrag() {
    }

    public static void configure(Window owner, BooleanSupplier nativeMove) {
        owner.getProperties().put(NATIVE_MOVE, nativeMove);
    }

    public static void install(Node bar, Window owner) {
        var gesture = new Gesture();
        bar.setOnMousePressed(event -> {
            gesture.offset = null;
            gesture.nativeAttempted = false;
            Window window = windowOf(bar);
            if (event.getButton() != MouseButton.PRIMARY || !movable(window)) {
                return;
            }
            for (Node node = event.getTarget() instanceof Node target ? target : null; node != null && node != bar; node = node.getParent()) {
                if (node instanceof ButtonBase) {
                    return;
                }
            }
            gesture.offset = new Point2D(event.getScreenX() - window.getX(), event.getScreenY() - window.getY());
        });
        bar.setOnMouseDragged(event -> {
            Window window = windowOf(bar);
            if (gesture.offset == null || !event.isPrimaryButtonDown() || !movable(window)) {
                return;
            }
            if (!gesture.nativeAttempted) {
                gesture.nativeAttempted = true;
                BooleanSupplier nativeMove = findNative(window);
                if (nativeMove == null) {
                    nativeMove = findNative(owner);
                }
                if (window.isFocused() && nativeMove != null && nativeMove.getAsBoolean()) {
                    gesture.offset = null;
                    event.consume();
                    return;
                }
            }
            window.setX(event.getScreenX() - gesture.offset.getX());
            window.setY(event.getScreenY() - gesture.offset.getY());
        });
        bar.setOnMouseReleased(event -> gesture.offset = null);
    }

    private static Window windowOf(Node node) {
        return node.getScene() == null ? null : node.getScene().getWindow();
    }

    private static boolean movable(Window window) {
        return window != null && (!(window instanceof Stage stage) || (!stage.isMaximized() && !stage.isFullScreen()));
    }

    private static BooleanSupplier findNative(Window window) {
        for (Window current = window; current != null; current = current instanceof Stage stage ? stage.getOwner() : null) {
            if (current.getProperties().get(NATIVE_MOVE) instanceof BooleanSupplier nativeMove) {
                return nativeMove;
            }
        }
        return null;
    }

    private static final class Gesture {
        private Point2D offset;
        private boolean nativeAttempted;
    }
}
