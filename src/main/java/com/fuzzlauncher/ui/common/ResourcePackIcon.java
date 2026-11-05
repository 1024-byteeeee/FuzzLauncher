package com.fuzzlauncher.ui.common;

import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;

/** Pixel artwork for the resource-pack page and entries without a pack.png. */
final class ResourcePackIcon {
    private ResourcePackIcon() {
    }

    static Image create() {
        Canvas canvas = new Canvas(32, 32);
        var paint = canvas.getGraphicsContext2D();
        paint.setFill(Color.web("#25252a"));
        paint.fillRect(3, 4, 23, 22);
        paint.fillRect(7, 8, 23, 22);
        paint.setFill(Color.web("#808080"));
        paint.fillRect(4, 5, 21, 20);
        paint.setFill(Color.web("#c0c0c0"));
        paint.fillRect(8, 9, 21, 20);
        paint.setFill(Color.WHITE);
        paint.fillRect(8, 9, 20, 1);
        paint.fillRect(8, 9, 1, 19);
        paint.setFill(Color.web("#356aab"));
        paint.fillRect(11, 12, 15, 14);
        paint.setFill(Color.web("#fff4a8"));
        paint.fillRect(21, 14, 3, 3);
        paint.setFill(Color.web("#397433"));
        paint.fillPolygon(new double[] {11, 16, 20, 23, 26, 26, 11},
            new double[] {22, 16, 22, 19, 22, 26, 26}, 7);
        paint.setFill(Color.web("#7aad42"));
        paint.fillRect(11, 24, 15, 2);
        var parameters = new SnapshotParameters();
        parameters.setFill(Color.TRANSPARENT);
        return canvas.snapshot(parameters, null);
    }
}
