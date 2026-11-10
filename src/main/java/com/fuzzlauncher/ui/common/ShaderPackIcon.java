package com.fuzzlauncher.ui.common;

import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;

final class ShaderPackIcon {
    private ShaderPackIcon() {
    }

    static Image create() {
        Canvas canvas = new Canvas(32, 32);
        var paint = canvas.getGraphicsContext2D();
        paint.setFill(Color.web("#202020"));
        paint.fillRect(2, 4, 28, 24);
        paint.setFill(Color.web("#527cb3"));
        paint.fillRect(3, 5, 26, 22);
        paint.setFill(Color.web("#9ac7e8"));
        paint.fillRect(3, 5, 26, 1);
        paint.fillRect(3, 6, 1, 21);
        paint.setFill(Color.web("#315445"));
        paint.fillPolygon(new double[] {4, 12, 19, 24, 29, 29, 4},
            new double[] {22, 14, 21, 16, 21, 27, 27}, 7);
        paint.setFill(Color.web("#71a351"));
        paint.fillPolygon(new double[] {4, 12, 16, 12, 8, 4},
            new double[] {22, 14, 18, 20, 25, 25}, 6);
        paint.setFill(Color.web("#ffda73"));
        paint.fillRect(19, 10, 6, 6);
        paint.fillRect(21, 7, 2, 2);
        paint.fillRect(16, 12, 2, 2);
        paint.fillRect(26, 12, 2, 2);
        paint.setFill(Color.web("#fff0af"));
        paint.fillRect(20, 11, 3, 2);
        var options = new SnapshotParameters();
        options.setFill(Color.TRANSPARENT);
        return canvas.snapshot(options, null);
    }
}
