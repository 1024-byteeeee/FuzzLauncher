package com.fuzzlauncher.ui.common;

import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.*;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;

/** Pixel-art expansion chip for the mod manager. */
final class ModIcon {
    private ModIcon() {
    }

    static Image create() {
        Canvas canvas = new Canvas(32, 32);
        GraphicsContext graphics = canvas.getGraphicsContext2D();
        graphics.setFill(Color.web("#926220"));
        for (int pin = 9; pin <= 21; pin += 4) {
            graphics.fillRect(pin, 2, 2, 28);
            graphics.fillRect(2, pin, 28, 2);
        }
        graphics.setFill(Color.web("#202020"));
        graphics.fillRect(6, 6, 20, 20);
        graphics.setFill(Color.web("#287468"));
        graphics.fillRect(7, 7, 18, 18);
        graphics.setFill(Color.web("#82c5a5"));
        graphics.fillRect(7, 7, 18, 1);
        graphics.fillRect(7, 8, 1, 17);
        graphics.setFill(Color.web("#153b36"));
        graphics.fillRect(11, 11, 10, 10);
        graphics.setFill(Color.web("#e9ca71"));
        graphics.fillRect(15, 12, 2, 8);
        graphics.fillRect(12, 15, 8, 2);
        SnapshotParameters parameters = new SnapshotParameters();
        parameters.setFill(Color.TRANSPARENT);
        return canvas.snapshot(parameters, null);
    }
}
