package com.fuzzlauncher.ui.common;

import javafx.event.EventHandler;
import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

/** Supplies resize borders for windows with custom chrome. */
public final class WindowResize {
    private static final int RESIZE_EDGE = 6;
    private static final Cursor[][] RESIZE_CURSORS = {
        { Cursor.NW_RESIZE, Cursor.N_RESIZE, Cursor.NE_RESIZE },
        { Cursor.W_RESIZE, Cursor.DEFAULT, Cursor.E_RESIZE },
        { Cursor.SW_RESIZE, Cursor.S_RESIZE, Cursor.SE_RESIZE }
    };

    private WindowResize() {
    }

    public static void install(Region root) {
        // The custom title bar uses an undecorated stage, so there are no native resize borders.
        root.addEventFilter(MouseEvent.ANY, new EventHandler<MouseEvent>() {
            private Rectangle2D initialBounds;
            private Point2D press;
            private int horizontal, vertical;

            @Override
            public void handle(MouseEvent event) {
                if (root.getScene() == null || !(root.getScene().getWindow() instanceof Stage window)
                    || !window.isResizable() || window.isMaximized() || window.isFullScreen()) {
                    initialBounds = null;
                    root.setCursor(Cursor.DEFAULT);
                    return;
                }
                double x = event.getSceneX(), y = event.getSceneY();
                int column = x <= RESIZE_EDGE ? -1 : x >= root.getWidth() - RESIZE_EDGE ? 1 : 0;
                int row = y <= RESIZE_EDGE ? -1 : y >= root.getHeight() - RESIZE_EDGE ? 1 : 0;
                Cursor edge = RESIZE_CURSORS[row + 1][column + 1];
                if (event.getEventType() == MouseEvent.MOUSE_MOVED) {
                    root.setCursor(edge);
                } else if (event.getEventType() == MouseEvent.MOUSE_PRESSED && event.isPrimaryButtonDown() && edge != Cursor.DEFAULT) {
                    initialBounds = new Rectangle2D(window.getX(), window.getY(), window.getWidth(), window.getHeight());
                    press = new Point2D(event.getScreenX(), event.getScreenY());
                    horizontal = column;
                    vertical = row;
                    event.consume();
                } else if (event.getEventType() == MouseEvent.MOUSE_DRAGGED && initialBounds != null) {
                    if (horizontal != 0) {
                        double width = Math.max(Math.max(root.minWidth(-1), window.getMinWidth()),
                            initialBounds.getWidth() + horizontal * (event.getScreenX() - press.getX()));
                        window.setWidth(width);
                        if (horizontal < 0) {
                            window.setX(initialBounds.getMaxX() - width);
                        }
                    }
                    if (vertical != 0) {
                        double height = Math.max(Math.max(root.minHeight(-1), window.getMinHeight()),
                            initialBounds.getHeight() + vertical * (event.getScreenY() - press.getY()));
                        window.setHeight(height);
                        if (vertical < 0) {
                            window.setY(initialBounds.getMaxY() - height);
                        }
                    }
                    event.consume();
                } else if (event.getEventType() == MouseEvent.MOUSE_RELEASED && initialBounds != null) {
                    initialBounds = null;
                    root.setCursor(edge);
                    event.consume();
                }
            }
        });
    }
}
