package com.fuzzlauncher.ui.common;

import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;

final class SchematicIcon {
    private SchematicIcon() {
    }

    static Image create() {
        Canvas canvas = new Canvas(32, 32);
        var paint = canvas.getGraphicsContext2D();
        paint.setFill(Color.web("#202020"));
        paint.fillRect(3, 5, 27, 23);
        paint.setFill(Color.web("#204b98"));
        paint.fillRect(4, 6, 25, 21);
        paint.setFill(Color.web("#8fbbef"));
        paint.fillRect(4, 6, 25, 1);
        paint.fillRect(4, 6, 1, 21);
        paint.setStroke(Color.web("#4b74b7"));
        for (int x = 8; x < 28; x += 5) {
            paint.strokeLine(x + .5, 8, x + .5, 26);
        }
        for (int y = 11; y < 26; y += 5) {
            paint.strokeLine(6, y + .5, 28, y + .5);
        }
        paint.setStroke(Color.web("#e1f2ff"));
        paint.strokePolygon(new double[] {9.5, 16.5, 23.5, 16.5}, new double[] {13.5, 9.5, 13.5, 17.5}, 4);
        paint.strokePolyline(new double[] {9.5, 9.5, 16.5, 23.5, 23.5}, new double[] {13.5, 19.5, 23.5, 19.5, 13.5}, 5);
        paint.strokeLine(16.5, 17.5, 16.5, 23.5);
        var options = new SnapshotParameters();
        options.setFill(Color.TRANSPARENT);
        return canvas.snapshot(options, null);
    }
}
