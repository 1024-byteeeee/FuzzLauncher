package com.fuzzlauncher.ui;

import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.*;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;

/** Original puzzle-piece icon, drawn at the same small scale as the retro navigation assets. */
final class ModIcon {
    private ModIcon() {
    }

    static Image create() {
        Canvas canvas = new Canvas(32, 32);
        GraphicsContext graphics = canvas.getGraphicsContext2D();
        String piece = "M3 7 H10 V5 C8 5 8 1 12 1 C16 1 16 5 14 5 V7 H21 V14 H23 "
            + "C23 12 27 12 27 16 C27 20 23 20 23 18 H21 V25 H14 V23 C16 23 16 19 12 19 "
            + "C8 19 8 23 10 23 V25 H3 V18 H5 C7 20 9 18 9 16 C9 14 7 12 5 14 H3 Z";
        graphics.setLineWidth(1);
        graphics.save();
        graphics.translate(3, 4);
        graphics.beginPath();
        graphics.appendSVGPath(piece);
        graphics.setFill(Color.web("#d8a028"));
        graphics.fill();
        graphics.setStroke(Color.web("#202020"));
        graphics.stroke();
        graphics.restore();
        graphics.beginPath();
        graphics.appendSVGPath(piece);
        graphics.setFill(Color.web("#2878c0"));
        graphics.fill();
        graphics.setStroke(Color.web("#202020"));
        graphics.stroke();
        graphics.setStroke(Color.web("#a8dcff"));
        graphics.strokeLine(4, 8, 10, 8);
        graphics.strokeLine(4, 8, 4, 13);
        graphics.strokeLine(15, 8, 20, 8);
        graphics.setStroke(Color.web("#104078"));
        graphics.strokeLine(20, 19, 20, 24);
        graphics.strokeLine(4, 24, 9, 24);
        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setFill(Color.TRANSPARENT);
        return canvas.snapshot(parameters, null);
    }
}
