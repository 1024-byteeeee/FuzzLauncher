package com.fuzzlauncher.ui.common;

import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.*;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;

/** Small information symbol matching the retro navigation's blue and gray palette. */
final class AboutIcon {
    private AboutIcon() {
    }

    static Image create() {
        Canvas canvas = new Canvas(32, 32);
        GraphicsContext graphics = canvas.getGraphicsContext2D();
        graphics.setFill(Color.web("#606060"));
        graphics.fillOval(4, 5, 27, 26);
        graphics.setFill(Color.web("#202020"));
        graphics.fillOval(1, 1, 28, 28);
        graphics.setFill(Color.web("#000080"));
        graphics.fillOval(2, 2, 26, 26);
        graphics.setStroke(Color.web("#809cff"));
        graphics.strokeArc(3, 3, 24, 24, 30, 140, ArcType.OPEN);
        graphics.setFill(Color.WHITE);
        graphics.fillRect(13, 7, 4, 4);
        graphics.fillRect(11, 13, 6, 3);
        graphics.fillRect(13, 16, 4, 6);
        graphics.fillRect(10, 22, 10, 3);
        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setFill(Color.TRANSPARENT);
        return canvas.snapshot(parameters, null);
    }
}
